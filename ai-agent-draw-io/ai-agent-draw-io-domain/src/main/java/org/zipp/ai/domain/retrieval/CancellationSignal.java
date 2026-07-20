package org.zipp.ai.domain.retrieval;

@FunctionalInterface
public interface CancellationSignal {
    CancellationSignal NEVER = () -> false;
    boolean isCancelled();
}
