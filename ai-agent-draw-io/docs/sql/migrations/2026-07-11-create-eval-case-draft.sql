CREATE TABLE IF NOT EXISTS eval_case_draft (
  id VARCHAR(64) PRIMARY KEY,
  candidate_id VARCHAR(64) NOT NULL,
  draft_json JSON NOT NULL,
  sanitizer_version VARCHAR(64) NOT NULL,
  model_version VARCHAR(128) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  KEY idx_eval_draft_candidate_created (candidate_id, created_at)
);
