-- Auto Memory v1.2: support bounded global cleanup of stale inferred observations.
-- The maintenance feature remains disabled by default and never targets ACTIVE or DISABLED rows.
USE ai_draw_io;

ALTER TABLE memory_item
    ADD INDEX idx_memory_item_aging (status, is_explicit, updated_at, memory_id);
