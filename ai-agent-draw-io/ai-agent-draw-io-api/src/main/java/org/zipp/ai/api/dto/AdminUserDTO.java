package org.zipp.ai.api.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class AdminUserDTO {

    private String id;
    private String email;
    private String status;
    private Integer sessionVersion;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant verifiedAt;
}
