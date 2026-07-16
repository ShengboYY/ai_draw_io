package org.zipp.ai.test.infrastructure;

import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.zipp.ai.infrastructure.adapter.repository.AgentUsageTelemetryRepository;
import org.zipp.ai.infrastructure.dao.IAgentUsageTelemetryMapper;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentUsageTelemetryRepositoryTest {

    @Test
    public void visualRepairClaimUsesAStableIdAndTheSourceRunOwnershipTuple() {
        List<List<Object>> attempts = new ArrayList<>();
        IAgentUsageTelemetryMapper mapper = (IAgentUsageTelemetryMapper) Proxy.newProxyInstance(
                IAgentUsageTelemetryMapper.class.getClassLoader(),
                new Class<?>[]{IAgentUsageTelemetryMapper.class},
                (proxy, method, args) -> {
                    if (!"tryClaimVisualRepair".equals(method.getName())) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    attempts.add(List.of(args));
                    return attempts.size() == 1 ? 1 : 0;
                });
        AgentUsageTelemetryRepository repository = new AgentUsageTelemetryRepository();
        ReflectionTestUtils.setField(repository, "agentUsageTelemetryMapper", mapper);
        Instant now = Instant.parse("2026-07-16T00:00:00Z");

        assertTrue(repository.tryClaimVisualRepair(
                "source-run", "usr_owner", "diagram-1", "review-request",
                7L, "sha256:current", "aru_repair_1", now));
        assertFalse(repository.tryClaimVisualRepair(
                "source-run", "usr_owner", "diagram-1", "review-request-2",
                7L, "sha256:current", "aru_repair_2", now));

        assertEquals(attempts.get(0).get(0), attempts.get(1).get(0));
        assertEquals("source-run", attempts.get(0).get(1));
        assertEquals("usr_owner", attempts.get(0).get(2));
        assertEquals("diagram-1", attempts.get(0).get(3));
        assertTrue(String.valueOf(attempts.get(0).get(7)).contains("aru_repair_1"));
    }

    @Test
    public void visualRepairResultChecksTheClaimedRepairRunAndExactSavedCanvas() {
        List<Object> arguments = new ArrayList<>();
        IAgentUsageTelemetryMapper mapper = (IAgentUsageTelemetryMapper) Proxy.newProxyInstance(
                IAgentUsageTelemetryMapper.class.getClassLoader(),
                new Class<?>[]{IAgentUsageTelemetryMapper.class},
                (proxy, method, args) -> {
                    if (!"countVisualRepairResult".equals(method.getName())) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    arguments.addAll(List.of(args));
                    return 1;
                });
        AgentUsageTelemetryRepository repository = new AgentUsageTelemetryRepository();
        ReflectionTestUtils.setField(repository, "agentUsageTelemetryMapper", mapper);

        assertTrue(repository.isVisualRepairResult(
                "source-run", "aru_repair_1", "usr_owner", "diagram-1", 8L, "sha256:repaired"));

        assertEquals("source-run", arguments.get(1));
        assertEquals("aru_repair_1", arguments.get(2));
        assertEquals(8L, arguments.get(5));
        assertEquals("sha256:repaired", arguments.get(6));
    }

    @Test
    public void deleteTelemetryBeforeDeletesExpiredRunsInBatches() {
        List<String> firstBatch = List.of("aru_old_1", "aru_old_2");
        List<String> secondBatch = List.of("aru_old_3");
        List<String> calls = new ArrayList<>();
        IAgentUsageTelemetryMapper mapper = fakeMapper(calls, firstBatch, secondBatch);
        AgentUsageTelemetryRepository repository = new AgentUsageTelemetryRepository();
        ReflectionTestUtils.setField(repository, "agentUsageTelemetryMapper", mapper);
        ReflectionTestUtils.setField(repository, "deleteBatchSize", 2);

        int deleted = repository.deleteTelemetryBefore(Instant.parse("2026-07-01T00:00:00Z"));

        assertEquals(18, deleted);
        assertEquals(List.of(
                "selectExpiredRunIds:2",
                "deleteTraceEventsByRunIds:" + firstBatch,
                "deleteDiagramSnapshotsByRunIds:" + firstBatch,
                "deleteToolCallsByRunIds:" + firstBatch,
                "deleteLlmCallsByRunIds:" + firstBatch,
                "deleteStepsByRunIds:" + firstBatch,
                "deleteRunsByIds:" + firstBatch,
                "selectExpiredRunIds:2",
                "deleteTraceEventsByRunIds:" + secondBatch,
                "deleteDiagramSnapshotsByRunIds:" + secondBatch,
                "deleteToolCallsByRunIds:" + secondBatch,
                "deleteLlmCallsByRunIds:" + secondBatch,
                "deleteStepsByRunIds:" + secondBatch,
                "deleteRunsByIds:" + secondBatch
        ), calls);
    }

    @SafeVarargs
    private IAgentUsageTelemetryMapper fakeMapper(List<String> calls, List<String>... batches) {
        Queue<List<String>> pendingBatches = new ArrayDeque<>(List.of(batches));
        return (IAgentUsageTelemetryMapper) Proxy.newProxyInstance(
                IAgentUsageTelemetryMapper.class.getClassLoader(),
                new Class<?>[]{IAgentUsageTelemetryMapper.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("selectExpiredRunIds".equals(name)) {
                        calls.add(name + ":" + args[1]);
                        return pendingBatches.isEmpty() ? List.of() : pendingBatches.remove();
                    }
                    if (name.startsWith("delete") && args != null && args.length == 1 && args[0] instanceof List<?> runIds) {
                        calls.add(name + ":" + runIds);
                        return runIds.size();
                    }
                    throw new UnsupportedOperationException(name);
                });
    }
}
