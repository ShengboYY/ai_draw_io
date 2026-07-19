package org.zipp.ai.domain.chartbook;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.chartbook.model.aggregate.Chartbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChartbookTest {

    @Test
    void chartbookSharesMaterialByAssociationWithoutTakingOwnership() {
        Chartbook chartbook = Chartbook.create("book_1", OwnerType.USER, "usr_1", "Agile delivery");

        chartbook.addDiagram("dia_1");
        chartbook.shareMaterial("mat_1", "usr_1");
        chartbook.removeSharedMaterial("mat_1");

        assertEquals(1, chartbook.diagramIds().size());
        assertEquals(0, chartbook.sharedMaterialIds().size());
    }

    @Test
    void anonymousOwnerCannotCreateAChartbook() {
        assertThrows(IllegalArgumentException.class,
                () -> Chartbook.create("book_1", OwnerType.ANONYMOUS, "anon_1", "Agile delivery"));
    }
}
