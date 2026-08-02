package org.zipp.ai.application.turn.context;

import org.zipp.ai.application.memory.AutoMemory;

import java.util.List;

/** Revalidates vector or pinned Memory identities against the MySQL authority. */
public interface AutoMemoryContextHydrationPort {

    List<AutoMemory> hydrateActiveVectorMatches(
            AutoMemoryContextQuery query,
            List<String> rankedVectorIds);

    List<AutoMemory> loadActive(
            AutoMemoryContextQuery query,
            List<String> memoryIds);
}
