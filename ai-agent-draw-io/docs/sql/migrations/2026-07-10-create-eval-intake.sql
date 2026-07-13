CREATE TABLE IF NOT EXISTS eval_case_candidate (
  id VARCHAR(64) PRIMARY KEY, source_run_id VARCHAR(128) NOT NULL, source_span_id VARCHAR(128), source_phase VARCHAR(64), source_agent_id VARCHAR(64),
  failure_family VARCHAR(64) NOT NULL, rule_id VARCHAR(64) NOT NULL, evidence_summary VARCHAR(1024) NOT NULL, risk VARCHAR(16) NOT NULL,
  discovered_at DATETIME(3) NOT NULL, policy_version VARCHAR(32) NOT NULL, status VARCHAR(32) NOT NULL, created_by VARCHAR(128) NOT NULL,
  detection_source VARCHAR(32) NOT NULL DEFAULT 'RULE_DETECTED', model_version VARCHAR(255),
  model_confidence DECIMAL(6,5), model_evidence_json JSON,
  UNIQUE KEY uk_eval_candidate_source_family (source_run_id, failure_family)
);
CREATE TABLE IF NOT EXISTS eval_case_review (
  id VARCHAR(64) PRIMARY KEY, candidate_id VARCHAR(64) NOT NULL, reviewer VARCHAR(128) NOT NULL, decision VARCHAR(16) NOT NULL, reason VARCHAR(1024), reviewed_at DATETIME(3) NOT NULL,
  KEY idx_eval_review_candidate (candidate_id)
);
CREATE TABLE IF NOT EXISTS eval_case_lineage (
  promotion_id VARCHAR(64) PRIMARY KEY, case_id VARCHAR(128) NOT NULL, dataset_version VARCHAR(64) NOT NULL, reviewer VARCHAR(128) NOT NULL,
  approved_at DATETIME(3) NOT NULL, sanitizer_version VARCHAR(64) NOT NULL, origin VARCHAR(64) NOT NULL
);
