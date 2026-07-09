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

public class AgentUsageTelemetryRepositoryTest {

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

        assertEquals(15, deleted);
        assertEquals(List.of(
                "selectExpiredRunIds:2",
                "deleteTraceEventsByRunIds:" + firstBatch,
                "deleteToolCallsByRunIds:" + firstBatch,
                "deleteLlmCallsByRunIds:" + firstBatch,
                "deleteStepsByRunIds:" + firstBatch,
                "deleteRunsByIds:" + firstBatch,
                "selectExpiredRunIds:2",
                "deleteTraceEventsByRunIds:" + secondBatch,
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
