package org.zipp.ai.api.dto;

import lombok.Builder;
import lombok.Data;

/** Outcome of consuming a password-reset token. */
@Data
@Builder
public class PasswordResetConfirmResponseDTO {
    /** SUCCESS | EXPIRED | ALREADY_USED | INVALID */
    private String status;
}
