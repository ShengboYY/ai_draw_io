-- Manage encrypted user model credentials (GitHub issue #8).
-- The raw API key is never persisted; reads should expose masked display values only.

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
