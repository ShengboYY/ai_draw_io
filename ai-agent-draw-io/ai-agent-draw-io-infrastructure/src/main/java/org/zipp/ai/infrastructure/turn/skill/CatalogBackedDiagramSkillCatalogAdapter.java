package org.zipp.ai.infrastructure.turn.skill;

import org.springframework.stereotype.Component;
import org.zipp.ai.application.turn.ModelInputBinding;
import org.zipp.ai.application.turn.skill.DiagramSkillBinding;
import org.zipp.ai.application.turn.skill.DiagramSkillCatalogPort;
import org.zipp.ai.application.turn.skill.DiagramSkillCatalogSnapshot;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillCatalogService;

import java.util.List;

/** Reuses the legacy catalog registry without exposing its MCP/session-bound loading tool to V2. */
@Component
public final class CatalogBackedDiagramSkillCatalogAdapter implements DiagramSkillCatalogPort {

    private final SkillCatalogService catalog;

    public CatalogBackedDiagramSkillCatalogAdapter(SkillCatalogService catalog) {
        this.catalog = catalog;
    }

    @Override
    public DiagramSkillCatalogSnapshot snapshot(String ownerKey) {
        SkillCatalogService.RuntimeCatalog runtime = catalog.runtimeCatalog(ownerKey);
        List<DiagramSkillBinding> selectable = runtime.selectableSkills().stream()
                .map(this::binding)
                .toList();
        List<DiagramSkillBinding> shared = runtime.sharedSkills().stream()
                .map(this::binding)
                .toList();
        String digest = ModelInputBinding.digestOf(
                runtime.promptText(),
                canonical(selectable),
                canonical(shared));
        return new DiagramSkillCatalogSnapshot(
                true, runtime.promptText(), selectable, shared, digest);
    }

    private DiagramSkillBinding binding(SkillCatalogService.SkillInfo skill) {
        String contentDigest = ModelInputBinding.digestOf(
                skill.name(),
                skill.body(),
                skill.referenceBody(),
                Integer.toString(skill.schemaVersion()));
        return new DiagramSkillBinding(
                skill.name(),
                skill.diagramType().isBlank() ? "unknown" : skill.diagramType(),
                contentDigest);
    }

    private String canonical(List<DiagramSkillBinding> values) {
        return values.stream()
                .map(value -> value.name() + ":" + value.diagramType() + ":" + value.contentDigest())
                .toList()
                .toString();
    }
}
