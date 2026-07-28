package org.zipp.ai.application.turn.skill;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** One owner-scoped catalog read shared by router projection and deterministic validation. */
public record DiagramSkillCatalogSnapshot(
        boolean available,
        String routerPromptText,
        List<DiagramSkillBinding> selectableSkills,
        List<DiagramSkillBinding> sharedSkills,
        String digest
) {

    public DiagramSkillCatalogSnapshot {
        routerPromptText = routerPromptText == null ? "" : routerPromptText;
        selectableSkills = immutableUnique(selectableSkills, "selectableSkills");
        sharedSkills = immutableUnique(sharedSkills, "sharedSkills");
        Set<String> allNames = new HashSet<>();
        selectableSkills.forEach(skill -> allNames.add(skill.name()));
        for (DiagramSkillBinding shared : sharedSkills) {
            if (!allNames.add(shared.name())) {
                throw new IllegalArgumentException("skill catalog names must be unique");
            }
        }
        if (!hexDigest(digest)) {
            throw new IllegalArgumentException("skill catalog digest must be SHA-256");
        }
    }

    public static DiagramSkillCatalogSnapshot empty() {
        return new DiagramSkillCatalogSnapshot(
                false, "", List.of(), List.of(), "0".repeat(64));
    }

    public Optional<DiagramSkillBinding> selectable(String name) {
        return selectableSkills.stream().filter(skill -> skill.name().equals(name)).findFirst();
    }

    private static List<DiagramSkillBinding> immutableUnique(
            List<DiagramSkillBinding> values,
            String field
    ) {
        List<DiagramSkillBinding> result = List.copyOf(values == null ? List.of() : values);
        Set<String> names = new HashSet<>();
        for (DiagramSkillBinding value : result) {
            if (value == null || !names.add(value.name())) {
                throw new IllegalArgumentException(field + " must contain unique non-null skills");
            }
        }
        return result;
    }

    private static boolean hexDigest(String value) {
        return value != null && value.length() == 64 && value.chars().allMatch(character ->
                (character >= '0' && character <= '9')
                        || (character >= 'a' && character <= 'f'));
    }
}
