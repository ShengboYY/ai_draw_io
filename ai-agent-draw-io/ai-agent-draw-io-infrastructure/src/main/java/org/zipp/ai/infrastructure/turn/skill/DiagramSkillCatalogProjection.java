package org.zipp.ai.infrastructure.turn.skill;

import org.zipp.ai.application.turn.ModelInputBinding;
import org.zipp.ai.application.turn.skill.DiagramSkillBinding;
import org.zipp.ai.application.turn.skill.DiagramSkillCatalogSnapshot;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillCatalogService;

import java.util.List;

/** Keeps V2 catalog identity calculation identical for planning and execution-time loading. */
final class DiagramSkillCatalogProjection {

    private DiagramSkillCatalogProjection() {
    }

    static DiagramSkillCatalogSnapshot snapshot(SkillCatalogService.RuntimeCatalog runtime) {
        List<DiagramSkillBinding> selectable = runtime.selectableSkills().stream()
                .map(DiagramSkillCatalogProjection::binding)
                .toList();
        List<DiagramSkillBinding> shared = runtime.sharedSkills().stream()
                .map(DiagramSkillCatalogProjection::binding)
                .toList();
        String digest = ModelInputBinding.digestOf(
                runtime.promptText(),
                canonical(selectable),
                canonical(shared));
        return new DiagramSkillCatalogSnapshot(
                true, runtime.promptText(), selectable, shared, digest);
    }

    static DiagramSkillBinding binding(SkillCatalogService.SkillInfo skill) {
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

    private static String canonical(List<DiagramSkillBinding> values) {
        return values.stream()
                .map(value -> value.name() + ":" + value.diagramType() + ":" + value.contentDigest())
                .toList()
                .toString();
    }
}
