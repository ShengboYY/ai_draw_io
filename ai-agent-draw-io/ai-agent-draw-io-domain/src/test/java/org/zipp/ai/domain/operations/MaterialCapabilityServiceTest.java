package org.zipp.ai.domain.operations;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MaterialCapabilityServiceTest {

    @Test
    void plainTextRemainsAvailableWhenEveryMaterialCapabilityIsDisabled() {
        MaterialCapabilityReport report = service(operations(0, 0, 0), 10).assess(
                MaterialFeatureSet.allDisabled(), false);

        assertEquals(CapabilityState.AVAILABLE, report.capability(MaterialCapability.PLAIN_TEXT_DRAWING));
        assertEquals(CapabilityState.DISABLED, report.capability(MaterialCapability.MATERIAL_UPLOAD));
        assertEquals(CapabilityState.DISABLED, report.capability(MaterialCapability.EVIDENCE_ANSWER));
    }

    @Test
    void reportsMisorderedFlagsAndOperationalLagWithoutPretendingTheApplicationIsDown() {
        MaterialFeatureSet flags = new MaterialFeatureSet(true, true, true, false,
                true, false, true, true, true);

        MaterialCapabilityReport report = service(operations(8, 121, 2), 72).assess(flags, true);

        assertEquals(CapabilityState.AVAILABLE, report.capability(MaterialCapability.PLAIN_TEXT_DRAWING));
        assertEquals(CapabilityState.MISCONFIGURED, report.capability(MaterialCapability.RETRIEVAL));
        assertEquals(CapabilityState.DEGRADED, report.overallMaterialState());
        assertEquals(8, report.operations().queuedJobs());
    }

    @Test
    void anonymousUploadIsLastAndRequiresReleaseApprovalAndHealthyCapacity() {
        MaterialFeatureSet flags = MaterialFeatureSet.allEnabled();

        assertEquals(CapabilityState.BLOCKED,
                service(operations(0, 0, 0), 10).assess(flags, false)
                        .capability(MaterialCapability.ANONYMOUS_UPLOAD));
        assertEquals(CapabilityState.BLOCKED,
                service(operations(0, 0, 0), 95).assess(flags, true)
                        .capability(MaterialCapability.ANONYMOUS_UPLOAD));
        assertEquals(CapabilityState.AVAILABLE,
                service(operations(0, 0, 0), 10).assess(flags, true)
                        .capability(MaterialCapability.ANONYMOUS_UPLOAD));
    }

    @Test
    void unavailableOperationalProjectionIsDegradedEvenWhenCapacityIsHealthy() {
        MaterialCapabilityService service = new MaterialCapabilityService(
                () -> { throw new IllegalStateException("database unavailable"); },
                new MaterialCapacityBreaker(() -> new MaterialCapacitySnapshot(10, 10, 10, 10, true)));

        MaterialCapabilityReport report = service.assess(MaterialFeatureSet.allEnabled(), true);

        assertEquals(CapabilityState.DEGRADED, report.overallMaterialState());
        assertEquals(CapabilityState.AVAILABLE, report.capability(MaterialCapability.PLAIN_TEXT_DRAWING));
    }

    private MaterialCapabilityService service(MaterialOperationalSnapshot operations, double capacity) {
        MaterialCapacityBreaker breaker = new MaterialCapacityBreaker(() ->
                new MaterialCapacitySnapshot(capacity, 10, 10, 10, true));
        return new MaterialCapabilityService(() -> operations, breaker);
    }

    private MaterialOperationalSnapshot operations(int queued, long oldestQueuedSeconds, int stuckDeleting) {
        return new MaterialOperationalSnapshot(Instant.parse("2026-07-20T00:00:00Z"), queued, 0, 0,
                oldestQueuedSeconds, 0, 0, stuckDeleting, 0, 100, 0, 0, 0, 0);
    }
}
