package org.zipp.ai.domain.account.model.valobj;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DemoQuotaSnapshot {

    private int limit;
    private int used;
    private int remaining;
    private boolean exhausted;

    public static DemoQuotaSnapshot of(int limit, int used) {
        int normalizedUsed = Math.max(0, used);
        int remaining = Math.max(0, limit - normalizedUsed);
        return DemoQuotaSnapshot.builder()
                .limit(limit)
                .used(normalizedUsed)
                .remaining(remaining)
                .exhausted(remaining <= 0)
                .build();
    }
}
