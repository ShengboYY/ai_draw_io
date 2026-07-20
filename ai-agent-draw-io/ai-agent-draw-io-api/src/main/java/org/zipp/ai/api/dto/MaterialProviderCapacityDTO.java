package org.zipp.ai.api.dto;

/** Content-free live provider utilization sample pushed by the billing exporter. */
public record MaterialProviderCapacityDTO(java.time.Instant capturedAt, long sequence,
                                          double embeddingPercent, double vectorReadPercent,
                                          double vectorWritePercent, boolean dependenciesAvailable) { }
