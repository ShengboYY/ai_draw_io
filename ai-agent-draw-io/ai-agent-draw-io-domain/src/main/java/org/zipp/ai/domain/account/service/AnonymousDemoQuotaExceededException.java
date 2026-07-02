package org.zipp.ai.domain.account.service;

import org.zipp.ai.domain.account.model.valobj.DemoQuotaSnapshot;
import org.zipp.ai.types.enums.ResponseCode;
import org.zipp.ai.types.exception.AppException;

public class AnonymousDemoQuotaExceededException extends AppException {

    private final DemoQuotaSnapshot quota;

    public AnonymousDemoQuotaExceededException(DemoQuotaSnapshot quota) {
        super(ResponseCode.DEMO_QUOTA_EXHAUSTED.getCode(), ResponseCode.DEMO_QUOTA_EXHAUSTED.getInfo());
        this.quota = quota;
    }

    public DemoQuotaSnapshot getQuota() {
        return quota;
    }
}
