package org.zipp.ai.application.memory;

import java.time.Instant;

/** Atomic persistence boundary for bounded, non-user-authored Memory maintenance. */
public interface AutoMemoryMaintenancePort {
    int purgeStaleObserved(Instant cutoffExclusive, int limit);
}
