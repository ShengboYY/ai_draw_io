package org.zipp.ai.infrastructure.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialRagFoundationMigrationTest {

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
    void workerIamAllowsExactVersionVerificationAndCleanupInBothBuckets() throws Exception {
        Path template = Path.of("../deploy/aws/material-upload/s3-and-iam.template.yml");
        if (!Files.exists(template)) {
            template = Path.of("../../deploy/aws/material-upload/s3-and-iam.template.yml");
        }
        String yaml = Files.readString(template);

        assertEquals(2, occurrences(yaml, "s3:GetObjectVersion"));
        assertEquals(2, occurrences(yaml, "s3:DeleteObjectVersion"));
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
