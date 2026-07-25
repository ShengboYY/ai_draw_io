package org.zipp.ai.application.turn;

import java.util.Set;

/**
 * Fixed capability registry for Plain execution.
 *
 * <p>There is deliberately no source or memory-management capability enum here. A model
 * cannot obtain those tools through a prompt or a dynamic registry.</p>
 */
public final class PlainRuntimeRegistry {

    private static final Set<PlainRuntimeCapability> SOURCE_FREE_CAPABILITIES =
            Set.of(PlainRuntimeCapability.PLAIN_GENERATION);

    public Set<PlainRuntimeCapability> capabilities() {
        return SOURCE_FREE_CAPABILITIES;
    }

    public boolean isSourceFree() {
        return SOURCE_FREE_CAPABILITIES.size() == 1
                && SOURCE_FREE_CAPABILITIES.contains(PlainRuntimeCapability.PLAIN_GENERATION);
    }
}
