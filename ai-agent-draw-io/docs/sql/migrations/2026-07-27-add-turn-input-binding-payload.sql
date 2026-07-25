-- M1 recovery payload for reconstructing the first pinned turn input after takeover.
-- The digest remains the comparison key; this bounded JSON is only the durable
-- reconstruction source for declarations that are not stored in the message row.

USE ai_draw_io;

SET @turn_input_binding_payload_count = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'turn_execution'
      AND COLUMN_NAME = 'turn_input_binding_json'
);
SET @add_turn_input_binding_payload_sql = IF(
    @turn_input_binding_payload_count = 0,
    'ALTER TABLE turn_execution ADD COLUMN turn_input_binding_json JSON NULL AFTER turn_input_binding_digest',
    'SET @turn_input_binding_payload_noop = 1'
);
PREPARE add_turn_input_binding_payload_stmt FROM @add_turn_input_binding_payload_sql;
EXECUTE add_turn_input_binding_payload_stmt;
DEALLOCATE PREPARE add_turn_input_binding_payload_stmt;
