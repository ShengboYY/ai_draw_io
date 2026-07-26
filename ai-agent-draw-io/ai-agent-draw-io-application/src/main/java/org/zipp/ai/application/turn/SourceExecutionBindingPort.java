package org.zipp.ai.application.turn;

/** Persists the exact plan/snapshot/entry tuple produced by source freeze. */
@FunctionalInterface
public interface SourceExecutionBindingPort {

    SourceExecutionBindingOutcome pin(FencedAttempt attempt, SourceCommitBinding binding);
}
