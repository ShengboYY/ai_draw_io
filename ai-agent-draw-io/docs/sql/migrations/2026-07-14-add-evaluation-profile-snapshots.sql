-- R3: every new Eval Run persists the exact resolved Profile config used for execution.
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='eval_run' AND column_name='profile_id')=0,
  'ALTER TABLE eval_run ADD COLUMN profile_id VARCHAR(128) NULL AFTER candidate_ref, ADD COLUMN profile_version VARCHAR(32) NULL AFTER profile_id, ADD COLUMN profile_snapshot_json JSON NULL AFTER profile_version, ADD COLUMN profile_config_hash CHAR(64) NULL AFTER profile_snapshot_json, ADD KEY idx_eval_run_profile (profile_id,profile_version,created_at)',
  'SET @eval_profile_snapshot_noop=1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- Historical Runs did not retain the full preset. Preserve exactly what is known and label the
-- snapshot as legacy instead of pretending it can be reconstructed from today's built-in Profile.
UPDATE eval_run
SET profile_id='legacy-run',
    profile_version='1',
    profile_snapshot_json=JSON_OBJECT(
      'profileId','legacy-run',
      'profileVersion','1',
      'target',evaluation_target,
      'mode',mode,
      'repetitions',repetitions,
      'legacyExecutionProfileHash',execution_profile_hash,
      'reconstruction','partial'),
    profile_config_hash=NULL
WHERE profile_id IS NULL;

-- Hash the exact persisted JSON representation. The old execution hash is evidence inside the
-- partial snapshot, never a substitute for snapshot integrity.
UPDATE eval_run
SET profile_config_hash=SHA2(CAST(profile_snapshot_json AS CHAR),256)
WHERE profile_id='legacy-run'
  AND JSON_UNQUOTE(JSON_EXTRACT(profile_snapshot_json,'$.reconstruction'))='partial';

CREATE OR REPLACE VIEW eval_profile_snapshot_migration_report AS
SELECT id AS eval_run_id, profile_id, profile_version, profile_config_hash,
       IF(JSON_UNQUOTE(JSON_EXTRACT(profile_snapshot_json,'$.reconstruction'))='partial','PARTIAL_LEGACY','COMPLETE') snapshot_status
FROM eval_run;
