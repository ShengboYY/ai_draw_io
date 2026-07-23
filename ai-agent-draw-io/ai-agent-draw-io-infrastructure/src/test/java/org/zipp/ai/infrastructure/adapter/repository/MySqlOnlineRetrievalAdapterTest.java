package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.retrieval.SourceMode;
import org.zipp.ai.domain.retrieval.port.AuthorizedCandidate;
import org.zipp.ai.domain.retrieval.port.AuthorizedSource;
import org.zipp.ai.domain.retrieval.port.AuthorizedSourceSet;
import org.zipp.ai.infrastructure.dao.retrieval.IOnlineRetrievalMapper;
import org.zipp.ai.infrastructure.dao.retrieval.po.OnlineCandidatePO;
import org.zipp.ai.infrastructure.dao.retrieval.po.OnlineSourcePO;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MySqlOnlineRetrievalAdapterTest {
    @Test
    void visualCandidateUsesExactCropArtifactInsteadOfRetrievalDescription() {
        OnlineCandidatePO row = new OnlineCandidatePO();
        row.setChunkId("chunk-visual");
        row.setEvidenceId("evidence-visual");
        row.setMaterialId("material-1");
        row.setVersionId("version-1");
        row.setRevisionId("revision-1");
        row.setModality("VISUAL");
        row.setPageNumber(2);
        row.setQualityScore(0.9);
        row.setRetrievalTextObjectKey("retrieval/description.txt");
        row.setRetrievalTextObjectVersionId("description-version");
        row.setRetrievalTextSha256("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        row.setRetrievalTextByteSize(64);
        row.setRetrievalTextContentType("text/plain");
        row.setVisualObjectKey("visual/crop.png");
        row.setVisualObjectVersionId("crop-version");
        row.setVisualContentSha256("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        row.setVisualByteSize(128);
        row.setVisualContentType("image/png");
        row.setSourceLabel("Workflow");
        MySqlOnlineRetrievalAdapter adapter = new MySqlOnlineRetrievalAdapter(new StubMapper(row));
        AuthorizedSource source = new AuthorizedSource("material-1", "version-1", "revision-1",
                MaterialScopeType.LIBRARY, MaterialScopeType.PERSONAL_LIBRARY_KEY,
                "READY", false, true, false, true);

        AuthorizedCandidate candidate = adapter.reauthorize(List.of("chunk-visual"),
                new AuthorizedSourceSet(new CatalogOwner(OwnerType.USER, "alice"),
                        SourceMode.EXPLICIT_ONLY, List.of(source)), 1).get(0);

        assertEquals("visual/crop.png", candidate.displayArtifact().objectKey());
        assertEquals("crop-version", candidate.displayArtifact().objectVersionId());
        assertEquals("image/png", candidate.displayArtifact().contentType());
    }

    private static final class StubMapper implements IOnlineRetrievalMapper {
        private final OnlineCandidatePO row;

        private StubMapper(OnlineCandidatePO row) {
            this.row = row;
        }

        @Override public List<OnlineCandidatePO> reauthorizeCandidates(
                String ownerType, String ownerKey, List<String> chunkIds,
                List<AuthorizedSource> sources, int limit) {
            return List.of(row);
        }

        @Override public List<OnlineSourcePO> selectExplicitSources(
                String ownerType, String ownerKey, String diagramId,
                String conversationId, List<String> versionIds) {
            return List.of();
        }
        @Override public List<OnlineSourcePO> selectConversationAttachmentSources(
                String ownerType, String ownerKey, String conversationId, List<String> uploadIds) {
            return List.of();
        }
        @Override public List<OnlineSourcePO> selectAutomaticSources(
                String ownerType, String ownerKey, String diagramId, String conversationId, int limit) {
            return List.of();
        }
        @Override public Integer countPendingConversationUploads(String ownerKey, String conversationId) {
            return 0;
        }
        @Override public List<OnlineCandidatePO> lexicalSearch(
                String ownerType, String ownerKey, String query, List<AuthorizedSource> sources,
                boolean includeText, boolean includeVisual, int limit) {
            return List.of();
        }
        @Override public List<OnlineCandidatePO> resolveVectorCandidates(
                String ownerType, String ownerKey, List<String> vectorIds,
                List<AuthorizedSource> sources) {
            return List.of();
        }
        @Override public List<OnlineCandidatePO> selectExistingTargetCandidates(
                String ownerType, String ownerKey, String diagramId, Long canvasVersion,
                List<String> cellIds, List<AuthorizedSource> sources, int limit) {
            return List.of();
        }
    }
}
