-- Persistent, bounded orchestration for deterministic/LLM/VLM Trace Analysis.
CREATE TABLE IF NOT EXISTS trace_analysis_job (
  id VARCHAR(64) PRIMARY KEY,
  scope VARCHAR(32) NOT NULL,
  analyzer_type VARCHAR(32) NOT NULL,
  analyzer_version VARCHAR(255) NOT NULL,
  analyzer_config_hash CHAR(64) NOT NULL,
  sample_definition_json JSON NULL,
  trace_snapshot_at DATETIME(3) NOT NULL,
  idempotency_key CHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  total_items INT NOT NULL DEFAULT 0,
  succeeded_items INT NOT NULL DEFAULT 0,
  failed_items INT NOT NULL DEFAULT 0,
  reserved_cost DECIMAL(12,6) NOT NULL DEFAULT 0,
  actual_cost DECIMAL(12,6) NOT NULL DEFAULT 0,
  created_by VARCHAR(128) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  started_at DATETIME(3) NULL,
  completed_at DATETIME(3) NULL,
  UNIQUE KEY uk_trace_analysis_job_idempotency (idempotency_key),
  KEY idx_trace_analysis_job_created (created_at, id),
  KEY idx_trace_analysis_job_status (status)
);

CREATE TABLE IF NOT EXISTS trace_analysis_item (
  id VARCHAR(64) PRIMARY KEY,
  job_id VARCHAR(64) NOT NULL,
  source_run_id VARCHAR(128) NOT NULL,
  analyzer_type VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  outcome_status VARCHAR(32) NULL,
  attempt INT NOT NULL DEFAULT 0,
  candidate_id VARCHAR(64) NULL,
  latency_ms BIGINT NULL,
  estimated_cost DECIMAL(12,6) NOT NULL DEFAULT 0,
  error_class VARCHAR(128) NULL,
  error_message VARCHAR(512) NULL,
  UNIQUE KEY uk_trace_analysis_item (job_id, source_run_id, analyzer_type),
  KEY idx_trace_analysis_item_job_status (job_id, status),
  KEY idx_trace_analysis_item_candidate (candidate_id)
);
