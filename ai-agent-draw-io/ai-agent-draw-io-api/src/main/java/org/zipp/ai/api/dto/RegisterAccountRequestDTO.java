package org.zipp.ai.api.dto;

import lombok.Data;

@Data
public class RegisterAccountRequestDTO {
    private String email;
    private String password;
}
