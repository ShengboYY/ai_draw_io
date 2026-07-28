package org.zipp.ai.application.turn.skill;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable attempt input containing the exact skill versions pinned by the Plain plan. */
public record DiagramSkillBundle(
        List<LoadedDiagramSkill> selectedSkills,
        List<LoadedDiagramSkill> requiredSkills,
        String selectionBindingDigest
) {

    public DiagramSkillBundle {
        selectedSkills = List.copyOf(selectedSkills == null ? List.of() : selectedSkills);
        requiredSkills = List.copyOf(requiredSkills == null ? List.of() : requiredSkills);
        if (selectionBindingDigest == null || selectionBindingDigest.length() != 64) {
            throw new IllegalArgumentException("selectionBindingDigest must be SHA-256");
        }
        Set<String> names = new HashSet<>();
        for (LoadedDiagramSkill skill : selectedSkills) {
            if (skill == null || !names.add(skill.name())) {
                throw new IllegalArgumentException("skill bundle names must be unique");
            }
        }
        for (LoadedDiagramSkill skill : requiredSkills) {
            if (skill == null || !names.add(skill.name())) {
                throw new IllegalArgumentException("skill bundle names must be unique");
            }
        }
    }

    public static DiagramSkillBundle empty() {
        return new DiagramSkillBundle(List.of(), List.of(), ResolvedDiagramSkillSelection.empty().bindingDigest());
    }

    public List<LoadedDiagramSkill> orderedSkills() {
        java.util.ArrayList<LoadedDiagramSkill> ordered = new java.util.ArrayList<>(requiredSkills);
        ordered.addAll(selectedSkills);
        return List.copyOf(ordered);
    }
}
