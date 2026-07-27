package org.zipp.ai.domain.retrieval;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunResourceDomainTest {

    @Test
    void closesAttachedResourcesExactlyOnce() {
        AtomicInteger closes = new AtomicInteger();
        RunResourceDomain resources = new RunResourceDomain();
        resources.attach(closes::incrementAndGet);

        resources.closeExactlyOnce(CloseReason.CANCELLED);
        resources.closeExactlyOnce(CloseReason.FAILED);

        assertEquals(1, closes.get());
        assertEquals(CloseReason.CANCELLED, resources.closeReason().orElseThrow());
    }

    @Test
    void immediatelyClosesLateResources() {
        AtomicInteger closes = new AtomicInteger();
        RunResourceDomain resources = new RunResourceDomain();
        resources.closeExactlyOnce(CloseReason.CLIENT_DISCONNECTED);

        resources.attach(closes::incrementAndGet);

        assertEquals(1, closes.get());
        assertTrue(resources.isClosed());
    }

    @Test
    void enforcesPreparedAndCommittingTransitions() {
        RunResourceDomain resources = new RunResourceDomain();

        assertThrows(IllegalStateException.class, resources::beginCommit);
        resources.markPrepared();
        resources.markPrepared();
        resources.beginCommit();
        resources.closeExactlyOnce(CloseReason.COMMITTED);

        assertEquals(RunResourceState.CLOSED, resources.state());
    }
}
