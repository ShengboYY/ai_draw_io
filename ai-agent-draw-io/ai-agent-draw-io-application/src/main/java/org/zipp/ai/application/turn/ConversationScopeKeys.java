package org.zipp.ai.application.turn;

import java.util.List;

/** Bounded read keys for the M3 compatibility resolver; writes use canonicalKey only. */
public record ConversationScopeKeys(String canonicalKey, List<String> boundedLegacyAliases) {

    public ConversationScopeKeys {
        ContractValues.requiredText(canonicalKey, "canonicalKey");
        boundedLegacyAliases = List.copyOf(boundedLegacyAliases == null ? List.of() : boundedLegacyAliases);
        boundedLegacyAliases.forEach(alias -> ContractValues.requiredText(alias, "boundedLegacyAlias"));
    }
}
