package org.zipp.ai.domain.operations;

public record CapacityDecision(boolean allowed, CapacityLevel level, String reasonCode) { }
