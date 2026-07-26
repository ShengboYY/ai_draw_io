package org.zipp.ai.application.turn;

import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Bounded read keys for the M3 compatibility resolver; writes use canonicalKey only. */
public record ConversationScopeKeys(String canonicalKey, List<String> boundedLegacyAliases) {

    public static final int MAX_LEGACY_ALIASES = 8;

    public ConversationScopeKeys {
        ContractValues.requiredText(canonicalKey, "canonicalKey");
        boundedLegacyAliases = List.copyOf(boundedLegacyAliases == null ? List.of() : boundedLegacyAliases);
        if (boundedLegacyAliases.size() > MAX_LEGACY_ALIASES) {
            throw new IllegalArgumentException("conversation legacy alias limit exceeded");
        }
        boundedLegacyAliases.forEach(alias -> ContractValues.requiredText(alias, "boundedLegacyAlias"));
        Set<String> unique = new HashSet<>(boundedLegacyAliases);
        if (unique.size() != boundedLegacyAliases.size() || unique.contains(canonicalKey)) {
            throw new IllegalArgumentException("conversation scope keys must be distinct");
        }
    }

    /** All keys are read-only compatibility keys; callers must use canonicalKey for writes. */
    public List<String> allKeys() {
        List<String> keys = new ArrayList<>(boundedLegacyAliases.size() + 1);
        keys.add(canonicalKey);
        keys.addAll(boundedLegacyAliases);
        return List.copyOf(keys);
    }
}
