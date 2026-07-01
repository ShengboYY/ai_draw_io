package org.zipp.ai.test.infrastructure;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasStateVersionConflictException;
import org.zipp.ai.infrastructure.adapter.repository.CanvasStateRepository;
import org.zipp.ai.infrastructure.dao.ICanvasStateMapper;
import org.zipp.ai.infrastructure.dao.po.CanvasStatePO;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CanvasStateRepositoryTest {

    @Test
    public void shouldSaveCurrentCanvasStateAndReturnLatestRow() throws Exception {
        CanvasStateRepository repository = new CanvasStateRepository();
        FakeCanvasStateMapper mapper = new FakeCanvasStateMapper();
        injectMapper(repository, mapper);

        CanvasState saved = repository.save(CanvasState.builder()
                .userId("alice")
                .diagramId("diagram-1")
                .diagramType("architecture")
                .currentXml("<mxGraphModel/>")
                .summary("1 node")
                .analysisJson("{\"valid\":true}")
                .build());

        assertEquals("alice", mapper.saved.getUserId());
        assertEquals("diagram-1", mapper.saved.getDiagramId());
        assertEquals("<mxGraphModel/>", mapper.saved.getCurrentXml());
        assertTrue(mapper.upsertCanvasStateCalled);
        assertEquals(Long.valueOf(4L), saved.getVersion());
        assertEquals("persisted", saved.getSummary());
    }

    @Test
    public void shouldUpdateCanvasStateWithExpectedVersion() throws Exception {
        CanvasStateRepository repository = new CanvasStateRepository();
        FakeCanvasStateMapper mapper = new FakeCanvasStateMapper();
        injectMapper(repository, mapper);

        CanvasState saved = repository.save(CanvasState.builder()
                .userId("alice")
                .diagramId("diagram-1")
                .currentXml("<mxGraphModel/>")
                .version(3L)
                .build());

        assertTrue(mapper.updateByVersionCalled);
        assertEquals(Long.valueOf(3L), mapper.saved.getVersion());
        assertEquals(Long.valueOf(4L), mapper.syncedVersion);
        assertEquals(Long.valueOf(4L), saved.getVersion());
    }

    @Test(expected = CanvasStateVersionConflictException.class)
    public void shouldRejectStaleExpectedVersion() throws Exception {
        CanvasStateRepository repository = new CanvasStateRepository();
        FakeCanvasStateMapper mapper = new FakeCanvasStateMapper();
        mapper.updateRows = 0;
        injectMapper(repository, mapper);

        repository.save(CanvasState.builder()
                .userId("alice")
                .diagramId("diagram-1")
                .currentXml("<mxGraphModel/>")
                .version(3L)
                .build());
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

    private void injectMapper(CanvasStateRepository repository, ICanvasStateMapper mapper) throws Exception {
        Field field = CanvasStateRepository.class.getDeclaredField("canvasStateMapper");
        field.setAccessible(true);
        field.set(repository, mapper);
    }

    private static class FakeCanvasStateMapper implements ICanvasStateMapper {

        private CanvasStatePO saved;
        private String selectedUserId;
        private String selectedDiagramId;
        private String listedUserId;
        private boolean listCalled;
        private boolean upsertCanvasStateCalled;
        private boolean updateByVersionCalled;
        private int updateRows = 1;
        private Long syncedVersion;
        private String renamedUserId;
        private String renamedDiagramId;
        private String renamedTitle;
        private String deletedUserId;
        private String deletedDiagramId;
        private boolean deleteCalled;

        @Override
        public CanvasStatePO selectByUserAndDiagram(String userId, String diagramId) {
            this.selectedUserId = userId;
            this.selectedDiagramId = diagramId;
            CanvasStatePO po = new CanvasStatePO();
            po.setUserId(userId);
            po.setDiagramId(diagramId);
            po.setTitle(renamedTitle == null ? "Checkout Flow" : renamedTitle);
            po.setDiagramType("architecture");
            po.setCurrentXml("<mxGraphModel/>");
            po.setSummary("persisted");
            po.setAnalysisJson("{\"valid\":true}");
            po.setVersion(4L);
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
        public int upsertCanvasState(CanvasStatePO state) {
            this.saved = state;
            this.upsertCanvasStateCalled = true;
            return 1;
        }

        @Override
        public int updateCanvasStateByVersion(CanvasStatePO state) {
            this.saved = state;
            this.updateByVersionCalled = true;
            return updateRows;
        }

        @Override
        public int syncDiagramVersion(String userId, String diagramId, Long version) {
            this.syncedVersion = version;
            return 1;
        }

        @Override
        public int updateDiagramTitle(String userId, String diagramId, String title) {
            this.renamedUserId = userId;
            this.renamedDiagramId = diagramId;
            this.renamedTitle = title;
            return 1;
        }

        @Override
        public int softDeleteDiagram(String userId, String diagramId) {
            this.deleteCalled = true;
            this.deletedUserId = userId;
            this.deletedDiagramId = diagramId;
            return 1;
        }
    }
}
