package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.retrieval.CanvasProbe;
import org.zipp.ai.domain.retrieval.CloseReason;
import org.zipp.ai.domain.retrieval.EvidencePreparationCommand;
import org.zipp.ai.domain.retrieval.EvidencePreparationModule;
import org.zipp.ai.domain.retrieval.PreparationOutcome;
import org.zipp.ai.domain.retrieval.ResolvedSourceSet;
import org.zipp.ai.domain.retrieval.RunResourceDomain;
import org.zipp.ai.domain.retrieval.SourceMode;
import org.zipp.ai.domain.retrieval.ValidatedSelection;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlSourceAwarePreparationAdapterResourceTest {

    @Test
    void exceptionalPreparationClosesOwnedResourcesAsFailed() {
        RunResourceDomain resources = new RunResourceDomain();
        AtomicInteger closes = attachTrackedResource(resources);
        EvidencePreparationModule module = (command, run, progress, cancellation) ->
                CompletableFuture.failedFuture(new IllegalStateException("provider failed"));

        assertThrows(RuntimeException.class, () ->
                MySqlSourceAwarePreparationAdapter.awaitPreparation(
                        module, command(), resources, () -> false));

        assertTrue(resources.isClosed());
        assertEquals(CloseReason.FAILED, resources.closeReason().orElseThrow());
        assertEquals(1, closes.get());
    }

    @Test
    void exceptionalCancellationClosesOwnedResourcesAsCancelled() {
        RunResourceDomain resources = new RunResourceDomain();
        AtomicInteger closes = attachTrackedResource(resources);
        EvidencePreparationModule module = (command, run, progress, cancellation) ->
                CompletableFuture.failedFuture(new CancellationException("cancelled"));

        PreparationOutcome outcome = MySqlSourceAwarePreparationAdapter.awaitPreparation(
                module, command(), resources, () -> false);

        assertInstanceOf(PreparationOutcome.Cancelled.class, outcome);
        assertEquals(CloseReason.CANCELLED, resources.closeReason().orElseThrow());
        assertEquals(1, closes.get());
    }

    private static AtomicInteger attachTrackedResource(RunResourceDomain resources) {
        AtomicInteger closes = new AtomicInteger();
        resources.attach(closes::incrementAndGet);
        return closes;
    }

    private static EvidencePreparationCommand command() {
        return new EvidencePreparationCommand(
                new CatalogOwner(OwnerType.USER, "owner-1"),
                "diagram-1",
                "conversation-1",
                "request-1",
                "run-1",
                "use the source",
                CanvasProbe.unavailableProbe(),
                ValidatedSelection.empty(),
                SourceMode.EXPLICIT,
                ResolvedSourceSet.empty(SourceMode.EXPLICIT),
                List.of(),
                "REQUIRED",
                "NONE");
    }
}
