package org.zipp.ai.domain.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The single run-scoped owner for leases and other external resources. */
public final class RunResourceDomain implements AutoCloseable {
    private final List<AutoCloseable> resources = new ArrayList<>();
    private RunResourceState state = RunResourceState.OPEN;
    private CloseReason closeReason;

    public void attach(AutoCloseable resource) {
        if (resource == null) return;
        synchronized (this) {
            if (closeReason == null) {
                resources.add(resource);
                return;
            }
        }
        closeQuietly(resource);
    }

    public void closeExactlyOnce(CloseReason reason) {
        List<AutoCloseable> closing;
        synchronized (this) {
            if (closeReason != null) return;
            closeReason = reason == null ? CloseReason.FAILED : reason;
            state = RunResourceState.CLOSED;
            closing = new ArrayList<>(resources);
            resources.clear();
        }
        // Close in reverse acquisition order, like a structured resource stack.
        for (int index = closing.size() - 1; index >= 0; index--) {
            closeQuietly(closing.get(index));
        }
    }

    public synchronized boolean isClosed() {
        return state == RunResourceState.CLOSED;
    }

    public synchronized RunResourceState state() {
        return state;
    }

    public synchronized void markPrepared() {
        if (state != RunResourceState.OPEN) {
            throw new IllegalStateException("run resources must be OPEN before preparation completes");
        }
        state = RunResourceState.PREPARED;
    }

    public synchronized void beginCommit() {
        if (state != RunResourceState.PREPARED) {
            throw new IllegalStateException("run resources must be PREPARED before commit");
        }
        state = RunResourceState.COMMITTING;
    }

    public synchronized Optional<CloseReason> closeReason() {
        return Optional.ofNullable(closeReason);
    }

    @Override
    public void close() {
        closeExactlyOnce(CloseReason.COMPLETED);
    }

    private void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception ignored) {
            // Cleanup is best effort; the first close reason remains the auditable terminal state.
        }
    }
}
