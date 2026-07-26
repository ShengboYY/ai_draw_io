package org.zipp.ai.application.turn;

/**
 * Branch-local grounded state. It must be discarded and closed before a Plain fallback starts.
 */
public interface OptionalPrimaryBranchScope extends AutoCloseable {

    void discard();

    @Override
    void close();
}
