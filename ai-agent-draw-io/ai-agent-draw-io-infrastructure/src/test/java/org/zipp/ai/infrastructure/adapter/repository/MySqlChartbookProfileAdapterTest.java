package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.chartbook.model.valobj.ChartbookProfile;
import org.zipp.ai.domain.chartbook.model.valobj.ChartbookProfilePatch;
import org.zipp.ai.domain.material.model.valobj.CatalogErrorCode;
import org.zipp.ai.domain.material.model.valobj.CatalogOperationException;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.infrastructure.dao.material.IChartbookProfileMapper;
import org.zipp.ai.infrastructure.dao.material.po.ChartbookProfileAuditPO;
import org.zipp.ai.infrastructure.dao.material.po.ChartbookProfilePO;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MySqlChartbookProfileAdapterTest {
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");
    private static final CatalogOwner OWNER = new CatalogOwner(OwnerType.USER, "user-1");

    @Test
    void staleVersionFailsBeforeWritingAndArchivedProfileIsReadOnly() {
        ChartbookProfilePO current = po("ACTIVE", 2);
        IChartbookProfileMapper mapper = proxy((method, args) -> switch (method) {
            case "selectAudit" -> null;
            case "selectCurrent" -> current;
            case "updateCurrent" -> 0;
            default -> unsupported(method);
        });

        CatalogOperationException conflict = assertThrows(CatalogOperationException.class,
                () -> new MySqlChartbookProfileAdapter(mapper).update(OWNER, "book-1",
                        new ChartbookProfilePatch("new", null, null, null, null, null),
                        1, "request-1", NOW));
        assertEquals(CatalogErrorCode.CATALOG_CONFLICT, conflict.code());

        ChartbookProfilePO archived = po("ARCHIVED", 2);
        IChartbookProfileMapper archivedMapper = proxy((method, args) -> switch (method) {
            case "selectAudit" -> null;
            case "selectCurrent" -> archived;
            default -> unsupported(method);
        });
        CatalogOperationException readOnly = assertThrows(CatalogOperationException.class,
                () -> new MySqlChartbookProfileAdapter(archivedMapper).update(OWNER, "book-1",
                        new ChartbookProfilePatch("new", null, null, null, null, null),
                        2, "request-2", NOW));
        assertEquals(CatalogErrorCode.CHARTBOOK_ARCHIVED, readOnly.code());
    }

    @Test
    void idempotentRetryReturnsTheCommittedHistoricVersion() {
        ChartbookProfileAuditPO audit = new ChartbookProfileAuditPO();
        audit.setVersion(1);
        ChartbookProfilePO historic = po("ACTIVE", 1);
        historic.setInstructions("committed instructions");
        historic.setProfileState("CONFIGURED");
        IChartbookProfileMapper mapper = proxy((method, args) -> switch (method) {
            case "selectAudit" -> audit;
            case "selectVersion" -> historic;
            default -> unsupported(method);
        });

        ChartbookProfile result = new MySqlChartbookProfileAdapter(mapper).update(OWNER, "book-1",
                new ChartbookProfilePatch("different retry", null, null, null, null, null),
                99, "request-1", NOW);

        assertEquals(1, result.version());
        assertEquals("committed instructions", result.instructions());
    }

    @Test
    void successfulUpdateWritesCurrentVersionHistoryAndAudit() {
        ChartbookProfilePO current = po("ACTIVE", 0);
        List<String> calls = new java.util.ArrayList<>();
        IChartbookProfileMapper mapper = proxy((method, args) -> {
            calls.add(method);
            return switch (method) {
                case "selectAudit" -> null;
                case "selectCurrent" -> current;
                case "updateCurrent", "insertVersion", "insertAudit" -> 1;
                default -> unsupported(method);
            };
        });

        ChartbookProfile result = new MySqlChartbookProfileAdapter(mapper).update(OWNER, "book-1",
                new ChartbookProfilePatch("instructions", null, null, null, null, null),
                0, "request-1", NOW);

        assertEquals(1, result.version());
        assertInstanceOf(ChartbookProfile.class, result);
        assertEquals(List.of("selectAudit", "selectCurrent", "updateCurrent", "insertVersion", "insertAudit"), calls);
    }

    private static ChartbookProfilePO po(String status, long version) {
        ChartbookProfilePO po = new ChartbookProfilePO();
        po.setChartbookId("book-1");
        po.setOwnerKey("user-1");
        po.setChartbookStatus(status);
        po.setProfileRowId("book-1");
        po.setVersion(version);
        po.setInstructions("");
        po.setGoal("");
        po.setSummary("");
        po.setGlossaryJson("{}");
        po.setDefaultStyleJson("{}");
        po.setStableConstraintsJson("[]");
        po.setProfileState("EMPTY");
        po.setUpdatedAt(NOW);
        return po;
    }

    private static Object unsupported(String method) {
        throw new UnsupportedOperationException(method);
    }

    @SuppressWarnings("unchecked")
    private static IChartbookProfileMapper proxy(Call call) {
        return (IChartbookProfileMapper) Proxy.newProxyInstance(
                IChartbookProfileMapper.class.getClassLoader(), new Class<?>[]{IChartbookProfileMapper.class},
                (proxy, method, args) -> call.invoke(method.getName(), args == null ? new Object[0] : args));
    }

    @FunctionalInterface
    private interface Call {
        Object invoke(String method, Object[] args);
    }
}
