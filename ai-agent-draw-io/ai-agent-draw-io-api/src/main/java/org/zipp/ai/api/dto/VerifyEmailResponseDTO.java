package org.zipp.ai.api.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Outcome of an email-verification click. The frontend renders one of four states so users see a
 * clear success/expired/invalid/already-used page.
 */
@Data
@Builder
public class VerifyEmailResponseDTO {
    /** SUCCESS | EXPIRED | ALREADY_USED | INVALID */
    private String status;
}
