-- CP5 needs a stable denominator while an asynchronous Eval Run is in progress.
SET @eval_run_planned_episodes_column_count = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'eval_run'
      AND COLUMN_NAME = 'planned_episodes'
);

SET @add_eval_run_planned_episodes_sql = IF(
    @eval_run_planned_episodes_column_count = 0,
    'ALTER TABLE eval_run ADD COLUMN planned_episodes INT NOT NULL DEFAULT 0 AFTER repetitions',
    'SET @add_eval_run_planned_episodes_noop = 1'
);

PREPARE add_eval_run_planned_episodes_stmt FROM @add_eval_run_planned_episodes_sql;
EXECUTE add_eval_run_planned_episodes_stmt;
DEALLOCATE PREPARE add_eval_run_planned_episodes_stmt;
