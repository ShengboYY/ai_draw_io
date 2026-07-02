package org.zipp.ai.domain.account.service;

import org.zipp.ai.domain.account.model.valobj.PlatformDailyQuotaSnapshot;
import org.zipp.ai.types.enums.ResponseCode;
import org.zipp.ai.types.exception.AppException;

public class PlatformDailyQuotaExceededException extends AppException {

    private final PlatformDailyQuotaSnapshot quota;

    public PlatformDailyQuotaExceededException(PlatformDailyQuotaSnapshot quota) {
        super(ResponseCode.PLATFORM_QUOTA_EXHAUSTED.getCode(), ResponseCode.PLATFORM_QUOTA_EXHAUSTED.getInfo());
        this.quota = quota;
    }

    public PlatformDailyQuotaSnapshot getQuota() {
        return quota;
    }
}
