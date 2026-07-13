-- Adds model provenance to the restricted Candidate queue without storing model inputs or raw trace payloads.
SET @candidate_detection_source_count = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'eval_case_candidate' AND COLUMN_NAME = 'detection_source'
);
SET @candidate_detection_source_sql = IF(
  @candidate_detection_source_count = 0,
  'ALTER TABLE eval_case_candidate ADD COLUMN detection_source VARCHAR(32) NOT NULL DEFAULT ''RULE_DETECTED'' AFTER created_by',
  'SET @candidate_detection_source_noop = 1'
);
PREPARE candidate_detection_source_stmt FROM @candidate_detection_source_sql;
EXECUTE candidate_detection_source_stmt;
DEALLOCATE PREPARE candidate_detection_source_stmt;

SET @candidate_model_version_count = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'eval_case_candidate' AND COLUMN_NAME = 'model_version'
);
SET @candidate_model_version_sql = IF(
  @candidate_model_version_count = 0,
  'ALTER TABLE eval_case_candidate ADD COLUMN model_version VARCHAR(255) NULL AFTER detection_source',
  'SET @candidate_model_version_noop = 1'
);
PREPARE candidate_model_version_stmt FROM @candidate_model_version_sql;
EXECUTE candidate_model_version_stmt;
DEALLOCATE PREPARE candidate_model_version_stmt;

SET @candidate_model_confidence_count = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'eval_case_candidate' AND COLUMN_NAME = 'model_confidence'
);
SET @candidate_model_confidence_sql = IF(
  @candidate_model_confidence_count = 0,
  'ALTER TABLE eval_case_candidate ADD COLUMN model_confidence DECIMAL(6,5) NULL AFTER model_version',
  'SET @candidate_model_confidence_noop = 1'
);
PREPARE candidate_model_confidence_stmt FROM @candidate_model_confidence_sql;
EXECUTE candidate_model_confidence_stmt;
DEALLOCATE PREPARE candidate_model_confidence_stmt;

SET @candidate_model_evidence_count = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'eval_case_candidate' AND COLUMN_NAME = 'model_evidence_json'
);
SET @candidate_model_evidence_sql = IF(
  @candidate_model_evidence_count = 0,
  'ALTER TABLE eval_case_candidate ADD COLUMN model_evidence_json JSON NULL AFTER model_confidence',
  'SET @candidate_model_evidence_noop = 1'
);
PREPARE candidate_model_evidence_stmt FROM @candidate_model_evidence_sql;
EXECUTE candidate_model_evidence_stmt;
DEALLOCATE PREPARE candidate_model_evidence_stmt;

CREATE TABLE IF NOT EXISTS eval_semantic_miner_run (
  id VARCHAR(64) PRIMARY KEY,
  status VARCHAR(32) NOT NULL,
  sampling_policy VARCHAR(16) NOT NULL,
  requested_limit INT NOT NULL,
  sampled_count INT NOT NULL DEFAULT 0,
  analyzed_count INT NOT NULL DEFAULT 0,
  candidate_count INT NOT NULL DEFAULT 0,
  error_count INT NOT NULL DEFAULT 0,
  estimated_cost_usd DECIMAL(12,6) NOT NULL DEFAULT 0,
  model_version VARCHAR(255) NOT NULL,
  sanitizer_version VARCHAR(64) NOT NULL,
  availability_reason VARCHAR(128),
  created_by VARCHAR(128) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  started_at DATETIME(3),
  completed_at DATETIME(3),
  KEY idx_semantic_miner_run_created (created_at, id),
  KEY idx_semantic_miner_run_status (status)
);
