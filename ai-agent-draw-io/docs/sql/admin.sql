-- ============================================================================
-- Minimal admin audit log.
--
-- Stores metadata only: no request bodies, prompts, responses, Draw.io XML, raw
-- API keys, or encrypted key material.
-- ============================================================================

CREATE DATABASE IF NOT EXISTS ai_draw_io
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE ai_draw_io;

CREATE TABLE IF NOT EXISTS admin_audit_log (
    id            VARCHAR(64)  NOT NULL COMMENT '主键; aal_<uuid>',
    actor_user_id VARCHAR(64)  NOT NULL COMMENT '管理员用户 ID',
    action        VARCHAR(64)  NOT NULL COMMENT 'LIST_USERS | DISABLE_USER | VIEW_USAGE | VIEW_RUN | VIEW_AUDIT_LOGS',
    target_type   VARCHAR(32)  NULL COMMENT 'USER | RUN | AUDIT_LOG | USAGE',
    target_id     VARCHAR(128) NULL COMMENT '目标 ID; 不存请求体',
    outcome       VARCHAR(24)  NOT NULL COMMENT 'SUCCESS | NOT_FOUND | REJECTED',
    ip_address    VARCHAR(64)  NULL COMMENT 'Servlet remote address',
    user_agent    VARCHAR(256) NULL COMMENT '截断后的 User-Agent',
    created_at    DATETIME     NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_admin_audit_created (created_at),
    KEY idx_admin_audit_actor_created (actor_user_id, created_at),
    KEY idx_admin_audit_target (target_type, target_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '管理员操作审计日志';
