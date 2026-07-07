package org.zipp.ai.test.infrastructure;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasStateSaveResult;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasStateSaveStatus;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasStateVersionConflictException;
import org.zipp.ai.infrastructure.adapter.repository.CanvasStateRepository;
import org.zipp.ai.infrastructure.dao.ICanvasStateMapper;
import org.zipp.ai.infrastructure.dao.po.CanvasStatePO;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CanvasStateRepositoryTest {

    @Test
    public void shouldSaveCurrentCanvasStateAndReturnLatestRow() throws Exception {
        CanvasStateRepository repository = new CanvasStateRepository();
        FakeCanvasStateMapper mapper = new FakeCanvasStateMapper();
        mapper.canvasStateExists = false;
        injectMapper(repository, mapper);

        CanvasStateSaveResult result = repository.saveWithResult(CanvasState.builder()
                .userId("alice")
                .diagramId("diagram-1")
                .diagramType("architecture")
                .currentXml("<mxGraphModel/>")
                .summary("1 node")
                .analysisJson("{\"valid\":true}")
                .build());
        CanvasState saved = result.getState();

        assertEquals(CanvasStateSaveStatus.CREATED, result.getStatus());
        assertEquals("alice", mapper.saved.getUserId());
        assertEquals("diagram-1", mapper.saved.getDiagramId());
        assertEquals("<mxGraphModel/>", mapper.saved.getCurrentXml());
        assertTrue(mapper.saved.getContentHash() != null && !mapper.saved.getContentHash().isBlank());
        assertTrue(mapper.insertCanvasStateCalled);
        assertEquals(Long.valueOf(4L), saved.getVersion());
        assertEquals("persisted", saved.getSummary());
    }

    @Test
    public void shouldUpdateCanvasStateWithExpectedVersion() throws Exception {
        CanvasStateRepository repository = new CanvasStateRepository();
        FakeCanvasStateMapper mapper = new FakeCanvasStateMapper();
        injectMapper(repository, mapper);

        CanvasStateSaveResult result = repository.saveWithResult(CanvasState.builder()
                .userId("alice")
                .diagramId("diagram-1")
                .currentXml("<mxGraphModel/>")
                .version(3L)
                .build());
        CanvasState saved = result.getState();

        assertEquals(CanvasStateSaveStatus.UPDATED, result.getStatus());
        assertTrue(mapper.updateByVersionCalled);
        assertEquals(Long.valueOf(3L), mapper.saved.getVersion());
        assertEquals(Long.valueOf(4L), saved.getVersion());
    }

    @Test(expected = CanvasStateVersionConflictException.class)
    public void shouldRejectStaleExpectedVersion() throws Exception {
        CanvasStateRepository repository = new CanvasStateRepository();
        FakeCanvasStateMapper mapper = new FakeCanvasStateMapper();
        mapper.updateRows = 0;
        mapper.currentXml = "<mxGraphModel><root><mxCell id=\"0\"/></root></mxGraphModel>";
        injectMapper(repository, mapper);

        repository.save(CanvasState.builder()
                .userId("alice")
                .diagramId("diagram-1")
                .currentXml("<mxGraphModel/>")
                .version(3L)
                .build());
    }

    @Test
    public void shouldTreatStaleExpectedVersionAsNoopWhenCanonicalCanvasHashAlreadyMatches() throws Exception {
        CanvasStateRepository repository = new CanvasStateRepository();
        FakeCanvasStateMapper mapper = new FakeCanvasStateMapper();
        mapper.updateRows = 0;
        mapper.currentXml = "<mxGraphModel><root><mxCell id=\"2\" value=\"Order\" vertex=\"1\" parent=\"1\"/></root></mxGraphModel>";
        injectMapper(repository, mapper);

        CanvasStateSaveResult result = repository.saveWithResult(CanvasState.builder()
                .userId("alice")
                .diagramId("diagram-1")
                .currentXml("<mxGraphModel>\n  <root>\n    <mxCell parent=\"1\" vertex=\"1\" value=\"Order\" id=\"2\"></mxCell>\n  </root>\n</mxGraphModel>")
                .version(3L)
                .build());
        CanvasState saved = result.getState();

        assertEquals(CanvasStateSaveStatus.NOOP, result.getStatus());
        assertTrue(mapper.updateByVersionCalled);
        assertFalse(mapper.insertCanvasStateCalled);
        assertEquals(Long.valueOf(4L), saved.getVersion());
        assertEquals(mapper.currentXml, saved.getCurrentXml());
        assertEquals(saved.getContentHash(), mapper.saved.getContentHash());
    }

    @Test(expected = CanvasStateVersionConflictException.class)
    public void shouldRejectNullVersionSaveWhenCanvasStateAlreadyExists() throws Exception {
        CanvasStateRepository repository = new CanvasStateRepository();
        FakeCanvasStateMapper mapper = new FakeCanvasStateMapper();
        mapper.canvasStateExists = true;
        injectMapper(repository, mapper);

        try {
            repository.save(CanvasState.builder()
                    .userId("alice")
                    .diagramId("diagram-1")
                    .currentXml("<mxGraphModel/>")
                    .build());
        } finally {
            assertFalse(mapper.insertCanvasStateCalled);
        }
    }

    @Test(expected = CanvasStateVersionConflictException.class)
    public void shouldRejectNullVersionSaveWhenConcurrentCreateAlreadyInsertedCanvasState() throws Exception {
        CanvasStateRepository repository = new CanvasStateRepository();
        FakeCanvasStateMapper mapper = new FakeCanvasStateMapper();
        mapper.canvasStateExists = false;
        mapper.insertCanvasStateRows = 0;
        injectMapper(repository, mapper);

        repository.save(CanvasState.builder()
                .userId("alice")
                .diagramId("diagram-1")
                .currentXml("<mxGraphModel/>")
                .build());
    }

    @Test
    public void shouldUseCanvasStateVersionAsDiagramListVersionSource() throws Exception {
        String mapperXml = new String(getClass()
                .getResourceAsStream("/mybatis/mapper/canvas_state_mapper.xml")
                .readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");

        assertTrue(mapperXml.contains("c.version AS version"));
        assertFalse(mapperXml.contains("d.current_version AS version"));
        assertFalse(mapperXml.contains("syncDiagramVersion"));
    }

    @Test
    public void shouldFindCanvasStateByOwnerAndDiagram() throws Exception {
        CanvasStateRepository repository = new CanvasStateRepository();
        FakeCanvasStateMapper mapper = new FakeCanvasStateMapper();
        injectMapper(repository, mapper);

        assertTrue(repository.find("alice", "diagram-1").isPresent());
        assertEquals("alice", mapper.selectedUserId);
        assertEquals("diagram-1", mapper.selectedDiagramId);
    }

    @Test
    public void shouldListDiagramsByOwner() throws Exception {
        CanvasStateRepository repository = new CanvasStateRepository();
        FakeCanvasStateMapper mapper = new FakeCanvasStateMapper();
        injectMapper(repository, mapper);

        List<CanvasState> diagrams = repository.list("alice");

        assertEquals("alice", mapper.listedUserId);
        assertEquals(2, diagrams.size());
        assertEquals("diagram-1", diagrams.get(0).getDiagramId());
        assertEquals("Checkout Flow", diagrams.get(0).getTitle());
        assertEquals(Long.valueOf(4L), diagrams.get(0).getVersion());
        assertEquals("data:image/png;base64,diagram1", diagrams.get(0).getThumbnailUrl());
    }

    @Test
    public void shouldReturnEmptyDiagramListForBlankOwner() throws Exception {
        CanvasStateRepository repository = new CanvasStateRepository();
        FakeCanvasStateMapper mapper = new FakeCanvasStateMapper();
        injectMapper(repository, mapper);

        List<CanvasState> diagrams = repository.list(" ");

        assertTrue(diagrams.isEmpty());
        assertFalse(mapper.listCalled);
    }

    @Test
    public void shouldRenameDiagramTitle() throws Exception {
        CanvasStateRepository repository = new CanvasStateRepository();
        FakeCanvasStateMapper mapper = new FakeCanvasStateMapper();
        injectMapper(repository, mapper);

        CanvasState renamed = repository.rename("alice", "diagram-1", "  New Checkout Flow  ").orElseThrow();

        assertEquals("alice", mapper.renamedUserId);
        assertEquals("diagram-1", mapper.renamedDiagramId);
        assertEquals("New Checkout Flow", mapper.renamedTitle);
        assertEquals("New Checkout Flow", renamed.getTitle());
    }

    @Test
    public void shouldUseDefaultTitleWhenRenameTitleIsBlank() throws Exception {
        CanvasStateRepository repository = new CanvasStateRepository();
        FakeCanvasStateMapper mapper = new FakeCanvasStateMapper();
        injectMapper(repository, mapper);

        repository.rename("alice", "diagram-1", " ");

        assertEquals("Untitled Diagram", mapper.renamedTitle);
    }

    @Test
    public void shouldUpdateDiagramThumbnailUrl() throws Exception {
        CanvasStateRepository repository = new CanvasStateRepository();
        FakeCanvasStateMapper mapper = new FakeCanvasStateMapper();
        injectMapper(repository, mapper);

        CanvasState updated = repository.updateThumbnail(
                "alice",
                "diagram-1",
                "data:image/png;base64,thumb").orElseThrow();

        assertEquals("alice", mapper.thumbnailUserId);
        assertEquals("diagram-1", mapper.thumbnailDiagramId);
        assertEquals("data:image/png;base64,thumb", mapper.thumbnailUrl);
        assertEquals("data:image/png;base64,thumb", updated.getThumbnailUrl());
    }

    @Test
    public void shouldSoftDeleteDiagram() throws Exception {
        CanvasStateRepository repository = new CanvasStateRepository();
        FakeCanvasStateMapper mapper = new FakeCanvasStateMapper();
        injectMapper(repository, mapper);

        assertTrue(repository.softDelete("alice", "diagram-1"));
        assertEquals("alice", mapper.deletedUserId);
        assertEquals("diagram-1", mapper.deletedDiagramId);
    }

    @Test
    public void shouldReturnFalseWhenSoftDeleteInputIsBlank() throws Exception {
        CanvasStateRepository repository = new CanvasStateRepository();
        FakeCanvasStateMapper mapper = new FakeCanvasStateMapper();
        injectMapper(repository, mapper);

        assertFalse(repository.softDelete(" ", "diagram-1"));
        assertFalse(mapper.deleteCalled);
    }

    @Test
    public void shouldImportAnonymousWorkspaceWithSafeIdsAndSoftDeleteSource() throws Exception {
        CanvasStateRepository repository = new CanvasStateRepository();
        FakeCanvasStateMapper mapper = new FakeCanvasStateMapper();
        mapper.existingDiagramIds.add("diagram-collision");
        injectMapper(repository, mapper);
        setImportIdSupplier(repository, new Supplier<>() {
            private int callCount;

            @Override
            public String get() {
                return callCount++ == 0 ? "diagram-collision" : "diagram-safe";
            }
        });

        List<CanvasState> imported = repository.importAnonymousWorkspace(
                "anon_123e4567-e89b-42d3-a456-426614174000",
                "usr_alice");

        assertEquals(List.of("diagram-collision", "diagram-safe"), mapper.checkedDiagramIds);
        assertEquals("anon_123e4567-e89b-42d3-a456-426614174000", mapper.importSourceUserId);
        assertEquals("diagram-1", mapper.importSourceDiagramId);
        assertEquals("usr_alice", mapper.importTargetUserId);
        assertEquals("diagram-safe", mapper.importTargetDiagramId);
        assertEquals("diagram-safe", mapper.copiedCanvasTargetDiagramId);
        assertEquals("diagram-safe", mapper.copiedMessagesTargetDiagramId);
        assertEquals("diagram-1", mapper.deletedDiagramId);
        assertEquals(1, imported.size());
        assertEquals("diagram-safe", imported.get(0).getDiagramId());
        assertEquals("usr_alice", imported.get(0).getUserId());
    }

    @Test
    public void shouldRejectAnonymousWorkspaceImportForInvalidSourceOwner() throws Exception {
        CanvasStateRepository repository = new CanvasStateRepository();
        FakeCanvasStateMapper mapper = new FakeCanvasStateMapper();
        injectMapper(repository, mapper);

        List<CanvasState> imported = repository.importAnonymousWorkspace("admin", "usr_alice");

        assertTrue(imported.isEmpty());
        assertFalse(mapper.importListCalled);
    }

    @Test
    public void shouldDefineAnonymousWorkspaceImportMapperStatements() throws Exception {
        String mapperXml = new String(getClass()
                .getResourceAsStream("/mybatis/mapper/canvas_state_mapper.xml")
                .readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");

        assertTrue(mapperXml.contains("selectImportableDiagrams"));
        assertTrue(mapperXml.contains("insertImportedDiagram"));
        assertTrue(mapperXml.contains("insertImportedCanvasState"));
        assertTrue(mapperXml.contains("insertImportedConversationMessages"));
        assertTrue(mapperXml.contains("NOT EXISTS"));
        assertTrue(mapperXml.contains("d.deleted = 0"));
    }

    private void injectMapper(CanvasStateRepository repository, ICanvasStateMapper mapper) throws Exception {
        Field field = CanvasStateRepository.class.getDeclaredField("canvasStateMapper");
        field.setAccessible(true);
        field.set(repository, mapper);
    }

    private void setImportIdSupplier(CanvasStateRepository repository, Supplier<String> supplier) throws Exception {
        Field field = CanvasStateRepository.class.getDeclaredField("importedDiagramIdSupplier");
        field.setAccessible(true);
        field.set(repository, supplier);
    }

    private static class FakeCanvasStateMapper implements ICanvasStateMapper {

        private CanvasStatePO saved;
        private String selectedUserId;
        private String selectedDiagramId;
        private String listedUserId;
        private boolean listCalled;
        private boolean insertCanvasStateCalled;
        private boolean updateByVersionCalled;
        private int updateRows = 1;
        private boolean canvasStateExists = true;
        private int insertCanvasStateRows = 1;
        private String renamedUserId;
        private String renamedDiagramId;
        private String renamedTitle;
        private String thumbnailUserId;
        private String thumbnailDiagramId;
        private String thumbnailUrl;
        private String deletedUserId;
        private String deletedDiagramId;
        private boolean deleteCalled;
        private final Set<String> existingDiagramIds = new HashSet<>();
        private final List<String> checkedDiagramIds = new ArrayList<>();
        private boolean importListCalled;
        private String importSourceUserId;
        private String importSourceDiagramId;
        private String importTargetUserId;
        private String importTargetDiagramId;
        private String copiedCanvasTargetDiagramId;
        private String copiedMessagesTargetDiagramId;
        private String currentXml = "<mxGraphModel/>";

        @Override
        public CanvasStatePO selectByUserAndDiagram(String userId, String diagramId) {
            this.selectedUserId = userId;
            this.selectedDiagramId = diagramId;
            if (!canvasStateExists && !insertCanvasStateCalled && !updateByVersionCalled) {
                return null;
            }
            CanvasStatePO po = new CanvasStatePO();
            po.setUserId(userId);
            po.setDiagramId(diagramId);
            po.setTitle(renamedTitle == null ? "Checkout Flow" : renamedTitle);
            po.setDiagramType("architecture");
            po.setCurrentXml(currentXml);
            po.setSummary("persisted");
            po.setAnalysisJson("{\"valid\":true}");
            po.setVersion(4L);
            po.setThumbnailUrl(thumbnailUrl == null ? "data:image/png;base64,diagram1" : thumbnailUrl);
            return po;
        }

        @Override
        public List<CanvasStatePO> selectDiagramsByUser(String userId) {
            this.listCalled = true;
            this.listedUserId = userId;

            CanvasStatePO first = new CanvasStatePO();
            first.setUserId(userId);
            first.setDiagramId("diagram-1");
            first.setTitle("Checkout Flow");
            first.setDiagramType("architecture");
            first.setVersion(4L);
            first.setThumbnailUrl("data:image/png;base64,diagram1");

            CanvasStatePO second = new CanvasStatePO();
            second.setUserId(userId);
            second.setDiagramId("diagram-2");
            second.setTitle("Inventory Map");
            second.setDiagramType("basic");
            second.setVersion(2L);

            return List.of(first, second);
        }

        @Override
        public int upsertDiagram(CanvasStatePO state) {
            this.saved = state;
            return 1;
        }

        @Override
        public int insertCanvasState(CanvasStatePO state) {
            this.saved = state;
            this.insertCanvasStateCalled = true;
            return insertCanvasStateRows;
        }

        @Override
        public int updateCanvasStateByVersion(CanvasStatePO state) {
            this.saved = state;
            this.updateByVersionCalled = true;
            return updateRows;
        }

        @Override
        public int countCanvasState(String userId, String diagramId) {
            return canvasStateExists ? 1 : 0;
        }

        @Override
        public int updateDiagramTitle(String userId, String diagramId, String title) {
            this.renamedUserId = userId;
            this.renamedDiagramId = diagramId;
            this.renamedTitle = title;
            return 1;
        }

        @Override
        public int updateDiagramThumbnail(String userId, String diagramId, String thumbnailUrl) {
            this.thumbnailUserId = userId;
            this.thumbnailDiagramId = diagramId;
            this.thumbnailUrl = thumbnailUrl;
            return 1;
        }

        @Override
        public int softDeleteDiagram(String userId, String diagramId) {
            this.deleteCalled = true;
            this.deletedUserId = userId;
            this.deletedDiagramId = diagramId;
            return 1;
        }

        @Override
        public List<CanvasStatePO> selectImportableDiagrams(String userId) {
            this.importListCalled = true;

            CanvasStatePO source = new CanvasStatePO();
            source.setUserId(userId);
            source.setDiagramId("diagram-1");
            source.setTitle("Anonymous checkout");
            source.setDiagramType("flowchart");
            source.setCurrentXml("<mxGraphModel/>");
            source.setSummary("anonymous canvas");
            source.setAnalysisJson("{\"nodeCount\":1}");
            source.setVersion(7L);
            return List.of(source);
        }

        @Override
        public int countDiagramById(String diagramId) {
            this.checkedDiagramIds.add(diagramId);
            return existingDiagramIds.contains(diagramId) ? 1 : 0;
        }

        @Override
        public int insertImportedDiagram(String sourceUserId, String sourceDiagramId, String targetUserId, String targetDiagramId) {
            this.importSourceUserId = sourceUserId;
            this.importSourceDiagramId = sourceDiagramId;
            this.importTargetUserId = targetUserId;
            this.importTargetDiagramId = targetDiagramId;
            return 1;
        }

        @Override
        public int insertImportedCanvasState(String sourceUserId, String sourceDiagramId, String targetUserId, String targetDiagramId) {
            this.copiedCanvasTargetDiagramId = targetDiagramId;
            return 1;
        }

        @Override
        public int insertImportedConversationMessages(String sourceUserId, String sourceDiagramId, String targetUserId, String targetDiagramId) {
            this.copiedMessagesTargetDiagramId = targetDiagramId;
            return 2;
        }

        @Override
        public int redactCanvasStateForUser(String userId, String anonymizedUserId) {
            return 1;
        }

        @Override
        public int softDeleteAndAnonymizeUserDiagrams(String userId, String anonymizedUserId) {
            return 1;
        }
    }
}
