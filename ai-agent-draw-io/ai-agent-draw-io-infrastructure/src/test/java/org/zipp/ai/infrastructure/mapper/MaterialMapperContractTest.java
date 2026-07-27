package org.zipp.ai.infrastructure.mapper;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialMapperContractTest {

    @Test
    void catalogScopeReadsFenceExpiryAndProjectIndependentSearchReadiness() throws Exception {
        String mapper = resource("mybatis/mapper/material_catalog_mapper.xml");

        assertTrue(mapper.contains("m.expires_at &gt; UTC_TIMESTAMP(3)"));
        assertTrue(mapper.contains("search_projection.state = 'READY'"));
        assertTrue(mapper.contains("search_generation.state = 'ACTIVE'"));
        assertTrue(mapper.contains("<update id=\"restoreTemporaryWhenConversationOnly\">"));
        assertTrue(mapper.contains("<update id=\"trashOwnedMaterial\">"));
        assertTrue(mapper.contains("INTERVAL 24 HOUR"));
        assertTrue(mapper.contains("scope_type IN ('LIBRARY', 'DIAGRAM', 'CHARTBOOK')"));
    }

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
        assertTrue(mapper.contains("durable_index_eligible"));
        assertTrue(mapper.contains("durable_scope.scope_type IN ('LIBRARY', 'DIAGRAM', 'CHARTBOOK')"));
        assertTrue(mapper.contains("existing_projection.expected_projection_count = 0"));
        assertTrue(mapper.contains("<update id=\"upgradeLexicalOnlyPrimaryProjection\""));
        assertTrue(mapper.contains("expected_projection_count = 0 AND state = 'READY'"));
        assertTrue(mapper.contains("<update id=\"replaceLexicalOnlyManifest\""));
        assertTrue(mapper.contains("g.state IN ('BUILDING', 'SHADOW', 'ACTIVE')"));
        assertTrue(mapper.contains("in_flight.stage IN ('INDEXING', 'PUBLISHING')"));
        assertTrue(mapper.contains("<select id=\"selectPendingGenerationPublications\""));
        assertTrue(mapper.contains("rv.projection_role = 'COMPATIBILITY'"));
        assertTrue(mapper.contains("<select id=\"selectGenerationBackfillStatus\""));
        assertTrue(mapper.contains("<insert id=\"insertShadowReport\""));
        assertTrue(mapper.contains("<update id=\"activateShadowGeneration\""));
        assertTrue(mapper.contains("pending_delete.provider_delete_completed_at IS NULL"));
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

    @Test
    void lifecycleAndDeletionMappersFenceOwnershipGenerationAndExactExternalIds() throws Exception {
        String lifecycle = resource("mybatis/mapper/material_lifecycle_mapper.xml");
        String leases = resource("mybatis/mapper/material_read_lease_mapper.xml");
        String deletion = resource("mybatis/mapper/material_deletion_mapper.xml");

        assertTrue(lifecycle.contains("lifecycle_generation = #{expectedGeneration}"));
        assertTrue(lifecycle.contains("<select id=\"countPendingVectorCleanup\""));
        assertTrue(lifecycle.contains("projection.provider_delete_completed_at IS NULL"));
        assertTrue(lifecycle.contains("job.status = 'CANCELLED'"));
        assertTrue(leases.contains("FOR UPDATE"));
        assertTrue(leases.contains("m.expires_at &gt; #{now}"));
        assertTrue(deletion.contains("FOR UPDATE SKIP LOCKED"));
        assertTrue(deletion.contains("task.lifecycle_generation = m.lifecycle_generation"));
        assertTrue(deletion.contains("projection.vector_id"));
        assertTrue(deletion.contains("projection.provider_deleted_at IS NULL"));
        assertTrue(deletion.contains("markVectorScopeDeleted"));
        assertTrue(deletion.contains("original_object_version_id"));
        assertTrue(deletion.contains("upload.quarantine_object_version_id"));
        assertTrue(deletion.contains("owner_key = SHA2"));
        assertFalse(deletion.contains("deleteAll"));
        assertFalse(deletion.contains("metadata"));
    }

    @Test
    void projectionMaintenanceDeletesOnlyUnleasedConversationOnlyTemporaryVectors() throws Exception {
        String mapper = resource("mybatis/mapper/index_projection_maintenance_mapper.xml");
        String cursor = statement(mapper, "update", "advanceTemporaryCleanupCursor");
        String candidate = statement(mapper, "select", "selectTemporaryCleanupMaterial");
        String vectors = statement(mapper, "select", "selectTemporaryCleanupVectorIds");
        String lock = statement(mapper, "select", "lockExpiredTemporaryCleanupMaterial");
        String claim = statement(mapper, "update", "claimTemporaryConversationVectorsForDeletion");
        String pendingGeneration = statement(mapper, "select", "selectPendingProviderDeletionGeneration");
        String retries = statement(mapper, "select", "selectTemporaryProviderDeletionRetries");
        String completion = statement(mapper, "update", "completeTemporaryProviderDeletion");

        assertTrue(cursor.contains("material_cursor = #{expectedMaterialId}"));
        assertTrue(candidate.contains("m.id &gt; #{afterMaterialId}"));
        assertTrue(candidate.contains("m.lifecycle_state = 'TRASHED'"));
        assertTrue(candidate.contains("m.retention_class = 'TEMPORARY'"));
        assertTrue(candidate.contains("conversation_scope.scope_type = 'CONVERSATION'"));
        assertTrue(candidate.contains("durable_scope.scope_type IN ('LIBRARY', 'DIAGRAM', 'CHARTBOOK')"));
        assertTrue(candidate.contains("FROM evidence_read_lease lease"));
        assertTrue(vectors.contains("m.expires_at &lt;= #{now}"));
        assertTrue(vectors.contains("p.index_generation_id = #{generationId}"));
        assertTrue(vectors.contains("p.provider_deleted_at IS NULL"));
        assertTrue(lock.contains("m.lifecycle_state = 'TRASHED'"));
        assertTrue(lock.contains("FOR UPDATE"));
        assertTrue(claim.contains("m.expires_at &lt;= #{deletedAt}"));
        assertTrue(claim.contains("conversation_scope.scope_type = 'CONVERSATION'"));
        assertTrue(claim.contains("durable_scope.scope_type IN ('LIBRARY', 'DIAGRAM', 'CHARTBOOK')"));
        assertTrue(claim.contains("FROM evidence_read_lease lease"));
        assertTrue(claim.contains("lease.status = 'ACTIVE'"));
        assertTrue(pendingGeneration.contains("g.state != 'PURGED'"));
        assertTrue(pendingGeneration.contains("pending.provider_delete_completed_at IS NULL"));
        assertTrue(retries.contains("provider_delete_completed_at IS NULL"));
        assertTrue(completion.contains("provider_delete_completed_at = #{completedAt}"));
        assertTrue(completion.contains("index_generation_id = #{generationId}"));
        assertTrue(completion.contains("collection=\"vectorIds\""));
        String retiredIds = statement(mapper, "select", "selectRetiredVectorIds");
        String retiredComplete = statement(mapper, "update", "completeRetiredGeneration");
        String retiredGeneration = statement(mapper, "select", "selectRetiredCleanupGeneration");
        assertTrue(retiredGeneration.contains("state IN ('RETIRED', 'PURGING')"));
        assertTrue(retiredGeneration.contains("FOR UPDATE SKIP LOCKED"));
        assertTrue(retiredIds.contains("provider_delete_completed_at IS NULL"));
        assertTrue(retiredComplete.contains("provider_delete_completed_at IS NULL"));
    }

    @Test
    void onlineRetrievalReauthorizesOwnerRevisionAndExactArtifactBeforeHydration() throws Exception {
        String mapper = resource("mybatis/mapper/online_retrieval_mapper.xml");

        assertTrue(mapper.contains("doc.owner_type = #{ownerType}"));
        assertTrue(mapper.contains("doc.owner_key = #{ownerKey}"));
        // Existing diagrams may keep a ready, exact older revision after a newer revision becomes active.
        assertTrue(mapper.contains("doc.version_id = #{source.versionId} AND doc.revision_id = #{source.revisionId}"));
        assertFalse(mapper.contains("mv.active_revision_id = mr.id"));
        assertTrue(mapper.contains("material.lifecycle_state = 'ACTIVE'"));
        assertTrue(mapper.contains("chunk.retrieval_text_byte_size IS NOT NULL"));
        assertTrue(mapper.contains("chunk.retrieval_text_content_type IS NOT NULL"));
        assertTrue(mapper.contains("MATCH(doc.word_search_text)"));
        assertTrue(mapper.contains("MATCH(doc.cjk_search_text)"));
        assertTrue(mapper.contains("retrieval_exact_term"));
        assertTrue(mapper.contains("projection.vector_id IN"));
        assertTrue(mapper.contains("COALESCE(v.active_revision_id"));
        assertTrue(mapper.contains("ORDER BY pending.revision_no DESC LIMIT 1"));
        assertTrue(mapper.contains("link.scope_key = #{conversationId}"));
        assertTrue(mapper.contains("link.scope_key = d.chartbook_id"));
        assertTrue(mapper.contains("mr.excluded_pages_json"));
        assertFalse(mapper.contains("visual_object_key AS source_label"));
    }

    @Test
    void automaticSourcesAreOwnerFencedAndCoverConversationDiagramAndChartbookOnly() throws Exception {
        String onlineMapper = resource("mybatis/mapper/online_retrieval_mapper.xml");
        String snapshotMapper = resource("mybatis/mapper/request_source_snapshot_mapper.xml");

        int automaticStart = onlineMapper.indexOf("<select id=\"selectAutomaticSources\"");
        int automaticEnd = onlineMapper.indexOf("</select>", automaticStart);
        String automatic = onlineMapper.substring(automaticStart, automaticEnd);
        assertTrue(automatic.contains("link.scope_type = 'CONVERSATION'"));
        assertTrue(automatic.contains("link.scope_key = #{conversationId}"));
        assertTrue(automatic.contains("link.scope_type = 'DIAGRAM'"));
        assertTrue(automatic.contains("link.scope_key = #{diagramId}"));
        assertTrue(automatic.contains("link.scope_type = 'CHARTBOOK'"));
        assertTrue(automatic.contains("link.scope_key = d.chartbook_id"));
        assertFalse(automatic.contains("link.scope_type = 'LIBRARY'"));
        assertTrue(automatic.contains("<include refid=\"activeOwnerMaterial\"/>"));
        assertTrue(onlineMapper.contains("m.owner_type = #{ownerType}"));
        assertTrue(onlineMapper.contains("m.owner_key = #{ownerKey}"));
        assertTrue(onlineMapper.contains("m.expires_at &gt; UTC_TIMESTAMP(3)"));
        // Direct availability follows the immutable page image, not a vector or retrieval chunk.
        assertTrue(automatic.contains("<include refid=\"hasVisualArtifact\"/>"));
        assertTrue(onlineMapper.contains("<sql id=\"hasVisualArtifact\">"));
        assertTrue(onlineMapper.contains("visual_page.artifact_kind = 'PAGE_IMAGE'"));
        assertFalse(automatic.contains("c.modality = 'VISUAL'"));

        // Run snapshots are append-once and may only be replayed by their original owner.
        assertTrue(snapshotMapper.contains("INSERT IGNORE INTO request_source_snapshot"));
        assertTrue(snapshotMapper.contains("#{source.displayName}"));
        assertTrue(snapshotMapper.contains("item.display_name"));
        assertTrue(snapshotMapper.contains("#{source.countsAsProcessingSource}"));
        assertTrue(snapshotMapper.contains("item.counts_as_processing_source"));
        assertTrue(snapshotMapper.contains("snapshot.owner_type = #{owner.ownerType}"));
        assertTrue(snapshotMapper.contains("snapshot.owner_key = #{owner.ownerKey}"));
        assertFalse(snapshotMapper.contains("UPDATE request_source_snapshot"));
    }

    @Test
    void groundedCanvasMappersFenceOwnershipAndKeepCitationWritesInsideOneCommitBoundary() throws Exception {
        String commit = resource("mybatis/mapper/grounded_canvas_commit_mapper.xml");
        String query = resource("mybatis/mapper/citation_query_mapper.xml");
        String manual = resource("mybatis/mapper/manual_provenance_mapper.xml");
        String run = resource("mybatis/mapper/grounded_run_control_mapper.xml");

        assertTrue(commit.contains("FOR UPDATE"));
        assertTrue(commit.contains("resultType=\"org.zipp.ai.infrastructure.dao.grounding.GroundedRunRowPO\""));
        assertTrue(commit.contains("selectPersistedProvenance"));
        assertTrue(commit.contains("provenance.provenance_ref"));
        assertTrue(commit.contains("diagram.user_id = state.user_id"));
        assertTrue(commit.contains("state.version = #{plan.expectedVersion}"));
        assertTrue(commit.contains("JOIN material_version version ON version.id = unit.version_id"));
        assertTrue(commit.contains("copyInheritedProvenance"));
        assertTrue(commit.contains("provenance.canvas_version = #{canvasVersion}"));
        assertTrue(commit.contains("citation_key, use_role, source_origin"));
        assertTrue(commit.contains("#{link.useRole}, #{link.origin}"));
        assertTrue(commit.contains("generation = #{plan.expectedRunGeneration}"));
        assertTrue(query.contains("diagram.user_id = #{ownerKey}"));
        assertTrue(query.contains("material.owner_key = #{ownerKey}"));
        assertTrue(query.contains("provenance.current_citation_id"));
        assertTrue(query.contains("COALESCE(evidence.source_origin, unit.source_channel)"));
        assertTrue(query.contains("SOURCE_UNAVAILABLE"));
        assertTrue(query.contains("evidence.revision_id"));
        assertTrue(query.contains("region.bbox_json"));
        assertTrue(query.contains("display_text_object_version_id"));
        assertTrue(manual.contains("previous.support_type"));
        assertTrue(manual.contains("#{provenance.supportType}, #{provenance.currentCitationId}"));
        assertTrue(manual.contains("selectOwnedProvenance"));
        assertTrue(manual.contains("upsertImportedPins"));
        assertTrue(manual.contains("citation.diagram_id = anchor.diagram_id"));
        assertTrue(manual.contains("diagram.user_id = #{ownerKey}"));
        assertTrue(run.contains("generation = generation + 1"));
        assertTrue(run.contains("generation = #{identity.generation}"));
    }

    @Test
    void operationsDashboardUsesOnlyBoundedAggregatesAndUtcCutoffs() throws Exception {
        String mapper = resource("mybatis/mapper/material_operations_mapper.xml");

        assertTrue(mapper.contains("COUNT(*) FROM material_processing_job"));
        assertTrue(mapper.contains("SUM(page_count)"));
        assertTrue(mapper.contains("#{last24Hours}"));
        assertTrue(mapper.contains("#{monthStart}"));
        assertTrue(mapper.contains("lifecycle_state IN ('DELETE_PENDING', 'DELETING')"));
        assertTrue(mapper.contains("rag_projection_repair_audit"));
        assertTrue(mapper.contains("rag_projection_orphan_deletion_audit"));
        assertTrue(mapper.contains("last_reconciled_at &lt; #{reconciliationStaleBefore}"));
        assertTrue(mapper.contains("VALUES(sequence_no) &gt; sequence_no"));
        assertTrue(mapper.contains("sequence_no = GREATEST(sequence_no, VALUES(sequence_no))"));
        assertFalse(mapper.contains("owner_key"));
        assertFalse(mapper.contains("display_name"));
        assertFalse(mapper.contains("display_text"));
    }

    private String resource(String path) throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String statement(String mapper, String tag, String id) {
        String opening = "<" + tag + " id=\"" + id + "\"";
        int start = mapper.indexOf(opening);
        assertTrue(start >= 0, opening);
        int end = mapper.indexOf("</" + tag + ">", start);
        assertTrue(end > start, id);
        return mapper.substring(start, end);
    }
}
