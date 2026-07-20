package org.zipp.ai.infrastructure.mapper;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialMapperContractTest {

    @Test
    void materialMapperAlwaysScopesReadsAndTtlWritesByOwnerAndGeneration() throws Exception {
        String mapper = resource("mybatis/mapper/material_foundation_mapper.xml");

        assertTrue(mapper.contains("owner_type = #{ownerType}"));
        assertTrue(mapper.contains("owner_key = #{ownerKey}"));
        assertTrue(mapper.contains("lifecycle_generation = #{expectedGeneration}"));
        assertTrue(mapper.contains("expires_at &gt; UTC_TIMESTAMP(3)"));
    }

    @Test
    void processingQueueMapperClaimsInAShortLockedTransactionAndFencesEveryWrite() throws Exception {
        String mapper = resource("mybatis/mapper/material_processing_job_mapper.xml");

        assertTrue(mapper.contains("FOR UPDATE SKIP LOCKED"));
        assertTrue(mapper.contains("fence_token = fence_token + 1"));
        assertTrue(mapper.contains("lease_owner = #{workerId}"));
        assertTrue(mapper.contains("fence_token = #{fenceToken}"));
        assertTrue(mapper.contains("lease_until &gt; UTC_TIMESTAMP(3)"));
        assertTrue(mapper.contains("collection=\"stages\""));
    }

    @Test
    void uploadMapperScopesBrowserReadsAndFencesEveryWorkerCommit() throws Exception {
        String mapper = resource("mybatis/mapper/material_upload_session_mapper.xml");

        assertTrue(mapper.contains("owner_type = #{ownerType}"));
        assertTrue(mapper.contains("owner_key = #{ownerKey}"));
        assertTrue(mapper.contains("state = 'CREATED' AND generation = #{expectedGeneration}"));
        assertTrue(mapper.contains("state = 'CREATED' AND policy_expires_at &gt; UTC_TIMESTAMP(3)"));
        assertTrue(mapper.contains("j.lease_owner = #{workerId}"));
        assertTrue(mapper.contains("j.fence_token = #{fenceToken}"));
        assertTrue(mapper.contains("j.lease_until &gt; UTC_TIMESTAMP(3)"));
    }

    @Test
    void materializationMapperLocksOwnedContentAndFencesVisibilityTransitions() throws Exception {
        String mapper = resource("mybatis/mapper/material_materialization_mapper.xml");

        assertTrue(mapper.contains("FOR UPDATE"));
        assertTrue(mapper.contains("owner_type = #{ownerType}"));
        assertTrue(mapper.contains("owner_key = #{ownerKey}"));
        assertTrue(mapper.contains("content_sha256 = #{contentSha256}"));
        assertTrue(mapper.contains("lifecycle_state = 'ACTIVE'"));
        assertTrue(mapper.contains("security_status = 'VALIDATED'"));
        assertTrue(mapper.contains("j.fence_token = #{fenceToken}"));
        assertTrue(mapper.contains("j.lease_until &gt; UTC_TIMESTAMP(3)"));
        assertTrue(mapper.contains("j.upload_session_id = u.id"));
        assertTrue(mapper.contains("j.stage = 'PROMOTE_ORIGINAL'"));
        assertTrue(mapper.contains("m.lifecycle_generation = u.material_lifecycle_generation"));
        assertTrue(mapper.contains("m.expires_at &gt; UTC_TIMESTAMP(3)"));
        assertTrue(mapper.contains("r.state = 'PROCESSING'"));
        assertTrue(mapper.contains("b.status = 'PROMOTING'"));
        assertTrue(mapper.contains("b.original_object_version_id = #{objectVersionId}"));
        assertTrue(mapper.contains("status = 'AVAILABLE'"));
    }

    @Test
    void documentVisualWorkReadsExactPinsOnlyUnderTheCurrentFence() throws Exception {
        String mapper = resource("mybatis/mapper/material_document_processing_mapper.xml");

        assertTrue(mapper.contains("<select id=\"selectVisualWork\""));
        assertTrue(mapper.contains("structure.object_version_id AS structure_version_id"));
        assertTrue(mapper.contains("image.object_version_id AS page_image_version_id"));
        assertTrue(mapper.contains("j.stage = 'ANALYZE_VISUALS'"));
        assertTrue(mapper.contains("j.lease_owner = #{workerId}"));
        assertTrue(mapper.contains("j.fence_token = #{fenceToken}"));
        assertTrue(mapper.contains("j.lease_until &gt; UTC_TIMESTAMP(3)"));
    }

    @Test
    void evidenceWorkReadsEveryExactPinOnlyUnderTheCurrentFence() throws Exception {
        String mapper = resource("mybatis/mapper/material_document_processing_mapper.xml");

        assertTrue(mapper.contains("<select id=\"selectEvidenceWork\""));
        assertTrue(mapper.contains("visual.object_version_id AS visual_manifest_version_id"));
        assertTrue(mapper.contains("canonical.object_version_id AS canonical_version_id"));
        assertTrue(mapper.contains("j.stage = 'BUILD_EVIDENCE_UNITS'"));
        assertTrue(mapper.contains("display_text_object_version_id"));
        assertTrue(mapper.contains("visual_object_version_id"));
    }

    @Test
    void vectorProjectionWorkUsesExactPinsFencesAndOneShotCoordinatorProgress() throws Exception {
        String mapper = resource("mybatis/mapper/material_vector_projection_mapper.xml");

        assertTrue(mapper.contains("r.progress = 93"));
        assertTrue(mapper.contains("manifest.object_version_id AS retrieval_manifest_version_id"));
        assertTrue(mapper.contains("b.vector_object_version_id"));
        assertTrue(mapper.contains("j.lease_owner = #{workerId}"));
        assertTrue(mapper.contains("j.fence_token = #{fenceToken}"));
        assertTrue(mapper.contains("j.lease_until &gt; UTC_TIMESTAMP(3)"));
        assertTrue(mapper.contains("pending_projection.state != 'INDEXED'"));
        assertTrue(mapper.contains("<select id=\"selectPublicationWork\""));
        assertTrue(mapper.contains("pm.object_version_id AS projection_manifest_version_id"));
        assertTrue(mapper.contains("j.stage = 'PUBLISH_REVISION'"));
        assertTrue(mapper.contains("<sql id=\"primaryVersionProcessable\">"));
        assertTrue(mapper.contains("v.active_revision_id != r.id"));
        assertTrue(mapper.contains("<update id=\"publishRevision\""));
        assertTrue(mapper.contains("active_revision_id = #{revisionId}"));
        assertTrue(mapper.contains("ingest_state = 'READY' OR ingest_state = 'PARTIAL'"));
        assertTrue(mapper.contains("SET state = #{state}, progress = 100"));
        assertTrue(mapper.contains("<insert id=\"recordInitialProcessingUsage\""));
        assertTrue(mapper.contains("<select id=\"selectCompatibilityCoordinatorWork\""));
        assertTrue(mapper.contains("target.state = 'PENDING'"));
        assertTrue(mapper.contains("in_flight.stage IN ('INDEXING', 'PUBLISHING')"));
        assertTrue(mapper.contains("<select id=\"selectPendingGenerationPublications\""));
        assertTrue(mapper.contains("rv.projection_role = 'COMPATIBILITY'"));
        assertTrue(mapper.contains("<select id=\"selectGenerationBackfillStatus\""));
        assertTrue(mapper.contains("<insert id=\"insertShadowReport\""));
        assertTrue(mapper.contains("<update id=\"activateShadowGeneration\""));
        assertTrue(mapper.contains("<update id=\"restoreRetiredGeneration\""));
    }

    @Test
    void catalogAndChartbookMutationsRemainOwnerFencedAndNonDestructive() throws Exception {
        String materials = resource("mybatis/mapper/material_catalog_mapper.xml");
        String chartbooks = resource("mybatis/mapper/chartbook_catalog_mapper.xml");

        assertTrue(materials.contains("m.owner_type = #{ownerType}"));
        assertTrue(materials.contains("m.owner_key = #{ownerKey}"));
        assertTrue(materials.contains("FOR UPDATE"));
        assertTrue(materials.contains("countOwnedScopes"));
        assertTrue(materials.contains("c.status = 'ACTIVE'"));
        assertTrue(materials.contains("library_scope.scope_key IN ('personal', 'library', #{ownerKey})"));
        assertTrue(materials.contains("MAX(latest_r.revision_no)"));
        assertTrue(chartbooks.contains("c.owner_key = #{ownerKey}"));
        assertTrue(chartbooks.contains("d.user_id = #{ownerKey}"));
        assertTrue(chartbooks.contains("SET d.chartbook_id = c.id"));
        assertTrue(chartbooks.contains("SET chartbook_id = NULL"));
    }

    @Test
    void previewAndReprocessUseOwnerFencesExactPinsAndReplacementRevisionEligibility() throws Exception {
        String preview = resource("mybatis/mapper/material_preview_mapper.xml");
        String processing = resource("mybatis/mapper/material_document_processing_mapper.xml");

        assertTrue(preview.contains("m.owner_type = #{ownerType}"));
        assertTrue(preview.contains("m.owner_key = #{ownerKey}"));
        assertTrue(preview.contains("image.object_version_id"));
        assertTrue(preview.contains("image.artifact_kind = 'PAGE_IMAGE'"));
        assertTrue(preview.contains("request.request_fingerprint = #{requestFingerprint}"));
        assertTrue(preview.contains("r.fingerprint = #{processingFingerprint}"));
        assertTrue(preview.contains("FOR UPDATE"));
        assertTrue(processing.contains("r.excluded_pages_json"));
        assertTrue(processing.contains("v.active_revision_id != r.id"));
        assertTrue(processing.contains("COALESCE(v.page_count, #{pageCount})"));
    }

    private String resource(String path) throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
