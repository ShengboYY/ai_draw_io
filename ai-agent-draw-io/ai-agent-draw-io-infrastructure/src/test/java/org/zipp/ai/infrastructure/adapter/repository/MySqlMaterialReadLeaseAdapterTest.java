package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.material.model.aggregate.EvidenceReadLease;
import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.infrastructure.dao.material.IMaterialReadLeaseMapper;
import org.zipp.ai.infrastructure.dao.material.po.EvidenceReadLeasePO;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MySqlMaterialReadLeaseAdapterTest {
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void acquireLocksAndReauthorizesMaterialBeforeWritingLease() {
        AtomicReference<EvidenceReadLeasePO> inserted = new AtomicReference<>();
        IMaterialReadLeaseMapper mapper = mapper((method, args) -> switch (method) {
            case "lockAuthorizedMaterial" -> "material_1";
            case "selectRunSourceForUpdate" -> null;
            case "insertLease" -> {
                inserted.set((EvidenceReadLeasePO) args[0]);
                yield 1;
            }
            default -> unsupported(method);
        });
        var adapter = new MySqlMaterialReadLeaseAdapter(mapper);
        EvidenceReadLease candidate = EvidenceReadLease.issue("lease_1", "user_1", "material_1",
                "version_1", "revision_1", "run_1", NOW);

        Optional<EvidenceReadLease> acquired = adapter.acquire(request(), candidate);

        assertTrue(acquired.isPresent());
        assertEquals("version_1", inserted.get().getVersionId());
        assertEquals("ACTIVE", inserted.get().getStatus());
    }

    @Test
    void failedRowLockAuthorizationNeverWritesLease() {
        IMaterialReadLeaseMapper mapper = mapper((method, args) -> {
            if ("lockAuthorizedMaterial".equals(method)) return null;
            throw new AssertionError("denied source must not touch lease rows");
        });
        EvidenceReadLease candidate = EvidenceReadLease.issue("lease_1", "user_1", "material_1",
                "version_1", "revision_1", "run_1", NOW);

        CatalogOperationException error = assertThrows(CatalogOperationException.class,
                () -> new MySqlMaterialReadLeaseAdapter(mapper).acquire(request(), candidate));
        assertEquals(CatalogErrorCode.READ_LEASE_DENIED, error.code());
    }

    private MaterialReadLeaseRequest request() {
        return new MaterialReadLeaseRequest(new CatalogOwner(OwnerType.USER, "user_1"),
                "material_1", "version_1", "revision_1", "run_1",
                MaterialScopeType.LIBRARY, "personal", false);
    }

    @SuppressWarnings("unchecked")
    private IMaterialReadLeaseMapper mapper(Call call) {
        return (IMaterialReadLeaseMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{IMaterialReadLeaseMapper.class},
                (proxy, method, args) -> call.invoke(method.getName(),
                        args == null ? new Object[0] : args));
    }

    private static Object unsupported(String method) {
        throw new AssertionError("unexpected mapper call: " + method);
    }

    @FunctionalInterface
    private interface Call {
        Object invoke(String method, Object[] args);
    }
}
