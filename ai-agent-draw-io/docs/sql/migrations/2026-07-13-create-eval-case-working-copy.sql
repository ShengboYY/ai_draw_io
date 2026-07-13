CREATE TABLE IF NOT EXISTS eval_case_working_copy (
  id VARCHAR(64) PRIMARY KEY,
  case_id VARCHAR(128) NOT NULL,
  case_version VARCHAR(64) NOT NULL,
  source_type VARCHAR(32) NOT NULL,
  candidate_id VARCHAR(64),
  status VARCHAR(32) NOT NULL,
  owner_user_id VARCHAR(128) NOT NULL,
  revision BIGINT NOT NULL,
  definition_json JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  KEY idx_eval_working_status_updated (status, updated_at),
  KEY idx_eval_working_owner_updated (owner_user_id, updated_at),
  KEY idx_eval_working_candidate (candidate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Mutable synthetic Eval Case working copies; published artifacts are stored separately';
