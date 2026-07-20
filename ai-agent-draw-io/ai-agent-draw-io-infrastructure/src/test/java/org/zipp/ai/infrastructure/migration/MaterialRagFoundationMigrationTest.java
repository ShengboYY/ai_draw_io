package org.zipp.ai.infrastructure.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialRagFoundationMigrationTest {

    @Test
    void provenanceReferenceMigrationBackfillsAndIndexesOpaqueIdentity() throws Exception {
        Path migration = Path.of("docs/sql/migrations/2026-08-07-add-cell-provenance-reference.sql");
        if (!Files.exists(migration)) {
            migration = Path.of("../docs/sql/migrations/2026-08-07-add-cell-provenance-reference.sql");
        }
        String sql = Files.readString(migration);

        assertTrue(sql.contains("ADD COLUMN provenance_ref VARCHAR(64)"));
        assertTrue(sql.contains("MODIFY COLUMN provenance_ref VARCHAR(64) NOT NULL"));
        assertTrue(sql.contains("idx_cell_provenance_ref"));
    }

    @Test
    void migrationContainsEveryWp1FoundationTableAndCriticalInvariant() throws Exception {
        Path migration = Path.of("docs/sql/migrations/2026-07-19-create-material-rag-foundation.sql");
        if (!Files.exists(migration)) {
            migration = Path.of("../docs/sql/migrations/2026-07-19-create-material-rag-foundation.sql");
        }
        String sql = Files.readString(migration);

        List<String> tables = List.of(
                "material", "material_tag", "material_content_blob", "material_version",
                "material_upload_session", "material_scope_link", "chartbook", "diagram_source_pin",
                "conversation_source_context", "visual_processing_consent", "material_processing_revision",
                "material_page", "material_section", "evidence_unit", "evidence_region",
                "evidence_relation", "retrieval_chunk", "retrieval_chunk_evidence",
                "retrieval_search_document", "retrieval_exact_term", "retrieval_chunk_vector_projection",
                "rag_index_generation", "material_processing_job", "deletion_task", "evidence_read_lease",
                "grounded_run_control", "diagram_canvas_version", "source_citation", "citation_evidence",
                "citation_source_tombstone", "diagram_cell_provenance", "deleted_source_tombstone");
        for (String table : tables) {
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS " + table + " "), table);
        }

        assertTrue(sql.contains("CHECK ((upload_session_id IS NULL) <> (revision_id IS NULL))"));
        assertTrue(sql.contains("FULLTEXT KEY ft_word (word_search_text)"));
        assertTrue(sql.contains("FULLTEXT KEY ft_cjk (cjk_search_text) WITH PARSER ngram"));
        assertTrue(sql.contains("UNIQUE KEY uk_chunk_projection (retrieval_chunk_id, index_generation_id)"));
        assertTrue(sql.contains("INSERT IGNORE INTO diagram_canvas_version"));
        assertEquals(1, occurrences(jobTable(sql), "stage VARCHAR(48) NOT NULL"));
        assertTrue(sql.contains("fingerprint CHAR(64) NOT NULL"));
        assertTrue(sql.contains("lifecycle_state <> 'ACTIVE' OR expires_at IS NOT NULL"));
    }

    @Test
    void wp3MigrationPersistsUploadCorrelationsAndPinnedFormalObjectVersion() throws Exception {
        Path migration = Path.of("docs/sql/migrations/2026-07-21-create-materialization-promotion.sql");
        if (!Files.exists(migration)) {
            migration = Path.of("../docs/sql/migrations/2026-07-21-create-materialization-promotion.sql");
        }
        String sql = Files.readString(migration);

        assertTrue(sql.contains("content_blob_id VARCHAR(64)"));
        assertTrue(sql.contains("processing_revision_id VARCHAR(64)"));
        assertTrue(sql.contains("material_lifecycle_generation BIGINT"));
        assertTrue(sql.contains("original_object_version_id VARCHAR(255)"));
        assertTrue(sql.contains("promoted_at DATETIME(3)"));
    }

    @Test
    void wp3bMigrationPinsEveryPageArtifactToAnExactObjectVersion() throws Exception {
        Path migration = Path.of("docs/sql/migrations/2026-07-22-create-document-processing-artifacts.sql");
        if (!Files.exists(migration)) {
            migration = Path.of("../docs/sql/migrations/2026-07-22-create-document-processing-artifacts.sql");
        }
        String sql = Files.readString(migration);

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS material_page_artifact"));
        assertTrue(sql.contains("object_version_id VARCHAR(255) NOT NULL"));
        assertTrue(sql.contains("content_sha256 CHAR(64) NOT NULL"));
        assertTrue(sql.contains("UNIQUE KEY uk_material_page_artifact_kind"));
        assertTrue(sql.contains("UNIQUE KEY uk_material_page_artifact_object"));
    }

    @Test
    void wp3cMigrationPinsTheWholeDocumentStructureArtifact() throws Exception {
        Path migration = Path.of("docs/sql/migrations/2026-07-23-create-document-structure-artifacts.sql");
        if (!Files.exists(migration)) {
            migration = Path.of("../docs/sql/migrations/2026-07-23-create-document-structure-artifacts.sql");
        }
        String sql = Files.readString(migration);

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS material_revision_artifact"));
        assertTrue(sql.contains("object_version_id VARCHAR(255) NOT NULL"));
        assertTrue(sql.contains("content_sha256 CHAR(64) NOT NULL"));
        assertTrue(sql.contains("UNIQUE KEY uk_material_revision_artifact_kind"));
        assertTrue(sql.contains("UNIQUE KEY uk_material_revision_artifact_object"));
    }

    @Test
    void documentSectionIdentityIsScopedToItsProcessingRevision() throws Exception {
        Path migration = Path.of("docs/sql/migrations/2026-07-24-scope-document-section-identity.sql");
        if (!Files.exists(migration)) {
            migration = Path.of("../docs/sql/migrations/2026-07-24-scope-document-section-identity.sql");
        }
        String sql = Files.readString(migration);

        assertTrue(sql.contains("DROP PRIMARY KEY"));
        assertTrue(sql.contains("PRIMARY KEY (revision_id, id)"));
    }

    @Test
    void visualCropMigrationPinsEveryCandidateToAnExactObjectVersion() throws Exception {
        Path migration = Path.of("docs/sql/migrations/2026-07-25-create-visual-crop-artifacts.sql");
        if (!Files.exists(migration)) {
            migration = Path.of("../docs/sql/migrations/2026-07-25-create-visual-crop-artifacts.sql");
        }
        String sql = Files.readString(migration);

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS material_visual_artifact"));
        assertTrue(sql.contains("object_version_id VARCHAR(255) NOT NULL"));
        assertTrue(sql.contains("PRIMARY KEY (revision_id, candidate_id)"));
        assertTrue(sql.contains("UNIQUE KEY uk_material_visual_artifact_object"));
    }

    @Test
    void evidenceMigrationPinsTextVisualAndAnalysisObjectVersions() throws Exception {
        Path migration = Path.of("docs/sql/migrations/2026-07-26-pin-evidence-artifact-versions.sql");
        if (!Files.exists(migration)) {
            migration = Path.of("../docs/sql/migrations/2026-07-26-pin-evidence-artifact-versions.sql");
        }
        String sql = Files.readString(migration);

        assertTrue(sql.contains("display_text_object_version_id VARCHAR(255)"));
        assertTrue(sql.contains("visual_object_version_id VARCHAR(255)"));
        assertTrue(sql.contains("visual_analysis_object_version_id VARCHAR(255)"));
        assertTrue(sql.contains("chk_evidence_display_pin"));
        assertTrue(sql.contains("chk_evidence_visual_pin"));
    }

    @Test
    void vectorProjectionMigrationPinsGenerationBatchesAndManifestArtifacts() throws Exception {
        Path migration = Path.of("docs/sql/migrations/2026-07-28-create-vector-projection-artifacts.sql");
        if (!Files.exists(migration)) {
            migration = Path.of("../docs/sql/migrations/2026-07-28-create-vector-projection-artifacts.sql");
        }
        String sql = Files.readString(migration);

        assertTrue(sql.contains("embedding_model_fingerprint CHAR(64) NOT NULL"));
        assertTrue(sql.contains("tokenizer_fingerprint VARCHAR(255) NOT NULL"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS retrieval_revision_vector_projection"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS retrieval_vector_batch"));
        assertTrue(sql.contains("chk_vector_batch_artifact_pin"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS retrieval_projection_manifest"));
        assertTrue(sql.contains("object_identity_hash CHAR(64) GENERATED ALWAYS"));
        assertTrue(sql.contains("UNIQUE KEY uk_projection_manifest_object (object_identity_hash)"));
    }

    @Test
    void publicationMigrationEnforcesOneActiveIndexGeneration() throws Exception {
        Path migration = Path.of("docs/sql/migrations/2026-07-29-enforce-index-generation-publication.sql");
        if (!Files.exists(migration)) {
            migration = Path.of("../docs/sql/migrations/2026-07-29-enforce-index-generation-publication.sql");
        }
        String sql = Files.readString(migration);

        assertTrue(sql.contains("CASE WHEN state = 'ACTIVE' THEN 1 ELSE NULL END"));
        assertTrue(sql.contains("UNIQUE KEY uk_rag_one_active_generation (active_slot)"));
    }

    @Test
    void processingUsageMigrationRecordsOneChargePerContentBlob() throws Exception {
        Path migration = Path.of("docs/sql/migrations/2026-07-30-create-material-processing-usage.sql");
        if (!Files.exists(migration)) {
            migration = Path.of("../docs/sql/migrations/2026-07-30-create-material-processing-usage.sql");
        }
        String sql = Files.readString(migration);

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS material_processing_usage"));
        assertTrue(sql.contains("PRIMARY KEY (content_blob_id)"));
        assertTrue(sql.contains("first_revision_id VARCHAR(64) NOT NULL"));
    }

    @Test
    void gapManifestMigrationRequiresAnExactObjectPin() throws Exception {
        Path migration = Path.of("docs/sql/migrations/2026-07-31-pin-revision-gap-manifest.sql");
        if (!Files.exists(migration)) {
            migration = Path.of("../docs/sql/migrations/2026-07-31-pin-revision-gap-manifest.sql");
        }
        String sql = Files.readString(migration);

        assertTrue(sql.contains("gap_manifest_object_version_id VARCHAR(255)"));
        assertTrue(sql.contains("gap_manifest_sha256 CHAR(64)"));
        assertTrue(sql.contains("chk_revision_gap_manifest_pin"));
    }

    @Test
    void compatibilityMigrationPinsTargetsShadowReportsAndRollbackMetadata() throws Exception {
        Path migration = Path.of("docs/sql/migrations/2026-08-01-create-index-generation-compatibility.sql");
        if (!Files.exists(migration)) {
            migration = Path.of("../docs/sql/migrations/2026-08-01-create-index-generation-compatibility.sql");
        }
        String sql = Files.readString(migration);

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS rag_index_generation_target"));
        assertTrue(sql.contains("PRIMARY KEY (index_generation_id, revision_id)"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS rag_index_shadow_report"));
        assertTrue(sql.contains("target_generation BIGINT NOT NULL"));
        assertTrue(sql.contains("rollback_until DATETIME(3)"));
        assertTrue(sql.contains("activation_report_id VARCHAR(64)"));
        assertTrue(sql.contains("previous_generation_id VARCHAR(64)"));
    }

    @Test
    void projectionMaintenanceMigrationKeepsAuditsAndProviderDeletionTombstones() throws Exception {
        Path migration = Path.of("docs/sql/migrations/2026-08-02-create-index-projection-maintenance.sql");
        if (!Files.exists(migration)) {
            migration = Path.of("../docs/sql/migrations/2026-08-02-create-index-projection-maintenance.sql");
        }
        String sql = Files.readString(migration);

        assertTrue(sql.contains("purged_at DATETIME(3)"));
        assertTrue(sql.contains("provider_deleted_at DATETIME(3)"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS rag_projection_repair_audit"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS rag_projection_reconciliation_cursor"));
        assertTrue(sql.contains("pagination_token VARCHAR(2048)"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS rag_projection_orphan_deletion_audit"));
        assertTrue(sql.contains("requested_at DATETIME(3) NOT NULL"));
        assertTrue(sql.contains("completed_at DATETIME(3) NULL"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS rag_generation_target_repair_audit"));
        assertTrue(sql.contains("uk_projection_repair_open"));
    }

    @Test
    void materialCatalogMigrationMakesChartbookCreationOwnerIdempotent() throws Exception {
        Path migration = Path.of("docs/sql/migrations/2026-08-03-create-material-catalog-api.sql");
        if (!Files.exists(migration)) {
            migration = Path.of("../docs/sql/migrations/2026-08-03-create-material-catalog-api.sql");
        }
        String sql = Files.readString(migration);

        assertTrue(sql.contains("create_idempotency_key VARCHAR(128)"));
        assertTrue(sql.contains("UNIQUE KEY uk_chartbook_owner_idempotency"));
    }

    @Test
    void previewReprocessMigrationSeparatesRequestAndWorkerProfileIdentity() throws Exception {
        Path migration = Path.of("docs/sql/migrations/2026-08-04-create-material-preview-reprocess.sql");
        if (!Files.exists(migration)) {
            migration = Path.of("../docs/sql/migrations/2026-08-04-create-material-preview-reprocess.sql");
        }
        String sql = Files.readString(migration);

        assertTrue(sql.contains("worker_profile_fingerprint CHAR(64)"));
        assertTrue(sql.contains("SET worker_profile_fingerprint = fingerprint"));
        assertTrue(sql.contains("SET fingerprint = SHA2"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS material_reprocess_request"));
        assertTrue(sql.contains("PRIMARY KEY (owner_key, material_id, request_fingerprint)"));
    }

    @Test
    void lifecycleMigrationAddsDurableFencesLeasesAndDeletionProof() throws Exception {
        Path migration = Path.of("docs/sql/migrations/2026-08-05-create-material-lifecycle-operations.sql");
        if (!Files.exists(migration)) {
            migration = Path.of("../docs/sql/migrations/2026-08-05-create-material-lifecycle-operations.sql");
        }
        String sql = Files.readString(migration);

        assertTrue(sql.contains("uk_evidence_lease_run_source"));
        assertTrue(sql.contains("lifecycle_generation BIGINT"));
        assertTrue(sql.contains("uk_deletion_task_material"));
        assertTrue(sql.contains("CREATE TABLE material_lifecycle_request"));
        assertTrue(sql.contains("CREATE TABLE material_deletion_proof"));
        assertTrue(sql.contains("lifecycle_state = 'DELETED'"));
    }

    @Test
    void onlineRetrievalMigrationAddsCompleteImmutableHydrationIdentity() throws Exception {
        Path migration = Path.of("docs/sql/migrations/2026-08-06-add-online-retrieval-artifact-identity.sql");
        if (!Files.exists(migration)) {
            migration = Path.of("../docs/sql/migrations/2026-08-06-add-online-retrieval-artifact-identity.sql");
        }
        String sql = Files.readString(migration);

        assertTrue(sql.contains("retrieval_text_byte_size BIGINT NULL"));
        assertTrue(sql.contains("retrieval_text_content_type VARCHAR(128) NULL"));
        assertTrue(sql.contains("Existing chunks stay ineligible"));
    }

    @Test
    void workerIamAllowsExactVersionVerificationAndCleanupInBothBuckets() throws Exception {
        Path template = Path.of("../deploy/aws/material-upload/s3-and-iam.template.yml");
        if (!Files.exists(template)) {
            template = Path.of("../../deploy/aws/material-upload/s3-and-iam.template.yml");
        }
        String yaml = Files.readString(template);

        assertEquals(2, occurrences(yaml, "s3:GetObjectVersion"));
        assertEquals(2, occurrences(yaml, "s3:DeleteObjectVersion"));
    }

    @Test
    void queueRoutesRevisionAndPromotionJobsOnlyToMatchingProcessingProfiles() throws Exception {
        Path mapper = Path.of("src/main/resources/mybatis/mapper/material_processing_job_mapper.xml");
        String xml = Files.readString(mapper);

        assertTrue(xml.contains("r.worker_profile_fingerprint = #{processingFingerprint}"));
        assertTrue(xml.contains("stage != 'PROMOTE_ORIGINAL'"));
        assertTrue(xml.contains("u.processing_revision_id"));
        assertTrue(xml.contains("projectionGenerationId"));
        assertTrue(xml.contains("work_key = CONCAT('ig:', #{projectionGenerationId})"));
        assertTrue(xml.contains("LIKE CONCAT('ig:', #{projectionGenerationId}, ':%')"));
        assertTrue(xml.contains("BUILD_COMPATIBILITY_PROJECTION"));
        assertTrue(xml.contains("REPAIR_VECTOR_BATCH"));
        assertTrue(xml.contains("rv.projection_role = 'COMPATIBILITY'"));
        assertTrue(xml.contains("material_processing_job.stage IN ('EMBED_CHUNK_BATCHES', 'UPSERT_VECTOR_BATCHES',"));

        Path maintenanceMapper = Path.of(
                "src/main/resources/mybatis/mapper/index_projection_maintenance_mapper.xml");
        String maintenanceXml = Files.readString(maintenanceMapper);
        assertTrue(maintenanceXml.contains("job.work_key = CONCAT('ig:', #{generationId})"));
        assertTrue(maintenanceXml.contains("LIKE CONCAT('ig:', #{generationId}, ':%')"));
    }

    private String jobTable(String sql) {
        int start = sql.indexOf("CREATE TABLE IF NOT EXISTS material_processing_job");
        int end = sql.indexOf("CREATE TABLE IF NOT EXISTS deletion_task", start);
        return sql.substring(start, end);
    }

    private int occurrences(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }
}
