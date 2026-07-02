-- Drop the legacy duplicated version column after moving the version source to
-- diagram_canvas_state.version. Run once against existing databases only.
USE ai_draw_io;

ALTER TABLE diagram DROP COLUMN current_version;
