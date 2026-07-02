package org.zipp.ai.domain.account.model.valobj;

import lombok.Builder;
import lombok.Data;

/** Raw login input. The service normalizes the email before lookup and never logs the raw password. */
@Data
@Builder
public class LoginAccountCommand {

    private String email;
    private String rawPassword;

}
