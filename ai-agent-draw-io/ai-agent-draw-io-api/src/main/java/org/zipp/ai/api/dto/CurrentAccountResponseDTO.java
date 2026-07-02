package org.zipp.ai.api.dto;

import lombok.Data;

@Data
public class CurrentAccountResponseDTO {

    private String ownerId;
    private String ownerType;
    private boolean authenticated;
    private boolean emailVerified;
    private String accountStatus;

}
