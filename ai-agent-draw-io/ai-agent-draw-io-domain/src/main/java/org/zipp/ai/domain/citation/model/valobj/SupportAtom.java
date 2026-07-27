package org.zipp.ai.domain.citation.model.valobj;

/** Exact display fragment proposed as support for one factual statement. */
public record SupportAtom(String atomKey, String citationKey, String anchorText, SupportAtomRole role) {
    public SupportAtom {
        atomKey = required(atomKey, "atomKey");
        citationKey = required(citationKey, "citationKey");
        anchorText = required(anchorText, "anchorText");
        if (anchorText.length() > 240) throw new IllegalArgumentException("anchorText exceeds 240 characters");
        if (role == null) throw new IllegalArgumentException("role is required");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
