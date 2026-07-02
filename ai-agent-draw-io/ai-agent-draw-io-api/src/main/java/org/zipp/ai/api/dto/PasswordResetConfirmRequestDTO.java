package org.zipp.ai.api.dto;

import lombok.Data;

@Data
public class PasswordResetConfirmRequestDTO {
    private String token;
    private String password;
}
