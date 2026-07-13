CREATE TABLE IF NOT EXISTS eval_case_evidence (
  id VARCHAR(64) PRIMARY KEY,
  working_copy_id VARCHAR(64) NOT NULL,
  working_copy_revision BIGINT NOT NULL,
  evidence_type VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  payload_json JSON NOT NULL,
  component_version VARCHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  KEY idx_eval_case_evidence_working (working_copy_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Immutable validation and dry-run evidence for Eval Case working copies';

CREATE TABLE IF NOT EXISTS eval_case_working_copy_review (
  id VARCHAR(64) PRIMARY KEY,
  working_copy_id VARCHAR(64) NOT NULL,
  working_copy_revision BIGINT NOT NULL,
  reviewer_user_id VARCHAR(128) NOT NULL,
  decision VARCHAR(16) NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  KEY idx_eval_case_review_working (working_copy_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Immutable human review decisions for Eval Case working copies';
