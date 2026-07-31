package org.zipp.ai.application.memory;

import java.util.List;

/** Read boundary kept separate from observation writes so Context cannot mutate Memory. */
public interface AutoMemoryQueryPort {
    List<AutoMemory> recallActive(AutoMemoryScope scope, int limit);
}
