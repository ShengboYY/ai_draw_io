package org.zipp.ai.test.app;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillCatalogService;
import org.zipp.ai.infrastructure.turn.skill.CatalogBackedDiagramSkillCatalogAdapter;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogBackedDiagramSkillCatalogAdapterTest {

    @Test
    void mapsOneOwnerScopedLegacyCatalogReadIntoAV2Snapshot() {
        AtomicInteger reads = new AtomicInteger();
        SkillCatalogService catalog = new SkillCatalogService() {
            @Override
            public RuntimeCatalog runtimeCatalog(String ownerId) {
                reads.incrementAndGet();
                return new RuntimeCatalog(
                        "- custom-flow: Custom flow\n",
                        List.of(skill("custom-flow", "flowchart", "selected body")),
                        List.of(
                                skill("drawio-xml-guide", "shared", "xml rules"),
                                skill("drawio-visual-design", "shared", "visual rules")));
            }
        };

        var snapshot = new CatalogBackedDiagramSkillCatalogAdapter(catalog).snapshot("owner-1");

        assertTrue(snapshot.available());
        assertEquals(1, reads.get());
        assertEquals(List.of("custom-flow"), snapshot.selectableSkills().stream()
                .map(value -> value.name()).toList());
        assertEquals(List.of("drawio-xml-guide", "drawio-visual-design"),
                snapshot.sharedSkills().stream().map(value -> value.name()).toList());
        assertTrue(snapshot.digest().matches("[0-9a-f]{64}"));
    }

    private SkillCatalogService.SkillInfo skill(String name, String diagramType, String body) {
        return new SkillCatalogService.SkillInfo(
                name,
                name + " description",
                body,
                "",
                "drawio-design",
                diagramType,
                1,
                true,
                SkillCatalogService.SkillSource.BUILT_IN,
                List.of());
    }
}
