-- CP6 persists existing live Judge/Gate values and the immutable statistical policy.
SET @eval_run_live_policy_column_count = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'eval_run' AND COLUMN_NAME = 'live_policy_json'
);
SET @add_eval_run_live_policy_sql = IF(
    @eval_run_live_policy_column_count = 0,
    'ALTER TABLE eval_run ADD COLUMN live_policy_json JSON NULL AFTER git_sha',
    'SET @add_eval_run_live_policy_noop = 1'
);
PREPARE add_eval_run_live_policy_stmt FROM @add_eval_run_live_policy_sql;
EXECUTE add_eval_run_live_policy_stmt;
DEALLOCATE PREPARE add_eval_run_live_policy_stmt;

CREATE TABLE IF NOT EXISTS eval_judge_result (
  episode_id VARCHAR(64) PRIMARY KEY,
  judge_version VARCHAR(512),
  calibration_version VARCHAR(128),
  status VARCHAR(32) NOT NULL,
  score_json JSON NOT NULL,
  evidence_json JSON NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS eval_gate_decision (
  eval_run_id VARCHAR(64) PRIMARY KEY,
  gate_version VARCHAR(128) NOT NULL,
  outcome VARCHAR(32) NOT NULL,
  reasons_json JSON NOT NULL,
  decided_at DATETIME(3) NOT NULL,
  override_approved BOOLEAN NOT NULL DEFAULT FALSE,
  override_reason VARCHAR(1000),
  overridden_by VARCHAR(128),
  overridden_at DATETIME(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
