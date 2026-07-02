-- ============================================================================
-- User account + one-time token tables for the AI Draw.io agent.
--
-- Introduced with issue #2 (register + verify email). Login/session upgrades
-- landing in later issues (#3+) extend this schema but do not replace it.
--
-- Passwords are stored only as adaptive hashes; verification / reset tokens
-- are stored only as token hashes. The raw values never touch the database.
-- ============================================================================

CREATE DATABASE IF NOT EXISTS ai_draw_io
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE ai_draw_io;

CREATE TABLE IF NOT EXISTS app_user (
    id                VARCHAR(64)  NOT NULL COMMENT '用户ID; usr_<uuid>',
    email             VARCHAR(320) NOT NULL COMMENT '注册时的原始邮箱(保留大小写)',
    email_normalized  VARCHAR(320) NOT NULL COMMENT '规范化邮箱(trim + lowercase),用于唯一性判断',
    password_hash     VARCHAR(255) NOT NULL COMMENT '密码哈希;永不存储明文',
    status            VARCHAR(24)  NOT NULL DEFAULT 'PENDING_VERIFICATION'
        COMMENT 'PENDING_VERIFICATION | ACTIVE | DISABLED | DELETED',
    session_version   INT          NOT NULL DEFAULT 0 COMMENT '密码/会话失效版本号',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    verified_at       DATETIME     NULL COMMENT '邮箱验证通过时间',
    deleted_at        DATETIME     NULL COMMENT '软删除时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_email_normalized (email_normalized),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户账户';

CREATE TABLE IF NOT EXISTS account_token (
    id           VARCHAR(64)  NOT NULL COMMENT '主键; atk_<uuid>',
    user_id      VARCHAR(64)  NOT NULL COMMENT '所属用户ID',
    purpose      VARCHAR(24)  NOT NULL COMMENT 'EMAIL_VERIFY | PASSWORD_RESET',
    token_hash   VARCHAR(128) NOT NULL COMMENT '一次性令牌的哈希;原始令牌只出现在邮件链接中',
    expires_at   DATETIME     NOT NULL COMMENT '过期时间;超过即使 used_at 为空也视为失效',
    used_at      DATETIME     NULL     COMMENT '被消费时间;一次性、消费后不可再用',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_token_hash_purpose (token_hash, purpose),
    KEY idx_user_purpose (user_id, purpose),
    KEY idx_expires (expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '账户一次性令牌(邮箱验证/密码重置)';

CREATE TABLE IF NOT EXISTS model_credential (
    id                    VARCHAR(64)   NOT NULL COMMENT '主键; mcr_<uuid>',
    user_id               VARCHAR(64)   NOT NULL COMMENT '所属已验证用户ID',
    provider              VARCHAR(64)   NOT NULL COMMENT '模型服务商标识,如 openai',
    base_url              VARCHAR(512)  NOT NULL COMMENT 'HTTPS 公网 API base URL',
    model                 VARCHAR(128)  NOT NULL COMMENT '模型名称',
    completion_path       VARCHAR(255)  NOT NULL COMMENT 'completion/chat completion 路径',
    display_name          VARCHAR(128)  NOT NULL COMMENT '用户侧显示名称',
    encrypted_api_key     TEXT          NOT NULL COMMENT 'AES-GCM 密文;永不存储明文 API key',
    encryption_provider   VARCHAR(32)   NOT NULL COMMENT 'ENV_AES_GCM,未来可扩展为 KMS',
    encryption_key_id     VARCHAR(128)  NOT NULL COMMENT '加密 key 标识,用于未来轮换/迁移',
    encryption_nonce      VARCHAR(128)  NOT NULL COMMENT 'AES-GCM nonce(Base64)',
    key_last_four         VARCHAR(8)    NOT NULL COMMENT 'API key 后四位,仅用于 masked 展示',
    status                VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE | DISABLED',
    created_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    disabled_at           DATETIME      NULL COMMENT '禁用时间',
    deleted_at            DATETIME      NULL COMMENT '软删除时间',
    PRIMARY KEY (id),
    KEY idx_user_status (user_id, status, deleted_at),
    KEY idx_user_provider_model (user_id, provider, model)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户模型凭据(加密存储)';
