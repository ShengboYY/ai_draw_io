package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

import java.time.Instant;

@Data
public class MaterialProviderCapacityPO {
    private Instant capturedAt;
    private long sequence;
    private double embeddingPercent;
    private double vectorReadPercent;
    private double vectorWritePercent;
    private boolean dependenciesAvailable;
}
