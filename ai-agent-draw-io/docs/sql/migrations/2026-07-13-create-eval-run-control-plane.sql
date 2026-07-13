CREATE TABLE IF NOT EXISTS eval_run (
  id VARCHAR(64) PRIMARY KEY,
  mode VARCHAR(16) NOT NULL,
  dataset_id VARCHAR(64) NOT NULL,
  dataset_version VARCHAR(64) NOT NULL,
  baseline_ref VARCHAR(128),
  candidate_ref VARCHAR(128),
  execution_profile_hash VARCHAR(128),
  idempotency_key VARCHAR(128) NOT NULL,
  repetitions INT NOT NULL,
  git_sha VARCHAR(128) NOT NULL,
  grader_manifest_json JSON NOT NULL,
  report_ref VARCHAR(512),
  status VARCHAR(32) NOT NULL,
  created_by VARCHAR(128) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  started_at DATETIME(3),
  completed_at DATETIME(3),
  UNIQUE KEY uk_eval_run_idempotency (idempotency_key),
  KEY idx_eval_run_created (created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS eval_episode (
  id VARCHAR(64) PRIMARY KEY,
  eval_run_id VARCHAR(64) NOT NULL,
  case_id VARCHAR(128) NOT NULL,
  case_version VARCHAR(64) NOT NULL,
  repetition INT NOT NULL,
  attempt INT NOT NULL,
  status VARCHAR(32) NOT NULL,
  trace_ref VARCHAR(512),
  artifact_refs_json JSON NOT NULL,
  latency_ms BIGINT NOT NULL,
  input_tokens BIGINT NOT NULL,
  output_tokens BIGINT NOT NULL,
  estimated_cost DECIMAL(18,8) NOT NULL,
  error_class VARCHAR(256),
  error_message VARCHAR(1000),
  UNIQUE KEY uk_eval_episode_coordinate (eval_run_id, case_id, case_version, repetition),
  KEY idx_eval_episode_run_status (eval_run_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS eval_grader_result (
  episode_id VARCHAR(64) NOT NULL,
  grader_name VARCHAR(128) NOT NULL,
  grader_version VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,
  severity VARCHAR(32) NOT NULL,
  score DECIMAL(10,6),
  evidence_json JSON NOT NULL,
  PRIMARY KEY (episode_id, grader_name, grader_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
