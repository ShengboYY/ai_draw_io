package org.zipp.ai.api.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Generic registration response. Same shape for every outcome so callers cannot use the response to
 * enumerate existing accounts.
 */
@Data
@Builder
public class RegisterAccountResponseDTO {
    /** Always {@code true} on a successful HTTP call, regardless of whether an email was actually sent. */
    private boolean submitted;
}
