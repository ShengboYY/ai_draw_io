CREATE TABLE IF NOT EXISTS eval_case_health (
  case_id VARCHAR(128) PRIMARY KEY,
  case_version VARCHAR(64) NOT NULL,
  baseline_reproduced BOOLEAN,
  health_status VARCHAR(32) NOT NULL,
  summary VARCHAR(512) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  KEY idx_eval_case_health_status (health_status, updated_at)
);
