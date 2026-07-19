package org.zipp.ai.domain.ingestion.model.valobj;

public record UploadRateReservation(int ownerHourlyLimit,
                                    int ipHourlyLimit,
                                    long accountByteLimit,
                                    int activeFileLimit) {
    public UploadRateReservation {
        if (ownerHourlyLimit < 1 || ipHourlyLimit < 1 || accountByteLimit < 1 || activeFileLimit < 1) {
            throw new IllegalArgumentException("upload reservation limits must be positive");
        }
    }
}
