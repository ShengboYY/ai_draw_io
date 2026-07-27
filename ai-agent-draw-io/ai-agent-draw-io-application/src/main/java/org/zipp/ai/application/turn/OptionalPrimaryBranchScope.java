package org.zipp.ai.application.turn;

/**
 * Branch-local grounded state. It must be discarded and closed before a Plain fallback starts.
 */
public interface OptionalPrimaryBranchScope extends AutoCloseable {

    /** No primary capability exists when preparation fails before handler dispatch. */
    static OptionalPrimaryBranchScope none() {
        return NoPrimaryBranchScope.INSTANCE;
    }

    void discard();

    @Override
    void close();

    enum NoPrimaryBranchScope implements OptionalPrimaryBranchScope {
        INSTANCE;

        @Override
        public void discard() {
            // Nothing was prepared, so there is no capability to revoke.
        }

        @Override
        public void close() {
            // Nothing was prepared, so there is no resource to close.
        }
    }
}
