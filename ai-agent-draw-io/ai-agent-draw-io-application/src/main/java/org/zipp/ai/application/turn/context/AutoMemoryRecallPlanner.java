package org.zipp.ai.application.turn.context;

import java.util.List;

/** Produces optional, self-contained semantic subqueries for one Memory context read. */
@FunctionalInterface
public interface AutoMemoryRecallPlanner {
    int MAX_SUBQUERIES = 3;

    AutoMemoryRecallPlanner NONE = query -> List.of();

    List<String> plan(AutoMemoryContextQuery query);
}
