package org.zipp.ai.test.domain.account;

import org.junit.Before;
import org.junit.Test;
import org.zipp.ai.domain.account.adapter.port.IEmailSender;
import org.zipp.ai.domain.account.model.entity.AccountToken;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.account.model.valobj.AccountStatus;
import org.zipp.ai.domain.account.model.valobj.EmailVerificationResult;
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
                new PrefixTokenHasher(), new SequentialTokenFactory(), emailSender, BASE_URL, clock);
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

        @Override
        public void sendVerificationEmail(String email, String verificationUrl) {
            verificationEmails.add(new SentEmail(email, verificationUrl));
        }

        @Override
        public void sendPasswordResetEmail(String email, String resetUrl) {
        }

        String lastToken() {
            String url = verificationEmails.get(verificationEmails.size() - 1).url;
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
