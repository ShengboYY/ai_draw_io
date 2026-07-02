package org.zipp.ai.domain.account.service;

import org.zipp.ai.types.enums.ResponseCode;
import org.zipp.ai.types.exception.AppException;

public class RateLimitExceededException extends AppException {

    public RateLimitExceededException(String message) {
        super(ResponseCode.AUTH_RATE_LIMITED.getCode(), message);
    }
}
