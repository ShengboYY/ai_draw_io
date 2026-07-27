package org.zipp.ai.api.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Result of a login attempt. {@code status} is one of:
 * {@code SUCCESS} · {@code INVALID_CREDENTIALS} · {@code NOT_VERIFIED} · {@code DISABLED}.
 * The user block is only populated for SUCCESS.
 */
@Data
@Builder
public class LoginResponseDTO {

    private String status;
    private String userId;
    private String email;
    private String accountStatus;
    private boolean admin;

}
