package org.zipp.ai.test.domain.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseSourceType;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseWorkingCopy;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseWorkingCopyStatus;
import org.zipp.ai.infrastructure.adapter.repository.evaluation.controlplane.EvalCaseWorkingCopyRepository;
import org.zipp.ai.infrastructure.dao.IEvalCaseWorkingCopyMapper;
import org.zipp.ai.infrastructure.dao.po.evaluation.controlplane.EvalCaseWorkingCopyPO;

import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class EvalCaseWorkingCopyRepositoryTest {

    @Test
    public void repositoryMustRoundTripDefinitionAndReportRevisionConflicts() {
        FakeMapper mapper = new FakeMapper();
        EvalCaseWorkingCopyRepository repository = new EvalCaseWorkingCopyRepository(
                mapper, new ObjectMapper().findAndRegisterModules());
        EvalCaseWorkingCopy value = workingCopy();

        repository.insert(value);
        EvalCaseWorkingCopy restored = repository.find("ecw_1").orElseThrow();

        assertEquals("case-1", restored.getDefinition().getCaseId());
        assertEquals("hello", restored.getDefinition().getInput().get("user"));
        assertEquals("ecc-source", restored.getCandidateId());
        value.setCandidateId(null);
        assertTrue(repository.update(value, 1L));
        assertNull(repository.find("ecw_1").orElseThrow().getCandidateId());
        mapper.allowUpdate = false;
        assertFalse(repository.update(value, 1L));
    }

    @Test
    public void repositoryMustForwardBoundedListFilters() {
        FakeMapper mapper = new FakeMapper();
        EvalCaseWorkingCopyRepository repository = new EvalCaseWorkingCopyRepository(
                mapper, new ObjectMapper().findAndRegisterModules());
        repository.insert(workingCopy());

        List<EvalCaseWorkingCopy> result = repository.list(EvalCaseWorkingCopyStatus.DRAFT,
                "editor-1", 20, 5);

        assertEquals(1, result.size());
        assertEquals("DRAFT", mapper.status);
        assertEquals("editor-1", mapper.owner);
        assertEquals(20, mapper.limit);
        assertEquals(5, mapper.offset);
    }

    private EvalCaseWorkingCopy workingCopy() {
        Instant now = Instant.parse("2026-07-13T02:00:00Z");
        return EvalCaseWorkingCopy.builder().id("ecw_1").caseId("case-1").caseVersion("1")
                .sourceType(EvalCaseSourceType.TRACE_DRAFT).candidateId("ecc-source")
                .status(EvalCaseWorkingCopyStatus.DRAFT)
                .ownerUserId("editor-1").revision(1L)
                .definition(EvalCaseDefinition.builder().caseId("case-1").caseVersion("1")
                        .input(java.util.Map.of("user", "hello"))
                        .privacy(new EvalCaseDefinition.Privacy("synthetic", "manual-v1")).build())
                .createdAt(now).updatedAt(now).build();
    }

    private static final class FakeMapper implements IEvalCaseWorkingCopyMapper {
        private EvalCaseWorkingCopyPO value;
        private boolean allowUpdate = true;
        private String status;
        private String owner;
        private int limit;
        private int offset;

        @Override public EvalCaseWorkingCopyPO selectById(String id) { return value; }
        @Override public EvalCaseWorkingCopyPO selectByCandidateId(String candidateId) {
            return value != null && candidateId.equals(value.getCandidateId()) ? value : null;
        }
        @Override public List<EvalCaseWorkingCopyPO> selectList(String status, String ownerUserId, int limit, int offset) {
            this.status = status; this.owner = ownerUserId; this.limit = limit; this.offset = offset;
            return value == null ? List.of() : List.of(value);
        }
        @Override public int insert(EvalCaseWorkingCopyPO value) { this.value = value; return 1; }
        @Override public int insertTraceDraftIfAbsent(EvalCaseWorkingCopyPO workingCopy) {
            if (value != null) return 0;
            value = workingCopy;
            return 1;
        }
        @Override public int update(EvalCaseWorkingCopyPO value, long expectedRevision) {
            if (!allowUpdate) return 0;
            this.value = value;
            return 1;
        }
    }
}
