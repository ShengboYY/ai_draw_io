package org.zipp.ai.test.domain.account;

import org.junit.Before;
import org.junit.Test;
import org.zipp.ai.domain.account.adapter.port.IEmailSender;
import org.zipp.ai.domain.account.model.entity.AccountToken;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.account.model.valobj.AccountStatus;
import org.zipp.ai.domain.account.model.valobj.EmailVerificationResult;
import org.zipp.ai.domain.account.model.valobj.LoginAccountCommand;
import org.zipp.ai.domain.account.model.valobj.LoginResult;
import org.zipp.ai.domain.account.model.valobj.PasswordResetResult;
import org.zipp.ai.domain.account.model.valobj.RegisterAccountCommand;
import org.zipp.ai.domain.account.model.valobj.RegistrationResult;
import org.zipp.ai.domain.account.model.valobj.TokenPurpose;
import org.zipp.ai.domain.account.service.DefaultAccountService;
import org.zipp.ai.domain.account.service.IAccountService;
import org.zipp.ai.domain.account.service.IAccountTokenStore;
import org.zipp.ai.domain.account.service.IPasswordHasher;
import org.zipp.ai.domain.account.service.ISecureTokenFactory;
import org.zipp.ai.domain.account.service.ITokenHasher;
import org.zipp.ai.domain.account.service.IUserAccountStore;
import org.zipp.ai.domain.account.service.RateLimitExceededException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DefaultAccountServiceTest {

    private static final String BASE_URL = "http://localhost:3000/verify-email";
    private static final String RESET_BASE_URL = "http://localhost:3000/reset-password/confirm";

    private FakeUserAccountStore users;
    private FakeAccountTokenStore tokens;
    private FakePasswordHasher passwordHasher;
    private FakeEmailSender emailSender;
    private MutableClock clock;
    private IAccountService service;

    @Before
    public void setUp() {
        users = new FakeUserAccountStore();
        tokens = new FakeAccountTokenStore();
        passwordHasher = new FakePasswordHasher();
        emailSender = new FakeEmailSender();
        clock = new MutableClock(Instant.parse("2026-07-02T10:00:00Z"));
        service = new DefaultAccountService(users, tokens, passwordHasher,
                new PrefixTokenHasher(), new SequentialTokenFactory(), emailSender, BASE_URL, RESET_BASE_URL, clock);
    }

    @Test
    public void registerCreatesPendingUserWithHashedPasswordAndSendsEmail() {
        RegistrationResult result = service.register(RegisterAccountCommand.builder()
                .email("Alice@Example.com").rawPassword("hunter2secret").build());

        assertEquals(RegistrationResult.Outcome.CREATED, result.getOutcome());
        UserAccount stored = users.findById(result.getUserId()).orElseThrow();
        assertEquals(AccountStatus.PENDING_VERIFICATION, stored.getStatus());
        assertNull("verified_at must be null before verification", stored.getVerifiedAt());
        // Password is stored only as a hash, never plaintext.
        assertFalse(stored.getPasswordHash().contains("hunter2secret"));
        assertTrue(passwordHasher.matches("hunter2secret", stored.getPasswordHash()));
        assertEquals(1, emailSender.verificationEmails.size());
        assertEquals("Alice@Example.com", emailSender.verificationEmails.get(0).email);
    }

    @Test
    public void registerNormalizesEmailBeforeUniquenessCheck() {
        service.register(RegisterAccountCommand.builder()
                .email("Bob@Example.com").rawPassword("password123").build());
        clock.advance(Duration.ofSeconds(61));
        RegistrationResult second = service.register(RegisterAccountCommand.builder()
                .email("  bob@example.COM ").rawPassword("password123").build());

        // Same normalized email => no second user created.
        assertEquals(1, users.count());
        assertFalse(RegistrationResult.Outcome.CREATED.equals(second.getOutcome()));
    }

    @Test
    public void verificationTokenIsStoredHashedWithThirtyMinuteExpiry() {
        service.register(RegisterAccountCommand.builder()
                .email("carol@example.com").rawPassword("password123").build());

        AccountToken token = tokens.only();
        String rawToken = emailSender.lastToken();
        assertEquals(TokenPurpose.EMAIL_VERIFY, token.getPurpose());
        // Only the hash is stored, never the raw token.
        assertFalse(rawToken.equals(token.getTokenHash()));
        assertEquals(new PrefixTokenHasher().hash(rawToken), token.getTokenHash());
        assertEquals(clock.instant().plus(Duration.ofMinutes(30)), token.getExpiresAt());
        assertNull(token.getUsedAt());
    }

    @Test
    public void verifyEmailActivatesUserAndConsumesToken() {
        RegistrationResult reg = service.register(RegisterAccountCommand.builder()
                .email("dave@example.com").rawPassword("password123").build());

        EmailVerificationResult result = service.verifyEmail(emailSender.lastToken());

        assertEquals(EmailVerificationResult.SUCCESS, result);
        UserAccount user = users.findById(reg.getUserId()).orElseThrow();
        assertEquals(AccountStatus.ACTIVE, user.getStatus());
        assertNotNull(user.getVerifiedAt());
        assertNotNull(tokens.only().getUsedAt());
    }

    @Test
    public void verifyEmailRejectsExpiredToken() {
        service.register(RegisterAccountCommand.builder()
                .email("erin@example.com").rawPassword("password123").build());
        String rawToken = emailSender.lastToken();

        clock.advance(Duration.ofMinutes(31));
        EmailVerificationResult result = service.verifyEmail(rawToken);

        assertEquals(EmailVerificationResult.EXPIRED, result);
        assertFalse(users.findByEmailNormalized("erin@example.com").orElseThrow().isActive());
    }

    @Test
    public void verifyEmailRejectsReusedToken() {
        service.register(RegisterAccountCommand.builder()
                .email("frank@example.com").rawPassword("password123").build());
        String rawToken = emailSender.lastToken();

        assertEquals(EmailVerificationResult.SUCCESS, service.verifyEmail(rawToken));
        assertEquals(EmailVerificationResult.ALREADY_USED, service.verifyEmail(rawToken));
    }

    @Test
    public void verifyEmailRejectsUnknownToken() {
        assertEquals(EmailVerificationResult.INVALID, service.verifyEmail("does-not-exist"));
        assertEquals(EmailVerificationResult.INVALID, service.verifyEmail(""));
    }

    @Test
    public void verificationEmailSendLimitIsSharedByRegistrationAndResend() {
        service.register(RegisterAccountCommand.builder()
                .email("send-limit@example.com").rawPassword("password123").build());

        assertRateLimited(() -> service.resendVerification("send-limit@example.com"));
        assertEquals(1, emailSender.verificationEmails.size());

        clock.advance(Duration.ofSeconds(61));
        service.resendVerification("Send-Limit@Example.com");
        assertEquals(2, emailSender.verificationEmails.size());
    }

    @Test
    public void verificationEmailDailyLimitResetsAfterRollingDay() {
        service.register(RegisterAccountCommand.builder()
                .email("daily-verify@example.com").rawPassword("password123").build());

        for (int i = 0; i < 4; i++) {
            clock.advance(Duration.ofSeconds(61));
            service.resendVerification("daily-verify@example.com");
        }

        assertEquals(5, emailSender.verificationEmails.size());
        clock.advance(Duration.ofSeconds(61));
        assertRateLimited(() -> service.resendVerification("daily-verify@example.com"));

        clock.advance(Duration.ofDays(1));
        service.resendVerification("daily-verify@example.com");
        assertEquals(6, emailSender.verificationEmails.size());
    }

    @Test
    public void loginSucceedsForVerifiedActiveUser() {
        registerAndVerify("gina@example.com", "password123");

        LoginResult result = service.login(LoginAccountCommand.builder()
                .email("  Gina@Example.COM ").rawPassword("password123").build());

        assertEquals(LoginResult.Outcome.SUCCESS, result.getOutcome());
        assertNotNull(result.getUser());
        assertEquals(AccountStatus.ACTIVE, result.getUser().getStatus());
        assertEquals("gina@example.com", result.getUser().getEmailNormalized());
    }

    @Test
    public void loginRejectsUnverifiedUserWithNotVerified() {
        service.register(RegisterAccountCommand.builder()
                .email("henri@example.com").rawPassword("password123").build());

        LoginResult result = service.login(LoginAccountCommand.builder()
                .email("henri@example.com").rawPassword("password123").build());

        assertEquals(LoginResult.Outcome.NOT_VERIFIED, result.getOutcome());
        assertNull("no user aggregate is returned for pending accounts", result.getUser());
    }

    @Test
    public void loginRejectsDisabledUser() {
        registerAndVerify("ivan@example.com", "password123");
        users.findByEmailNormalized("ivan@example.com").orElseThrow().setStatus(AccountStatus.DISABLED);

        LoginResult result = service.login(LoginAccountCommand.builder()
                .email("ivan@example.com").rawPassword("password123").build());

        assertEquals(LoginResult.Outcome.DISABLED, result.getOutcome());
    }

    @Test
    public void loginRejectsDeletedUser() {
        registerAndVerify("juno@example.com", "password123");
        users.findByEmailNormalized("juno@example.com").orElseThrow().setStatus(AccountStatus.DELETED);

        LoginResult result = service.login(LoginAccountCommand.builder()
                .email("juno@example.com").rawPassword("password123").build());

        assertEquals(LoginResult.Outcome.DISABLED, result.getOutcome());
    }

    @Test
    public void loginRejectsUnknownUserWithSameOutcomeAsWrongPassword() {
        registerAndVerify("kate@example.com", "password123");

        LoginResult unknown = service.login(LoginAccountCommand.builder()
                .email("noone@example.com").rawPassword("password123").build());
        LoginResult wrongPassword = service.login(LoginAccountCommand.builder()
                .email("kate@example.com").rawPassword("wrongwrong").build());

        assertEquals(LoginResult.Outcome.INVALID_CREDENTIALS, unknown.getOutcome());
        assertEquals(LoginResult.Outcome.INVALID_CREDENTIALS, wrongPassword.getOutcome());
    }

    @Test
    public void loginRejectsBlankInputAsInvalidCredentials() {
        assertEquals(LoginResult.Outcome.INVALID_CREDENTIALS,
                service.login(LoginAccountCommand.builder().email(null).rawPassword("x").build()).getOutcome());
        assertEquals(LoginResult.Outcome.INVALID_CREDENTIALS,
                service.login(LoginAccountCommand.builder().email("kate@example.com").rawPassword("").build()).getOutcome());
    }

    @Test
    public void fiveConsecutiveLoginFailuresLockFurtherAttemptsForFifteenMinutes() {
        registerAndVerify("locked@example.com", "password123");

        for (int i = 0; i < 5; i++) {
            LoginResult failure = service.login(LoginAccountCommand.builder()
                    .email("locked@example.com").rawPassword("wrong-password").build());
            assertEquals(LoginResult.Outcome.INVALID_CREDENTIALS, failure.getOutcome());
        }

        LoginResult locked = service.login(LoginAccountCommand.builder()
                .email("locked@example.com").rawPassword("password123").build());
        assertEquals(LoginResult.Outcome.LOCKED, locked.getOutcome());

        clock.advance(Duration.ofMinutes(15));
        LoginResult success = service.login(LoginAccountCommand.builder()
                .email("locked@example.com").rawPassword("password123").build());
        assertEquals(LoginResult.Outcome.SUCCESS, success.getOutcome());
    }

    @Test
    public void successfulLoginClearsConsecutiveFailureCount() {
        registerAndVerify("clear-failures@example.com", "password123");

        for (int i = 0; i < 4; i++) {
            service.login(LoginAccountCommand.builder()
                    .email("clear-failures@example.com").rawPassword("wrong-password").build());
        }
        assertEquals(LoginResult.Outcome.SUCCESS, service.login(LoginAccountCommand.builder()
                .email("clear-failures@example.com").rawPassword("password123").build()).getOutcome());

        for (int i = 0; i < 4; i++) {
            service.login(LoginAccountCommand.builder()
                    .email("clear-failures@example.com").rawPassword("wrong-password").build());
        }
        assertEquals(LoginResult.Outcome.SUCCESS, service.login(LoginAccountCommand.builder()
                .email("clear-failures@example.com").rawPassword("password123").build()).getOutcome());
    }

    @Test
    public void requestPasswordResetUsesGenericResponseAndOnlyEmailsActiveUsers() {
        registerAndVerify("maya@example.com", "old-password");

        service.requestPasswordReset("maya@example.com");
        service.requestPasswordReset("unknown@example.com");

        assertEquals(1, emailSender.passwordResetEmails.size());
        assertEquals("maya@example.com", emailSender.passwordResetEmails.get(0).email);
    }

    @Test
    public void passwordResetEmailSendLimitRequiresCooldownAndResetsAfterRollingDay() {
        registerAndVerify("reset-limit@example.com", "old-password");

        service.requestPasswordReset("reset-limit@example.com");
        assertRateLimited(() -> service.requestPasswordReset("reset-limit@example.com"));

        for (int i = 0; i < 4; i++) {
            clock.advance(Duration.ofSeconds(61));
            service.requestPasswordReset("reset-limit@example.com");
        }

        assertEquals(5, emailSender.passwordResetEmails.size());
        clock.advance(Duration.ofSeconds(61));
        assertRateLimited(() -> service.requestPasswordReset("reset-limit@example.com"));

        clock.advance(Duration.ofDays(1));
        service.requestPasswordReset("reset-limit@example.com");
        assertEquals(6, emailSender.passwordResetEmails.size());
    }

    @Test
    public void passwordResetTokenIsStoredHashedWithThirtyMinuteExpiry() {
        registerAndVerify("nora@example.com", "old-password");

        service.requestPasswordReset("nora@example.com");

        AccountToken token = tokens.last();
        String rawToken = emailSender.lastPasswordResetToken();
        assertEquals(TokenPurpose.PASSWORD_RESET, token.getPurpose());
        // Only the hash is stored, never the raw reset token.
        assertFalse(rawToken.equals(token.getTokenHash()));
        assertEquals(new PrefixTokenHasher().hash(rawToken), token.getTokenHash());
        assertEquals(clock.instant().plus(Duration.ofMinutes(30)), token.getExpiresAt());
        assertNull(token.getUsedAt());
    }

    @Test
    public void resetPasswordUpdatesHashConsumesTokenAndIncrementsSessionVersion() {
        registerAndVerify("opal@example.com", "old-password");
        UserAccount before = users.findByEmailNormalized("opal@example.com").orElseThrow();
        int previousSessionVersion = before.getSessionVersion();
        service.requestPasswordReset("opal@example.com");
        String rawToken = emailSender.lastPasswordResetToken();

        PasswordResetResult result = service.resetPassword(rawToken, "new-password");

        assertEquals(PasswordResetResult.SUCCESS, result);
        UserAccount after = users.findById(before.getId()).orElseThrow();
        assertTrue(passwordHasher.matches("new-password", after.getPasswordHash()));
        assertFalse(passwordHasher.matches("old-password", after.getPasswordHash()));
        assertEquals(previousSessionVersion + 1, after.getSessionVersion());
        assertNotNull(tokens.last().getUsedAt());
    }

    @Test
    public void resetPasswordRejectsExpiredToken() {
        registerAndVerify("quinn@example.com", "old-password");
        service.requestPasswordReset("quinn@example.com");
        String rawToken = emailSender.lastPasswordResetToken();

        clock.advance(Duration.ofMinutes(31));
        PasswordResetResult result = service.resetPassword(rawToken, "new-password");

        assertEquals(PasswordResetResult.EXPIRED, result);
        assertTrue(passwordHasher.matches("old-password",
                users.findByEmailNormalized("quinn@example.com").orElseThrow().getPasswordHash()));
    }

    @Test
    public void resetPasswordRejectsReusedToken() {
        registerAndVerify("rhea@example.com", "old-password");
        service.requestPasswordReset("rhea@example.com");
        String rawToken = emailSender.lastPasswordResetToken();

        assertEquals(PasswordResetResult.SUCCESS, service.resetPassword(rawToken, "new-password"));
        assertEquals(PasswordResetResult.ALREADY_USED, service.resetPassword(rawToken, "another-password"));
    }

    @Test
    public void resetPasswordRejectsUnknownToken() {
        assertEquals(PasswordResetResult.INVALID, service.resetPassword("does-not-exist", "new-password"));
        assertEquals(PasswordResetResult.INVALID, service.resetPassword("", "new-password"));
    }

    @Test
    public void findByIdReturnsExistingUser() {
        RegistrationResult reg = service.register(RegisterAccountCommand.builder()
                .email("lola@example.com").rawPassword("password123").build());
        assertNotNull(service.findById(reg.getUserId()).orElse(null));
        assertNull(service.findById("usr_unknown").orElse(null));
    }

    private void registerAndVerify(String email, String password) {
        service.register(RegisterAccountCommand.builder().email(email).rawPassword(password).build());
        service.verifyEmail(emailSender.lastToken());
    }

    private void assertRateLimited(Runnable action) {
        try {
            action.run();
        } catch (RateLimitExceededException expected) {
            return;
        }
        throw new AssertionError("expected rate limit denial");
    }

    // ---- Fakes -------------------------------------------------------------

    private static final class FakeUserAccountStore implements IUserAccountStore {
        private final ConcurrentHashMap<String, UserAccount> byId = new ConcurrentHashMap<>();

        @Override
        public Optional<UserAccount> findByEmailNormalized(String emailNormalized) {
            return byId.values().stream().filter(u -> u.getEmailNormalized().equals(emailNormalized)).findFirst();
        }

        @Override
        public Optional<UserAccount> findById(String id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public void insert(UserAccount account) {
            byId.put(account.getId(), account);
        }

        @Override
        public void markVerified(String userId, Instant verifiedAt) {
            UserAccount user = byId.get(userId);
            if (user != null) {
                user.setStatus(AccountStatus.ACTIVE);
                user.setVerifiedAt(verifiedAt);
                user.setUpdatedAt(verifiedAt);
            }
        }

        @Override
        public boolean updatePasswordHashAndIncrementSessionVersion(String userId, String passwordHash, Instant updatedAt) {
            UserAccount user = byId.get(userId);
            if (user == null) {
                return false;
            }
            user.setPasswordHash(passwordHash);
            user.setSessionVersion(user.getSessionVersion() + 1);
            user.setUpdatedAt(updatedAt);
            return true;
        }

        int count() {
            return byId.size();
        }
    }

    private static final class FakeAccountTokenStore implements IAccountTokenStore {
        private final List<AccountToken> all = new ArrayList<>();

        @Override
        public void insert(AccountToken token) {
            all.add(token);
        }

        @Override
        public Optional<AccountToken> findByHashAndPurpose(String tokenHash, TokenPurpose purpose) {
            return all.stream()
                    .filter(t -> t.getTokenHash().equals(tokenHash) && t.getPurpose() == purpose)
                    .findFirst();
        }

        @Override
        public boolean markUsed(String tokenId, Instant usedAt) {
            for (AccountToken token : all) {
                if (token.getId().equals(tokenId)) {
                    if (token.getUsedAt() != null) {
                        return false;
                    }
                    token.setUsedAt(usedAt);
                    return true;
                }
            }
            return false;
        }

        AccountToken only() {
            assertEquals(1, all.size());
            return all.get(0);
        }

        AccountToken last() {
            return all.get(all.size() - 1);
        }
    }

    private static final class FakePasswordHasher implements IPasswordHasher {
        @Override
        public String hash(String rawPassword) {
            // Base64 of the raw so the hash is deterministic but does not contain the plaintext.
            return "H:" + java.util.Base64.getEncoder().encodeToString(rawPassword.getBytes());
        }

        @Override
        public boolean matches(String rawPassword, String passwordHash) {
            return hash(rawPassword).equals(passwordHash);
        }
    }

    private static final class PrefixTokenHasher implements ITokenHasher {
        @Override
        public String hash(String rawToken) {
            return "th:" + rawToken;
        }
    }

    private static final class SequentialTokenFactory implements ISecureTokenFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public String newToken() {
            return "raw-token-" + counter.incrementAndGet();
        }
    }

    private static final class FakeEmailSender implements IEmailSender {
        private final List<SentEmail> verificationEmails = new ArrayList<>();
        private final List<SentEmail> passwordResetEmails = new ArrayList<>();

        @Override
        public void sendVerificationEmail(String email, String verificationUrl) {
            verificationEmails.add(new SentEmail(email, verificationUrl));
        }

        @Override
        public void sendPasswordResetEmail(String email, String resetUrl) {
            passwordResetEmails.add(new SentEmail(email, resetUrl));
        }

        String lastToken() {
            String url = verificationEmails.get(verificationEmails.size() - 1).url;
            int idx = url.indexOf("token=");
            return url.substring(idx + "token=".length());
        }

        String lastPasswordResetToken() {
            String url = passwordResetEmails.get(passwordResetEmails.size() - 1).url;
            int idx = url.indexOf("token=");
            return url.substring(idx + "token=".length());
        }
    }

    private static final class SentEmail {
        final String email;
        final String url;

        SentEmail(String email, String url) {
            this.email = email;
            this.url = url;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
