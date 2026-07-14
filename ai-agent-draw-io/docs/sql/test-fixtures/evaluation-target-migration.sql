DROP DATABASE IF EXISTS eval_target_migration_contract;
CREATE DATABASE eval_target_migration_contract CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE eval_target_migration_contract;

-- Minimal legacy schema needed to execute the real R2 migration in isolation.
CREATE TABLE eval_case_working_copy (
  id VARCHAR(64) PRIMARY KEY,
  case_id VARCHAR(128) NOT NULL,
  case_version VARCHAR(32) NOT NULL,
  definition_json JSON NOT NULL,
  status VARCHAR(32) NOT NULL
);
CREATE TABLE eval_case_version (
  case_id VARCHAR(128) NOT NULL,
  case_version VARCHAR(32) NOT NULL,
  artifact_ref VARCHAR(255) NULL,
  retired_at DATETIME NULL,
  PRIMARY KEY (case_id, case_version)
);
CREATE TABLE eval_dataset (
  id VARCHAR(64) PRIMARY KEY,
  dataset_class VARCHAR(16) NOT NULL,
  created_at DATETIME NOT NULL
);
CREATE TABLE eval_dataset_version (
  dataset_id VARCHAR(64) NOT NULL,
  version VARCHAR(32) NOT NULL,
  dataset_class VARCHAR(16) NOT NULL,
  PRIMARY KEY (dataset_id, version)
);
CREATE TABLE eval_dataset_member (
  dataset_id VARCHAR(64) NOT NULL,
  case_id VARCHAR(128) NOT NULL,
  case_version VARCHAR(32) NOT NULL
);
CREATE TABLE eval_run (
  id VARCHAR(64) PRIMARY KEY,
  dataset_id VARCHAR(64) NOT NULL,
  dataset_version VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL
);

INSERT INTO eval_case_working_copy (id, case_id, case_version, definition_json, status) VALUES
  ('ambiguous', 'ambiguous', '1', JSON_OBJECT('expected', JSON_OBJECT('routeType', 'edit_existing', 'graph', JSON_OBJECT())), 'DRAFT'),
  ('critical', 'critical', '1', JSON_OBJECT('expected', JSON_OBJECT('maxCriticalIssues', 0)), 'DRAFT'),
  ('explicit', 'explicit', '1', JSON_OBJECT('evaluationTarget', 'FULL_AGENT'), 'DRAFT'),
  ('fixture', 'fixture', '1', JSON_OBJECT('fixtureVersion', 'fixture-v1'), 'DRAFT'),
  ('major', 'major', '1', JSON_OBJECT('expected', JSON_OBJECT('maxMajorIssues', 1)), 'DRAFT'),
  ('quality', 'quality', '1', JSON_OBJECT('expected', JSON_OBJECT('graph', JSON_OBJECT())), 'PUBLISHED'),
  ('router', 'router', '1', JSON_OBJECT('expected', JSON_OBJECT('routeType', 'answer_only')), 'DRAFT');

INSERT INTO eval_case_version (case_id, case_version, artifact_ref) VALUES ('quality', '1', 'quality.yaml');
INSERT INTO eval_dataset (id, dataset_class, created_at) VALUES ('drawing-core', 'CORE', NOW());
INSERT INTO eval_dataset_version (dataset_id, version, dataset_class) VALUES ('drawing-core', 'v1', 'CORE');
INSERT INTO eval_dataset_member (dataset_id, case_id, case_version) VALUES ('drawing-core', 'quality', '1');
INSERT INTO eval_run (id, dataset_id, dataset_version, created_at) VALUES ('drawing-run', 'drawing-core', 'v1', NOW());
