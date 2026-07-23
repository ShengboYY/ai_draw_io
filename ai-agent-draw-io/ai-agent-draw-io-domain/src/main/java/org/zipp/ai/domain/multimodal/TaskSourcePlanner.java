package org.zipp.ai.domain.multimodal;

/** Converts trusted request facts into one immutable source execution plan. */
public interface TaskSourcePlanner {
    TaskSourcePlan plan(TaskSourcePlanningCommand command);
}
