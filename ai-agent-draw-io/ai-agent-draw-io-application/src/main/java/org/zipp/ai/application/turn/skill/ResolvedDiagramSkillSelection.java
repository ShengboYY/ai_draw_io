package org.zipp.ai.application.turn.skill;

import org.zipp.ai.application.turn.ModelInputBinding;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministically validated skill selection; model output alone cannot create this value. */
public record ResolvedDiagramSkillSelection(
        List<DiagramSkillBinding> selectedSkills,
        List<DiagramSkillBinding> requiredSkills,
        DiagramSkillSelectionSource source,
        String catalogDigest
) {

    public ResolvedDiagramSkillSelection {
        selectedSkills = immutableUnique(selectedSkills, "selectedSkills");
        requiredSkills = immutableUnique(requiredSkills, "requiredSkills");
        if (source == null || !hexDigest(catalogDigest)) {
            throw new IllegalArgumentException("resolved skill selection values are invalid");
        }
    }

    public static ResolvedDiagramSkillSelection empty() {
        return new ResolvedDiagramSkillSelection(
                List.of(), List.of(), DiagramSkillSelectionSource.NONE, "0".repeat(64));
    }

    public String bindingDigest() {
        String selected = selectedSkills.stream()
                .map(skill -> skill.name() + ":" + skill.contentDigest())
                .toList().toString();
        String required = requiredSkills.stream()
                .map(skill -> skill.name() + ":" + skill.contentDigest())
                .toList().toString();
        return ModelInputBinding.digestOf(source.name(), catalogDigest, selected, required);
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
