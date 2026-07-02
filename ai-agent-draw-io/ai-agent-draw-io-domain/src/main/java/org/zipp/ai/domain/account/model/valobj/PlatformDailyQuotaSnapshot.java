package org.zipp.ai.domain.account.model.valobj;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data
@Builder
public class PlatformDailyQuotaSnapshot {

    private int limit;
    private int used;
    private int remaining;
    private boolean exhausted;
    private String quotaDate;

    public static PlatformDailyQuotaSnapshot of(int limit, int used, LocalDate quotaDate) {
        int normalizedUsed = Math.max(0, used);
        int remaining = Math.max(0, limit - normalizedUsed);
        return PlatformDailyQuotaSnapshot.builder()
                .limit(limit)
                .used(normalizedUsed)
                .remaining(remaining)
                .exhausted(remaining <= 0)
                .quotaDate(quotaDate == null ? null : quotaDate.toString())
                .build();
    }
}
