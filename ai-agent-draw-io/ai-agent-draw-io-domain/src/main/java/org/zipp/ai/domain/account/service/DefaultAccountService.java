package org.zipp.ai.domain.account.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.account.adapter.port.IEmailSender;
import org.zipp.ai.domain.account.model.entity.AccountToken;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.account.model.valobj.AccountStatus;
import org.zipp.ai.domain.account.model.valobj.EmailNormalizer;
import org.zipp.ai.domain.account.model.valobj.EmailVerificationResult;
import org.zipp.ai.domain.account.model.valobj.RegisterAccountCommand;
import org.zipp.ai.domain.account.model.valobj.RegistrationResult;
import org.zipp.ai.domain.account.model.valobj.TokenPurpose;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Registration + email verification. Security invariants enforced here:
 * <ul>
 *   <li>Email is normalized before the uniqueness check.</li>
 *   <li>Passwords are stored only as hashes.</li>
 *   <li>New users start {@link AccountStatus#PENDING_VERIFICATION}.</li>
 *   <li>Only the token hash is persisted; tokens expire after 30 minutes and are single-use.</li>
 *   <li>Verification fails safely for invalid / expired / already-used tokens.</li>
 * </ul>
 */
@Service
public class DefaultAccountService implements IAccountService {

    /** Verification and reset tokens expire after 30 minutes (design decision). */
    static final Duration VERIFICATION_TTL = Duration.ofMinutes(30);
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final IUserAccountStore userAccountStore;
    private final IAccountTokenStore accountTokenStore;
    private final IPasswordHasher passwordHasher;
    private final ITokenHasher tokenHasher;
    private final ISecureTokenFactory tokenFactory;
    private final IEmailSender emailSender;
    private final String verificationBaseUrl;
    private final Clock clock;

    @Autowired
    public DefaultAccountService(IUserAccountStore userAccountStore,
                                 IAccountTokenStore accountTokenStore,
                                 IPasswordHasher passwordHasher,
                                 ITokenHasher tokenHasher,
                                 ISecureTokenFactory tokenFactory,
                                 IEmailSender emailSender,
                                 @Value("${account.verification.base-url:http://localhost:3000/verify-email}")
                                 String verificationBaseUrl) {
        this(userAccountStore, accountTokenStore, passwordHasher, tokenHasher, tokenFactory, emailSender,
                verificationBaseUrl, Clock.systemUTC());
    }

    /** Test seam: inject a fixed {@link Clock} to exercise token expiry deterministically. */
    public DefaultAccountService(IUserAccountStore userAccountStore,
                                 IAccountTokenStore accountTokenStore,
                                 IPasswordHasher passwordHasher,
                                 ITokenHasher tokenHasher,
                                 ISecureTokenFactory tokenFactory,
                                 IEmailSender emailSender,
                                 String verificationBaseUrl,
                                 Clock clock) {
        this.userAccountStore = userAccountStore;
        this.accountTokenStore = accountTokenStore;
        this.passwordHasher = passwordHasher;
        this.tokenHasher = tokenHasher;
        this.tokenFactory = tokenFactory;
        this.emailSender = emailSender;
        this.verificationBaseUrl = verificationBaseUrl;
        this.clock = clock;
    }

    @Override
    public RegistrationResult register(RegisterAccountCommand command) {
        String normalized = EmailNormalizer.normalize(command == null ? null : command.getEmail());
        if (normalized == null) {
            throw new IllegalArgumentException("A valid email address is required.");
        }
        String rawPassword = command.getRawPassword();
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }

        Optional<UserAccount> existing = userAccountStore.findByEmailNormalized(normalized);
        if (existing.isPresent()) {
            UserAccount user = existing.get();
            // Never reveal whether an email is taken; pending users simply get another link.
            if (user.isPendingVerification()) {
                issueVerificationToken(user.getId(), command.getEmail().trim());
                return RegistrationResult.builder()
                        .outcome(RegistrationResult.Outcome.RESENT_PENDING)
                        .userId(user.getId())
                        .build();
            }
            return RegistrationResult.builder()
                    .outcome(RegistrationResult.Outcome.ALREADY_REGISTERED)
                    .userId(user.getId())
                    .build();
        }

        Instant now = clock.instant();
        String userId = "usr_" + UUID.randomUUID();
        UserAccount account = UserAccount.builder()
                .id(userId)
                .email(command.getEmail().trim())
                .emailNormalized(normalized)
                .passwordHash(passwordHasher.hash(rawPassword))
                .status(AccountStatus.PENDING_VERIFICATION)
                .sessionVersion(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
        userAccountStore.insert(account);
        issueVerificationToken(userId, account.getEmail());

        return RegistrationResult.builder()
                .outcome(RegistrationResult.Outcome.CREATED)
                .userId(userId)
                .build();
    }

    @Override
    public EmailVerificationResult verifyEmail(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return EmailVerificationResult.INVALID;
        }
        Optional<AccountToken> match = accountTokenStore
                .findByHashAndPurpose(tokenHasher.hash(rawToken), TokenPurpose.EMAIL_VERIFY);
        if (match.isEmpty()) {
            return EmailVerificationResult.INVALID;
        }
        AccountToken token = match.get();
        if (token.isUsed()) {
            return EmailVerificationResult.ALREADY_USED;
        }
        Instant now = clock.instant();
        if (token.isExpiredAt(now)) {
            return EmailVerificationResult.EXPIRED;
        }
        // Consume first: if another concurrent request already used it, treat this one as reuse.
        if (!accountTokenStore.markUsed(token.getId(), now)) {
            return EmailVerificationResult.ALREADY_USED;
        }
        userAccountStore.markVerified(token.getUserId(), now);
        return EmailVerificationResult.SUCCESS;
    }

    @Override
    public void resendVerification(String email) {
        String normalized = EmailNormalizer.normalize(email);
        if (normalized == null) {
            return; // Fail safely / generically for invalid input.
        }
        userAccountStore.findByEmailNormalized(normalized)
                .filter(UserAccount::isPendingVerification)
                .ifPresent(user -> issueVerificationToken(user.getId(), email.trim()));
    }

    private void issueVerificationToken(String userId, String email) {
        Instant now = clock.instant();
        String rawToken = tokenFactory.newToken();
        AccountToken token = AccountToken.builder()
                .id("atk_" + UUID.randomUUID())
                .userId(userId)
                .purpose(TokenPurpose.EMAIL_VERIFY)
                .tokenHash(tokenHasher.hash(rawToken))
                .expiresAt(now.plus(VERIFICATION_TTL))
                .createdAt(now)
                .build();
        accountTokenStore.insert(token);
        emailSender.sendVerificationEmail(email, buildVerificationUrl(rawToken));
    }

    private String buildVerificationUrl(String rawToken) {
        String separator = verificationBaseUrl.contains("?") ? "&" : "?";
        return verificationBaseUrl + separator + "token=" + rawToken;
    }
}
