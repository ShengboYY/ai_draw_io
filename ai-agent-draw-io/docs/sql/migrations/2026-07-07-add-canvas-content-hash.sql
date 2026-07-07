-- Adds nullable canonical content hash storage for canvas-state idempotency.
-- Existing rows are left NULL so the application can compute the canonical hash
-- with the same parser rules it uses for new saves.

USE ai_draw_io;

ALTER TABLE diagram_canvas_state
    ADD COLUMN content_hash VARCHAR(80) DEFAULT NULL COMMENT 'Canonical SHA-256 hash of latest canvas XML' AFTER current_xml;
