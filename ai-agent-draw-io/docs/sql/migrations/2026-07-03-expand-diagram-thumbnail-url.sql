USE ai_draw_io;

ALTER TABLE diagram
    MODIFY COLUMN thumbnail_url MEDIUMTEXT COMMENT 'Homepage thumbnail data URL';
