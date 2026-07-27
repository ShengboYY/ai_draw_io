package org.zipp.ai.application.turn;

public interface LegacyRetryExpiryPort {

    /** Fills retry-horizon fields for legacy rows created before the M1 writer existed. */
    int backfillRetryable(int batchSize);

    int expireDue(int batchSize);
}
