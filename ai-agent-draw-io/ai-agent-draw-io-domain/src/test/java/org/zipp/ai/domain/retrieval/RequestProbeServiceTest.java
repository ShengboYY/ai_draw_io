package org.zipp.ai.domain.retrieval;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.retrieval.internal.DefaultRequestProbeService;
import org.zipp.ai.domain.retrieval.internal.DeadlineRequestProbeService;
import org.zipp.ai.domain.retrieval.port.RequestProbeDataPort;

import java.util.List;
import java.util.Optional;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestProbeServiceTest {

    @Test
    void exposesOnlyTrustedCountsAndServerCanvasFacts() {
        RequestProbeDataPort data = new RequestProbeDataPort() {
            @Override
            public SourceProbe probeSources(RequestProbeCommand command) {
                return new SourceProbe(1, List.of("PDF"), List.of("READY"), 0,
                        true, false, true, true, true, 0, SourceMode.EXPLICIT);
            }

            @Override
            public Optional<ServerCanvasFacts> loadCanvasFacts(CatalogOwner owner, String diagramId,
                                                                List<String> selectedCellIds) {
                return Optional.of(new ServerCanvasFacts(4, 3, 9L, "server-hash",
                        1, List.of("NODE")));
            }
        };
        RequestProbeService service = new DefaultRequestProbeService(data);

        RequestProbe result = service.probe(new RequestProbeCommand(
                new CatalogOwner(OwnerType.USER, "alice"), "diagram-1", "conversation-1",
                SourceMode.EXPLICIT, List.of("version-1"), List.of("cell-1"), 9L, "server-hash"));

        assertEquals(1, result.sources().selectedCount());
        assertEquals(4, result.canvas().nodeCount());
        assertEquals("server-hash", result.canvas().contentHash());
        assertFalse(result.canvas().selectionVersionMismatch());
    }

    @Test
    void neverFallsBackToClientCanvasWhenServerCanvasIsUnavailable() {
        RequestProbeDataPort data = new RequestProbeDataPort() {
            @Override
            public SourceProbe probeSources(RequestProbeCommand command) {
                return SourceProbe.empty(command.sourceMode());
            }

            @Override
            public Optional<ServerCanvasFacts> loadCanvasFacts(CatalogOwner owner, String diagramId,
                                                                List<String> selectedCellIds) {
                return Optional.empty();
            }
        };

        RequestProbe result = new DefaultRequestProbeService(data).probe(new RequestProbeCommand(
                new CatalogOwner(OwnerType.USER, "alice"), "diagram-1", "conversation-1",
                SourceMode.AUTO, List.of(), List.of("untrusted-cell"), 7L, "client-hash"));

        assertFalse(result.canvas().hasCanvas());
        assertTrue(result.canvas().unavailable());
        assertEquals(0, result.canvas().selectedCellCount());
    }

    @Test
    void requestProbeUsesTheFixedSourceSnapshotWithoutResolvingIdsAgain() {
        AtomicInteger sourceCalls = new AtomicInteger();
        RequestProbeDataPort data = new RequestProbeDataPort() {
            @Override
            public SourceProbe probeSources(RequestProbeCommand command) {
                sourceCalls.incrementAndGet();
                return SourceProbe.empty(SourceMode.AUTO);
            }

            @Override
            public Optional<ServerCanvasFacts> loadCanvasFacts(CatalogOwner owner, String diagramId,
                                                                List<String> selectedCellIds) {
                return Optional.empty();
            }
        };
        ResolvedSourceSet snapshot = new ResolvedSourceSet(SourceMode.EXPLICIT_ONLY, List.of(), 1, 0);
        RequestProbeCommand command = new RequestProbeCommand(owner(), "diagram-1", "conversation-1",
                SourceMode.EXPLICIT_ONLY, List.of("upl-1"), List.of(), snapshot,
                List.of(), null, "");

        RequestProbe result = new DefaultRequestProbeService(data).probe(command);

        assertEquals(1, result.sources().pendingConversationUploadCount());
        assertEquals(0, sourceCalls.get());
    }

    @Test
    void boundsProbeDependencyWait() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            RequestProbeService slow = command -> {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return new RequestProbe(SourceProbe.empty(SourceMode.AUTO), CanvasProbe.unavailableProbe());
            };
            RequestProbeService bounded = new DeadlineRequestProbeService(slow, executor, Duration.ofMillis(20));

            assertThrows(IllegalStateException.class, () -> bounded.probe(new RequestProbeCommand(
                    new CatalogOwner(OwnerType.USER, "alice"), "diagram-1", "conversation-1",
                    SourceMode.AUTO, List.of(), List.of(), null, "")));
        } finally {
            executor.shutdownNow();
        }
    }

    private CatalogOwner owner() {
        return new CatalogOwner(OwnerType.USER, "alice");
    }
}
