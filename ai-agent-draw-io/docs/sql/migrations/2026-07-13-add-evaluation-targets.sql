-- R2: Target authority flows Working Copy -> Case Version -> Dataset Version -> Run.
-- Columns remain nullable so ambiguous legacy records can be reported and blocked instead of misclassified.
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='eval_case_working_copy' AND column_name='evaluation_target')=0,
  'ALTER TABLE eval_case_working_copy ADD COLUMN evaluation_target VARCHAR(32) NULL AFTER definition_json, ADD COLUMN target_migration_status VARCHAR(16) NULL AFTER evaluation_target, ADD KEY idx_eval_working_target (evaluation_target, status)',
  'SET @eval_target_working_noop=1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='eval_case_version' AND column_name='evaluation_target')=0,
  'ALTER TABLE eval_case_version ADD COLUMN evaluation_target VARCHAR(32) NULL AFTER artifact_ref, ADD COLUMN target_migration_status VARCHAR(16) NULL AFTER evaluation_target, ADD KEY idx_eval_case_target (evaluation_target, retired_at)',
  'SET @eval_target_case_noop=1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='eval_dataset' AND column_name='evaluation_target')=0,
  'ALTER TABLE eval_dataset ADD COLUMN evaluation_target VARCHAR(32) NULL AFTER dataset_class, ADD KEY idx_eval_dataset_target (evaluation_target, created_at)',
  'SET @eval_target_dataset_noop=1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='eval_dataset_version' AND column_name='evaluation_target')=0,
  'ALTER TABLE eval_dataset_version ADD COLUMN evaluation_target VARCHAR(32) NULL AFTER dataset_class',
  'SET @eval_target_dataset_version_noop=1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='eval_run' AND column_name='evaluation_target')=0,
  'ALTER TABLE eval_run ADD COLUMN evaluation_target VARCHAR(32) NULL AFTER dataset_version, ADD KEY idx_eval_run_target_created (evaluation_target, created_at)',
  'SET @eval_target_run_noop=1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- Explicit JSON values are confirmations. Exact canonical tags and nonblank assertions mirror
-- EvaluationTargetInferenceService; weak substring matches must never classify legacy data.
DROP TEMPORARY TABLE IF EXISTS eval_target_inference_signal;
CREATE TEMPORARY TABLE eval_target_inference_signal AS
SELECT id,
       JSON_UNQUOTE(JSON_EXTRACT(definition_json, '$.evaluationTarget')) explicit_target,
       JSON_CONTAINS(COALESCE(LOWER(JSON_EXTRACT(definition_json, '$.tags')), JSON_ARRAY()), JSON_QUOTE('target:full_agent')) tag_full,
       JSON_CONTAINS(COALESCE(LOWER(JSON_EXTRACT(definition_json, '$.tags')), JSON_ARRAY()), JSON_QUOTE('target:intent_router')) tag_router,
       JSON_CONTAINS(COALESCE(LOWER(JSON_EXTRACT(definition_json, '$.tags')), JSON_ARRAY()), JSON_QUOTE('target:drawing_quality')) tag_drawing,
       JSON_UNQUOTE(JSON_EXTRACT(definition_json, '$.fixtureVersion')) fixture_version,
       NULLIF(TRIM(JSON_UNQUOTE(JSON_EXTRACT(definition_json, '$.expected.routeType'))), '') IS NOT NULL route_present,
       JSON_TYPE(JSON_EXTRACT(definition_json, '$.expected.graph')) IS NOT NULL
         AND JSON_TYPE(JSON_EXTRACT(definition_json, '$.expected.graph')) <> 'NULL' graph_present
FROM eval_case_working_copy
WHERE target_migration_status IS NULL;

UPDATE eval_case_working_copy wc
JOIN eval_target_inference_signal sig ON sig.id=wc.id
SET wc.evaluation_target = CASE
      WHEN sig.explicit_target IN ('FULL_AGENT','INTENT_ROUTER','DRAWING_QUALITY') THEN sig.explicit_target
      WHEN sig.tag_full + sig.tag_router + sig.tag_drawing > 1 THEN NULL
      WHEN sig.tag_full = 1 THEN 'FULL_AGENT'
      WHEN sig.tag_router = 1 THEN 'INTENT_ROUTER'
      WHEN sig.tag_drawing = 1 THEN 'DRAWING_QUALITY'
      WHEN sig.fixture_version = 'fixture-v1' THEN 'FULL_AGENT'
      WHEN sig.route_present AND NOT sig.graph_present THEN 'INTENT_ROUTER'
      WHEN sig.graph_present AND NOT sig.route_present THEN 'DRAWING_QUALITY'
      ELSE NULL END,
    wc.target_migration_status = CASE
      WHEN sig.explicit_target IN ('FULL_AGENT','INTENT_ROUTER','DRAWING_QUALITY') THEN 'CONFIRMED'
      WHEN sig.tag_full + sig.tag_router + sig.tag_drawing > 1 THEN 'AMBIGUOUS'
      WHEN sig.tag_full + sig.tag_router + sig.tag_drawing = 1
        OR sig.fixture_version = 'fixture-v1'
        OR (sig.route_present AND NOT sig.graph_present)
        OR (sig.graph_present AND NOT sig.route_present) THEN 'INFERRED'
      ELSE 'AMBIGUOUS' END;

DROP TEMPORARY TABLE eval_target_inference_signal;

-- Keep the mutable canonical definition aligned with its authoritative Working Copy columns.
UPDATE eval_case_working_copy
SET definition_json=JSON_SET(definition_json, '$.evaluationTarget', evaluation_target)
WHERE evaluation_target IS NOT NULL
  AND (JSON_EXTRACT(definition_json, '$.evaluationTarget') IS NULL
       OR JSON_UNQUOTE(JSON_EXTRACT(definition_json, '$.evaluationTarget')) <> evaluation_target);

-- Only a unique PUBLISHED Working Copy lineage may populate immutable metadata; drafts are never trusted.
UPDATE eval_case_version cv
LEFT JOIN (
  SELECT case_id, case_version, MIN(evaluation_target) evaluation_target,
         MIN(target_migration_status) target_migration_status
  FROM eval_case_working_copy
  WHERE status='PUBLISHED' AND evaluation_target IS NOT NULL
  GROUP BY case_id, case_version
  HAVING COUNT(*)=1
) wc ON wc.case_id=cv.case_id AND wc.case_version=cv.case_version
SET cv.evaluation_target=wc.evaluation_target,
    cv.target_migration_status=IF(wc.evaluation_target IS NULL, 'AMBIGUOUS', wc.target_migration_status)
WHERE cv.target_migration_status IS NULL;

UPDATE eval_dataset d
JOIN (
  SELECT m.dataset_id, COUNT(DISTINCT cv.evaluation_target) targets, MIN(cv.evaluation_target) evaluation_target,
         SUM(cv.evaluation_target IS NULL) unresolved
  FROM eval_dataset_member m
  JOIN eval_case_version cv ON cv.case_id=m.case_id AND cv.case_version=m.case_version
  GROUP BY m.dataset_id
) inferred ON inferred.dataset_id=d.id
SET d.evaluation_target=inferred.evaluation_target
WHERE d.evaluation_target IS NULL AND inferred.targets=1 AND inferred.unresolved=0;

UPDATE eval_dataset_version dv
JOIN eval_dataset d ON d.id=dv.dataset_id
SET dv.evaluation_target=d.evaluation_target
WHERE dv.evaluation_target IS NULL;

UPDATE eval_run r JOIN eval_dataset_version dv ON dv.dataset_id=r.dataset_id AND dv.version=r.dataset_version
SET r.evaluation_target=dv.evaluation_target
WHERE r.evaluation_target IS NULL;

-- Operational report: AMBIGUOUS rows are the manual confirmation queue.
CREATE OR REPLACE VIEW eval_target_migration_report AS
SELECT 'WORKING_COPY' AS artifact_type, id AS artifact_id, evaluation_target, target_migration_status
FROM eval_case_working_copy
UNION ALL
SELECT 'CASE_VERSION', CONCAT(case_id, '@', case_version), evaluation_target, target_migration_status
FROM eval_case_version;
