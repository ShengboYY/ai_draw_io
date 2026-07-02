package org.zipp.ai.api.dto;

import lombok.Data;

@Data
public class CurrentAccountResponseDTO {

    private String ownerId;
    private String ownerType;
    private boolean authenticated;
    private boolean emailVerified;
    private String accountStatus;
    private Integer demoQuotaLimit;
    private Integer demoQuotaUsed;
    private Integer demoQuotaRemaining;
    private Boolean demoQuotaExhausted;
    private Integer platformDailyQuotaLimit;
    private Integer platformDailyQuotaUsed;
    private Integer platformDailyQuotaRemaining;
    private Boolean platformDailyQuotaExhausted;
    private String platformDailyQuotaDate;

}
