package org.zipp.ai.domain.operations;

@FunctionalInterface
public interface MaterialOperationsSnapshotPort {
    MaterialOperationalSnapshot current();
}
