package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.chartbook.model.aggregate.Chartbook;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.infrastructure.dao.material.IChartbookCatalogMapper;
import org.zipp.ai.infrastructure.dao.material.po.ChartbookCatalogPO;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MySqlChartbookCatalogAdapterTest {
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");
    private static final CatalogOwner OWNER = new CatalogOwner(OwnerType.USER, "user_1");

    @Test
    void createReadsBackTheStableIdempotencyWinner() {
        ChartbookCatalogPO winner = po("book_winner");
        IChartbookCatalogMapper mapper = proxy((method, args) -> switch (method) {
            case "insertChartbook" -> 0;
            case "selectByIdempotencyKey" -> winner;
            case "selectDiagramIds", "selectMaterialIds" -> List.of();
            default -> unsupported(method);
        });
        Chartbook requested = Chartbook.create("book_loser", OwnerType.USER, "user_1", "Agile");

        var result = new MySqlChartbookCatalogAdapter(mapper)
                .createOrFind(requested, "request_1", NOW);

        assertEquals("book_winner", result.chartbookId());
    }

    @Test
    void archiveDetachesDiagramsOnlyAfterTheOwnedBookTransitionWins() {
        List<String> calls = new ArrayList<>();
        IChartbookCatalogMapper mapper = proxy((method, args) -> {
            calls.add(method);
            return switch (method) {
                case "archive" -> 1;
                case "detachDiagrams" -> 2;
                default -> unsupported(method);
            };
        });

        assertTrue(new MySqlChartbookCatalogAdapter(mapper).archive(OWNER, "book_1"));
        assertTrue(calls.indexOf("archive") < calls.indexOf("detachDiagrams"));
    }

    private static ChartbookCatalogPO po(String id) {
        ChartbookCatalogPO po = new ChartbookCatalogPO();
        po.setId(id);
        po.setOwnerKey("user_1");
        po.setName("Agile");
        po.setStatus("ACTIVE");
        po.setCreatedAt(NOW);
        po.setUpdatedAt(NOW);
        return po;
    }

    private static Object unsupported(String method) {
        throw new UnsupportedOperationException(method);
    }

    @SuppressWarnings("unchecked")
    private static IChartbookCatalogMapper proxy(Call call) {
        return (IChartbookCatalogMapper) Proxy.newProxyInstance(
                IChartbookCatalogMapper.class.getClassLoader(), new Class<?>[]{IChartbookCatalogMapper.class},
                (proxy, method, args) -> call.invoke(method.getName(), args == null ? new Object[0] : args));
    }

    @FunctionalInterface
    private interface Call {
        Object invoke(String method, Object[] args);
    }
}
