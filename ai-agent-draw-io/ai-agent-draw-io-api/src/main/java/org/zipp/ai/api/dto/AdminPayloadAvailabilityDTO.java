package org.zipp.ai.api.dto;

import lombok.Data;

@Data
public class AdminPayloadAvailabilityDTO {

    private Boolean onDemand;
    private String status;
    private String note;
}
