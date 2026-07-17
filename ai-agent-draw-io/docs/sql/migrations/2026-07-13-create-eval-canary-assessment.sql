-- Match eval_run's charset and collation so the VARCHAR foreign key is compatible.
CREATE TABLE IF NOT EXISTS eval_canary_assessment (
  id VARCHAR(64) PRIMARY KEY,
  eval_run_id VARCHAR(64) NOT NULL,
  deployment_ref VARCHAR(180) NOT NULL,
  policy_version VARCHAR(120) NOT NULL,
  outcome VARCHAR(32) NOT NULL,
  reasons_json TEXT NOT NULL,
  baseline_requests INT NOT NULL,
  canary_requests INT NOT NULL,
  canary_failures INT NOT NULL,
  critical_findings INT NOT NULL,
  infrastructure_errors INT NOT NULL,
  p95_latency_ms DOUBLE NOT NULL,
  average_cost DOUBLE NOT NULL,
  created_by VARCHAR(64) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL,
  KEY idx_eval_canary_run_time (eval_run_id, created_at),
  CONSTRAINT fk_eval_canary_run FOREIGN KEY (eval_run_id) REFERENCES eval_run(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
