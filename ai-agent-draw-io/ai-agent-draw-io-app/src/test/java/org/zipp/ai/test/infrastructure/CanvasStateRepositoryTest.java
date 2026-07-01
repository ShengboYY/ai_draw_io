package org.zipp.ai.test.infrastructure;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasStateVersionConflictException;
import org.zipp.ai.infrastructure.adapter.repository.CanvasStateRepository;
import org.zipp.ai.infrastructure.dao.ICanvasStateMapper;
import org.zipp.ai.infrastructure.dao.po.CanvasStatePO;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
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

    private void injectMapper(CanvasStateRepository repository, ICanvasStateMapper mapper) throws Exception {
        Field field = CanvasStateRepository.class.getDeclaredField("canvasStateMapper");
        field.setAccessible(true);
        field.set(repository, mapper);
    }

    private static class FakeCanvasStateMapper implements ICanvasStateMapper {

        private CanvasStatePO saved;
        private String selectedUserId;
        private String selectedDiagramId;
        private boolean upsertCanvasStateCalled;
        private boolean updateByVersionCalled;
        private int updateRows = 1;
        private Long syncedVersion;

        @Override
        public CanvasStatePO selectByUserAndDiagram(String userId, String diagramId) {
            this.selectedUserId = userId;
            this.selectedDiagramId = diagramId;
            CanvasStatePO po = new CanvasStatePO();
            po.setUserId(userId);
            po.setDiagramId(diagramId);
            po.setDiagramType("architecture");
            po.setCurrentXml("<mxGraphModel/>");
            po.setSummary("persisted");
            po.setAnalysisJson("{\"valid\":true}");
            po.setVersion(4L);
            return po;
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
    }
}
