package org.zipp.ai.domain.operations;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialCapacityBreakerTest {

    @Test
    void stopsOnlyNewAnonymousWorkAtTheExhaustionBoundary() {
        MaterialCapacityBreaker breaker = new MaterialCapacityBreaker(() -> snapshot(95));

        CapacityDecision anonymous = breaker.decide(OwnerType.ANONYMOUS, CapacityWorkload.NEW_UPLOAD);
        CapacityDecision registered = breaker.decide(OwnerType.USER, CapacityWorkload.NEW_UPLOAD);

        assertFalse(anonymous.allowed());
        assertEquals("ANONYMOUS_CAPACITY_EXHAUSTED", anonymous.reasonCode());
        assertTrue(registered.allowed());
        assertEquals(CapacityLevel.EXHAUSTED, registered.level());
    }

    @Test
    void restrictsAnonymousLowPriorityReprocessingBeforeNewUploads() {
        MaterialCapacityBreaker breaker = new MaterialCapacityBreaker(() -> snapshot(85));

        assertFalse(breaker.decide(OwnerType.ANONYMOUS, CapacityWorkload.REPROCESS).allowed());
        assertTrue(breaker.decide(OwnerType.ANONYMOUS, CapacityWorkload.NEW_UPLOAD).allowed());
        assertEquals(CapacityLevel.RESTRICTED,
                breaker.decide(OwnerType.USER, CapacityWorkload.RETRIEVAL).level());
    }

    @Test
    void unavailableCapacityTelemetryFailsClosedForAnonymousButPreservesRegisteredAndPlainText() {
        MaterialCapacityBreaker breaker = new MaterialCapacityBreaker(() -> {
            throw new IllegalStateException("metrics unavailable");
        });

        assertFalse(breaker.decide(OwnerType.ANONYMOUS, CapacityWorkload.NEW_UPLOAD).allowed());
        assertTrue(breaker.decide(OwnerType.USER, CapacityWorkload.NEW_UPLOAD).allowed());
        assertTrue(breaker.decide(OwnerType.ANONYMOUS, CapacityWorkload.PLAIN_TEXT_DRAWING).allowed());
    }

    private MaterialCapacitySnapshot snapshot(double maximumUsagePercent) {
        return new MaterialCapacitySnapshot(maximumUsagePercent, 20, 30, 10, true);
    }
}
