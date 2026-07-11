-- Apply once when upgrading an existing eval intake schema created before the queue phase.
ALTER TABLE eval_case_candidate
  ADD INDEX idx_eval_candidate_queue (status, risk, discovered_at);
