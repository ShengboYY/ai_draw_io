-- Diagram Trace canvas snapshot metadata.
-- Stores version/hash/thumbnail evidence only; raw draw.io XML stays in diagram_canvas_state.

USE ai_draw_io;

CREATE TABLE IF NOT EXISTS agent_diagram_trace_snapshot (
    id            VARCHAR(64) NOT NULL COMMENT '主键; ads_<uuid>',
    run_id        VARCHAR(64) NOT NULL COMMENT 'agent_run.id',
    span_id       VARCHAR(64) NULL COMMENT '关联 span id;通常为 drawing step 或工具 span',
    diagram_id    VARCHAR(64) NOT NULL COMMENT 'diagram.id',
    version       BIGINT NULL COMMENT 'diagram_canvas_state.version',
    canvas_hash   VARCHAR(128) NULL COMMENT 'diagram_canvas_state.content_hash;不含 XML',
    thumbnail_url MEDIUMTEXT NULL COMMENT '缩略图 data URL 或 URL;可为空',
    summary       VARCHAR(512) NULL COMMENT '快照摘要/保存状态;不含 prompt/raw XML',
    created_at    DATETIME NOT NULL COMMENT '快照记录时间',
    PRIMARY KEY (id),
    KEY idx_agent_diagram_snapshot_run (run_id, created_at),
    KEY idx_agent_diagram_snapshot_span (span_id),
    KEY idx_agent_diagram_snapshot_diagram (diagram_id, version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Diagram Trace 画布快照元数据';
