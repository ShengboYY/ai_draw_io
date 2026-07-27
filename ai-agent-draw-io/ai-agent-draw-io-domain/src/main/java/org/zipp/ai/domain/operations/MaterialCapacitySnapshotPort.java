package org.zipp.ai.domain.operations;

@FunctionalInterface
public interface MaterialCapacitySnapshotPort {
    MaterialCapacitySnapshot current();
}
