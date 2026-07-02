package org.zipp.ai.domain.account.model.valobj;

import lombok.Builder;
import lombok.Data;

/** Raw registration input. The service normalizes the email and hashes the password. */
@Data
@Builder
public class RegisterAccountCommand {

    private String email;
    private String rawPassword;

}
