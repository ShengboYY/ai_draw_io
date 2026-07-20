package org.zipp.ai.domain.material;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.material.model.aggregate.EvidenceReadLease;
import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.domain.material.port.MaterialReadLeasePort;
import org.zipp.ai.domain.material.service.MaterialReadLeaseService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MaterialReadLeaseServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");
    private static final CatalogOwner USER = new CatalogOwner(OwnerType.USER, "user_1");

    @Test
    void authorizedSourceGetsFiveMinuteLeaseWithFifteenMinuteHardLimit() {
        FakeReadLeasePort port = new FakeReadLeasePort(true);

        EvidenceReadLease lease = service(port).acquire(request());

        assertEquals(NOW.plus(Duration.ofMinutes(5)), lease.expiresAt());
        assertEquals(NOW.plus(Duration.ofMinutes(15)), lease.maxExpiresAt());
        assertEquals(MaterialReadLeaseStatus.ACTIVE, lease.status());
    }

    @Test
    void deniedOrExpiredSourceDoesNotLeakAReadableLease() {
        FakeReadLeasePort port = new FakeReadLeasePort(false);

        CatalogOperationException error = assertThrows(CatalogOperationException.class,
                () -> service(port).acquire(request()));

        assertEquals(CatalogErrorCode.READ_LEASE_DENIED, error.code());
    }

    @Test
    void renewalNeverCrossesTheOriginalFifteenMinuteBoundary() {
        FakeReadLeasePort port = new FakeReadLeasePort(true);
        MaterialReadLeaseService service = service(port);
        EvidenceReadLease lease = service.acquire(request());
        port.now = NOW.plus(Duration.ofMinutes(4));
        service.renew(USER, lease.id(), "run_1");
        port.now = NOW.plus(Duration.ofMinutes(8));
        service.renew(USER, lease.id(), "run_1");
        port.now = NOW.plus(Duration.ofMinutes(12));
        EvidenceReadLease renewed = service.renew(USER, lease.id(), "run_1");

        assertEquals(NOW.plus(Duration.ofMinutes(15)), renewed.expiresAt());
    }

    private MaterialReadLeaseService service(FakeReadLeasePort port) {
        return new MaterialReadLeaseService(port, prefix -> prefix + "_1", port.clock());
    }

    private MaterialReadLeaseRequest request() {
        return new MaterialReadLeaseRequest(USER, "material_1", "version_1", "revision_1",
                "run_1", MaterialScopeType.LIBRARY, "personal", false);
    }

    private static final class FakeReadLeasePort implements MaterialReadLeasePort {
        private final boolean authorize;
        private EvidenceReadLease lease;
        private Instant now = NOW;

        private FakeReadLeasePort(boolean authorize) {
            this.authorize = authorize;
        }

        @Override
        public Optional<EvidenceReadLease> acquire(MaterialReadLeaseRequest request,
                                                   EvidenceReadLease candidate) {
            if (!authorize) return Optional.empty();
            lease = candidate;
            return Optional.of(candidate);
        }

        @Override
        public Optional<List<EvidenceReadLease>> acquireAll(List<MaterialReadLeaseRequest> requests,
                                                            List<EvidenceReadLease> candidates) {
            if (!authorize) return Optional.empty();
            lease = candidates.get(0);
            return Optional.of(candidates);
        }

        @Override
        public Optional<EvidenceReadLease> findOwned(CatalogOwner owner, String leaseId, String runId) {
            return Optional.ofNullable(lease);
        }

        @Override
        public boolean save(EvidenceReadLease changed, MaterialReadLeaseStatus expectedStatus) {
            lease = changed;
            return true;
        }

        @Override
        public int expireDue(Instant expiryTime, int limit) {
            return 0;
        }

        private Clock clock() {
            return new Clock() {
                @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(java.time.ZoneId zone) { return this; }
                @Override public Instant instant() { return now; }
            };
        }
    }
}
