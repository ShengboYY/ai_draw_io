-- ============================================================================
-- Skill storage for the AI Draw.io agent.
--
-- Built-in skills ship inside the jar (classpath: agent/skills/*/SKILL.md).
-- This table holds the *dynamic* skills:
--   - PUBLIC  : platform-provided skills available to every user (owner_id = '')
--   - PRIVATE : a user's own skills (owner_id = <userId>)
-- The backend merges built-in + PUBLIC + the requesting user's PRIVATE skills,
-- and selects by description, so new skills work without code changes.
--
-- Run once against the target database (e.g. mounted into MySQL's
-- /docker-entrypoint-initdb.d/ for automatic first-boot initialization).
-- ============================================================================

CREATE DATABASE IF NOT EXISTS ai_draw_io
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE ai_draw_io;

CREATE TABLE IF NOT EXISTS skill (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    owner_id    VARCHAR(64)  NOT NULL DEFAULT ''      COMMENT '所有者用户ID;公共技能为空串',
    name        VARCHAR(128) NOT NULL                 COMMENT '技能名(对应 SKILL.md frontmatter 的 name)',
    description TEXT                                  COMMENT '技能描述,供路由按语义选择',
    category    VARCHAR(64)  NOT NULL DEFAULT 'drawio-design' COMMENT '类别;路由只选 drawio-design',
    body        MEDIUMTEXT                            COMMENT '技能正文(SKILL.md 去掉 frontmatter 的内容)',
    visibility  VARCHAR(16)  NOT NULL DEFAULT 'PRIVATE' COMMENT 'PUBLIC | PRIVATE',
    version     INT          NOT NULL DEFAULT 1        COMMENT '版本号,每次 upsert 自增',
    enabled     TINYINT(1)   NOT NULL DEFAULT 1        COMMENT '是否启用',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_owner_name (owner_id, name),
    KEY idx_visibility (visibility),
    KEY idx_owner (owner_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户/平台技能';

-- ----------------------------------------------------------------------------
-- Example: a platform-provided PUBLIC skill (owner_id = '').
-- The router will offer it to everyone when the request matches the description.
-- ----------------------------------------------------------------------------
-- INSERT INTO skill (owner_id, name, description, category, body, visibility)
-- VALUES ('', 'drawio-mindmap',
--         'Mind maps / 思维导图: radial idea trees, brainstorming, central topic with branches.',
--         'drawio-design',
--         '# Mind map rules\n- Root in the center; branches radiate outward.\n- Curved edges; color by branch.',
--         'PUBLIC')
-- ON DUPLICATE KEY UPDATE description = VALUES(description), body = VALUES(body);
