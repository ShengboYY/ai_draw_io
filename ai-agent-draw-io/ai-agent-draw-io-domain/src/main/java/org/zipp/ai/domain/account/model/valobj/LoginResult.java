package org.zipp.ai.domain.account.model.valobj;

import lombok.Builder;
import lombok.Data;
import org.zipp.ai.domain.account.model.entity.UserAccount;

/**
 * Outcome of a login attempt. INVALID_CREDENTIALS is the generic bucket for both unknown emails and
 * wrong passwords so an attacker cannot enumerate accounts. NOT_VERIFIED and DISABLED are reported
 * distinctly because the frontend surfaces actionable hints (resend link / contact support).
 */
@Data
@Builder
public class LoginResult {

    public enum Outcome {
        SUCCESS,
        INVALID_CREDENTIALS,
        NOT_VERIFIED,
        DISABLED
    }

    private Outcome outcome;
    private UserAccount user;

    public static LoginResult of(Outcome outcome) {
        return LoginResult.builder().outcome(outcome).build();
    }

    public static LoginResult success(UserAccount user) {
        return LoginResult.builder().outcome(Outcome.SUCCESS).user(user).build();
    }

    public boolean isSuccess() {
        return outcome == Outcome.SUCCESS;
    }
}
