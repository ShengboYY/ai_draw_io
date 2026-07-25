package org.zipp.ai.application.turn;

public interface LegacyRetryExpiryPort {

    int expireDue(int batchSize);
}
