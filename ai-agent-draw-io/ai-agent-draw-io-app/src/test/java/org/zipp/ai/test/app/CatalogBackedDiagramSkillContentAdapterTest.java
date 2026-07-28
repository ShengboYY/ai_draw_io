package org.zipp.ai.test.app;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.skill.DiagramSkillBinding;
import org.zipp.ai.application.turn.skill.DiagramSkillCatalogSnapshot;
import org.zipp.ai.application.turn.skill.DiagramSkillSelectionSource;
import org.zipp.ai.application.turn.skill.ResolvedDiagramSkillSelection;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillCatalogService;
import org.zipp.ai.infrastructure.turn.skill.CatalogBackedDiagramSkillCatalogAdapter;
import org.zipp.ai.infrastructure.turn.skill.CatalogBackedDiagramSkillContentAdapter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogBackedDiagramSkillContentAdapterTest {

    @Test
    void loadsTheExactPinnedBodiesWhenTheSelectedSkillIsAlsoRequired() {
        SkillCatalogService catalog = catalog("## Rules [P0]\nUse stable ids.");
        DiagramSkillCatalogSnapshot snapshot =
                new CatalogBackedDiagramSkillCatalogAdapter(catalog).snapshot("owner-1");
        DiagramSkillBinding selected = snapshot.selectableSkills().get(0);
        ResolvedDiagramSkillSelection selection = new ResolvedDiagramSkillSelection(
                List.of(selected),
                List.of(
                        snapshot.sharedSkills().get(0),
                        snapshot.sharedSkills().get(1),
                        selected),
                DiagramSkillSelectionSource.ROUTER,
                snapshot.digest());

        var bundle = new CatalogBackedDiagramSkillContentAdapter(catalog)
                .load("owner-1", selection);

        assertThat(bundle.selectedSkills()).extracting("name").containsExactly("drawio-flowchart");
        assertThat(bundle.requiredSkills()).extracting("name")
                .containsExactly(
                        "drawio-xml-guide",
                        "drawio-visual-design",
                        "drawio-flowchart");
        assertThat(bundle.orderedSkills()).extracting("name")
                .containsExactly("drawio-xml-guide", "drawio-visual-design", "drawio-flowchart");
        assertThat(bundle.selectionBindingDigest()).isEqualTo(selection.bindingDigest());
    }

    @Test
    void rejectsExecutionWhenAPlannedSkillVersionHasChanged() {
        SkillCatalogService planningCatalog = catalog("## Rules [P0]\nUse stable ids.");
        DiagramSkillCatalogSnapshot snapshot =
                new CatalogBackedDiagramSkillCatalogAdapter(planningCatalog).snapshot("owner-1");
        ResolvedDiagramSkillSelection selection = new ResolvedDiagramSkillSelection(
                List.of(snapshot.selectableSkills().get(0)),
                snapshot.sharedSkills(),
                DiagramSkillSelectionSource.ROUTER,
                snapshot.digest());

        SkillCatalogService changedCatalog = catalog("## Rules [P0]\nUse different ids.");

        assertThatThrownBy(() -> new CatalogBackedDiagramSkillContentAdapter(changedCatalog)
                .load("owner-1", selection))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("V2_SKILL_SNAPSHOT_STALE");
    }

    private SkillCatalogService catalog(String selectedBody) {
        return new SkillCatalogService() {
            @Override
            public RuntimeCatalog runtimeCatalog(String ownerId) {
                return new RuntimeCatalog(
                        "- drawio-flowchart: Flow charts\n",
                        List.of(skill("drawio-flowchart", "flowchart", selectedBody)),
                        List.of(
                                skill("drawio-xml-guide", "shared", "## XML [P0]\nValid XML."),
                                skill("drawio-visual-design", "shared", "## Design [P0]\nReadable.")));
            }
        };
    }

    private SkillCatalogService.SkillInfo skill(String name, String diagramType, String body) {
        return new SkillCatalogService.SkillInfo(
                name,
                name,
                body,
                "",
                "drawio-design",
                diagramType,
                1,
                !"shared".equals(diagramType),
                SkillCatalogService.SkillSource.BUILT_IN,
                List.of());
    }
}
