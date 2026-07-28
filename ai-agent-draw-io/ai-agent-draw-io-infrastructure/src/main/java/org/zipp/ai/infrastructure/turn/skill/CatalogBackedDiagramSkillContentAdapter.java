package org.zipp.ai.infrastructure.turn.skill;

import org.springframework.stereotype.Component;
import org.zipp.ai.application.turn.skill.DiagramSkillBinding;
import org.zipp.ai.application.turn.skill.DiagramSkillBundle;
import org.zipp.ai.application.turn.skill.DiagramSkillCatalogSnapshot;
import org.zipp.ai.application.turn.skill.DiagramSkillContentPort;
import org.zipp.ai.application.turn.skill.LoadedDiagramSkill;
import org.zipp.ai.application.turn.skill.ResolvedDiagramSkillSelection;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillCatalogService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads bounded skill bodies from one owner-visible catalog read and verifies the planning pin. */
@Component
public final class CatalogBackedDiagramSkillContentAdapter implements DiagramSkillContentPort {

    private static final int MAX_SKILL_BODY_CHARS = 15_000;
    private static final String TRUNCATED_MARKER = "\n...[truncated]";

    private final SkillCatalogService catalog;

    public CatalogBackedDiagramSkillContentAdapter(SkillCatalogService catalog) {
        this.catalog = catalog;
    }

    @Override
    public DiagramSkillBundle load(
            String ownerKey,
            ResolvedDiagramSkillSelection selection
    ) {
        if (ownerKey == null || ownerKey.isBlank() || selection == null) {
            throw new IllegalArgumentException("skill load owner and selection are required");
        }
        if (selection.selectedSkills().isEmpty() && selection.requiredSkills().isEmpty()) {
            return DiagramSkillBundle.empty();
        }

        // A single catalog read prevents selected and required skills from coming from different
        // refresh generations when dynamic skills are updated during execution.
        SkillCatalogService.RuntimeCatalog runtime = catalog.runtimeCatalog(ownerKey);
        DiagramSkillCatalogSnapshot snapshot = DiagramSkillCatalogProjection.snapshot(runtime);
        if (!snapshot.digest().equals(selection.catalogDigest())) {
            throw new IllegalStateException("V2_SKILL_SNAPSHOT_STALE");
        }

        Map<String, SkillCatalogService.SkillInfo> visible = new LinkedHashMap<>();
        runtime.selectableSkills().forEach(skill -> visible.put(skill.name(), skill));
        runtime.sharedSkills().forEach(skill -> visible.put(skill.name(), skill));
        List<LoadedDiagramSkill> selected = loadBound(selection.selectedSkills(), visible);
        List<LoadedDiagramSkill> required = loadBound(selection.requiredSkills(), visible);
        return new DiagramSkillBundle(selected, required, selection.bindingDigest());
    }

    private List<LoadedDiagramSkill> loadBound(
            List<DiagramSkillBinding> bindings,
            Map<String, SkillCatalogService.SkillInfo> visible
    ) {
        return bindings.stream().map(binding -> {
            SkillCatalogService.SkillInfo skill = visible.get(binding.name());
            if (skill == null || skill.body().isBlank()) {
                throw new IllegalStateException("V2_REQUIRED_SKILL_UNAVAILABLE");
            }
            DiagramSkillBinding current = DiagramSkillCatalogProjection.binding(skill);
            if (!current.equals(binding)) {
                throw new IllegalStateException("V2_SKILL_SNAPSHOT_STALE");
            }
            String body = boundedBody(skill.body());
            return new LoadedDiagramSkill(
                    current.name(),
                    current.diagramType(),
                    current.contentDigest(),
                    body,
                    body.length() < skill.body().trim().length());
        }).toList();
    }

    private String boundedBody(String value) {
        String body = value.trim();
        if (body.length() <= MAX_SKILL_BODY_CHARS) {
            return body;
        }
        int contentLimit = MAX_SKILL_BODY_CHARS - TRUNCATED_MARKER.length();
        return body.substring(0, contentLimit).trim() + TRUNCATED_MARKER;
    }
}
