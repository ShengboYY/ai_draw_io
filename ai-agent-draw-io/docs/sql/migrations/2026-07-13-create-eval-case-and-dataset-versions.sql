CREATE TABLE IF NOT EXISTS eval_case_version (
  case_id VARCHAR(128) NOT NULL,
  case_version VARCHAR(64) NOT NULL,
  content_hash CHAR(64) NOT NULL,
  artifact_ref VARCHAR(512) NOT NULL,
  approved_by VARCHAR(128) NOT NULL,
  published_at DATETIME(3) NOT NULL,
  retired_at DATETIME(3),
  PRIMARY KEY (case_id, case_version),
  UNIQUE KEY uk_eval_case_content_hash (content_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Immutable metadata for published synthetic Eval Case artifacts';

CREATE TABLE IF NOT EXISTS eval_dataset (
  id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  dataset_class VARCHAR(32) NOT NULL,
  owner_user_id VARCHAR(128) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  KEY idx_eval_dataset_class (dataset_class, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS eval_dataset_version (
  dataset_id VARCHAR(64) NOT NULL,
  version VARCHAR(64) NOT NULL,
  dataset_class VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  content_hash CHAR(64),
  revision BIGINT NOT NULL,
  published_by VARCHAR(128),
  published_at DATETIME(3),
  PRIMARY KEY (dataset_id, version),
  KEY idx_eval_dataset_version_status (status, published_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS eval_dataset_member (
  dataset_id VARCHAR(64) NOT NULL,
  dataset_version VARCHAR(64) NOT NULL,
  case_id VARCHAR(128) NOT NULL,
  case_version VARCHAR(64) NOT NULL,
  PRIMARY KEY (dataset_id, dataset_version, case_id, case_version),
  KEY idx_eval_dataset_member_case (case_id, case_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
