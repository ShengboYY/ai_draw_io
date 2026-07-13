-- R7: keep the Draft backlink unique, then transfer it to a restricted link at publication.
DROP PROCEDURE IF EXISTS migrate_r7_working_candidate_unique;
DELIMITER //
CREATE PROCEDURE migrate_r7_working_candidate_unique()
BEGIN
  -- Fail with an actionable message before ALTER emits an opaque duplicate-key error.
  IF EXISTS (
    SELECT candidate_id FROM eval_case_working_copy
    WHERE candidate_id IS NOT NULL GROUP BY candidate_id HAVING COUNT(*) > 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT='duplicate Working Copy candidate_id must be resolved before R7';
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='eval_case_working_copy'
      AND index_name='uk_eval_working_candidate'
  ) THEN
    ALTER TABLE eval_case_working_copy
      ADD UNIQUE KEY uk_eval_working_candidate (candidate_id);
  END IF;
END//
DELIMITER ;
CALL migrate_r7_working_candidate_unique();
DROP PROCEDURE migrate_r7_working_candidate_unique;

CREATE TABLE IF NOT EXISTS eval_candidate_promotion_link (
  candidate_id VARCHAR(64) PRIMARY KEY,
  working_copy_id VARCHAR(64) NOT NULL,
  case_id VARCHAR(128) NOT NULL,
  case_version VARCHAR(64) NOT NULL,
  promoted_at DATETIME(3) NOT NULL,
  retention_expires_at DATETIME(3) NULL,
  UNIQUE KEY uk_eval_promotion_working_copy (working_copy_id),
  KEY idx_eval_promotion_retention (retention_expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Restricted Trace-domain lineage; never exported with Eval artifacts';
