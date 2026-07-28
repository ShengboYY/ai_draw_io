package org.zipp.ai.application.turn.skill;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        Set<String> selectedNames = new HashSet<>();
        for (LoadedDiagramSkill skill : selectedSkills) {
            if (skill == null || !selectedNames.add(skill.name())) {
                throw new IllegalArgumentException("selected skill bundle names must be unique");
            }
        }
        Set<String> requiredNames = new HashSet<>();
        for (LoadedDiagramSkill skill : requiredSkills) {
            if (skill == null || !requiredNames.add(skill.name())) {
                throw new IllegalArgumentException("required skill bundle names must be unique");
            }
            LoadedDiagramSkill selected = selectedSkills.stream()
                    .filter(candidate -> candidate.name().equals(skill.name()))
                    .findFirst()
                    .orElse(null);
            if (selected != null && !selected.contentDigest().equals(skill.contentDigest())) {
                throw new IllegalArgumentException(
                        "overlapping skill roles must pin the same version");
            }
        }
    }

    public static DiagramSkillBundle empty() {
        return new DiagramSkillBundle(List.of(), List.of(), ResolvedDiagramSkillSelection.empty().bindingDigest());
    }

    public List<LoadedDiagramSkill> orderedSkills() {
        // One skill may be both model-selected and runtime-required; render its body only once.
        Map<String, LoadedDiagramSkill> ordered = new LinkedHashMap<>();
        requiredSkills.forEach(skill -> ordered.putIfAbsent(skill.name(), skill));
        selectedSkills.forEach(skill -> ordered.putIfAbsent(skill.name(), skill));
        return List.copyOf(ordered.values());
    }
}
