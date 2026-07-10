ALTER TABLE agent_diagram_trace_snapshot
    ADD COLUMN changed_cell_count INT NULL
        COMMENT '相对前一画布发生新增、删除或属性变化的 cell 数' AFTER summary;
