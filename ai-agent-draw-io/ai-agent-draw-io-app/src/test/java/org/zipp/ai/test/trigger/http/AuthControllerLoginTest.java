package org.zipp.ai.test.trigger.http;

import jakarta.servlet.http.HttpSession;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.zipp.ai.api.dto.LoginRequestDTO;
import org.zipp.ai.api.dto.LoginResponseDTO;
import org.zipp.ai.api.dto.PasswordResetConfirmRequestDTO;
import org.zipp.ai.api.dto.PasswordResetConfirmResponseDTO;
import org.zipp.ai.api.dto.PasswordResetRequestDTO;
import org.zipp.ai.api.dto.RegisterAccountRequestDTO;
import org.zipp.ai.api.dto.RegisterAccountResponseDTO;
import org.zipp.ai.api.dto.ResendVerificationRequestDTO;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.adapter.port.IEmailSender;
import org.zipp.ai.domain.account.model.entity.AccountToken;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.account.model.valobj.AccountDeletionResult;
import org.zipp.ai.domain.account.model.valobj.AccountStatus;
import org.zipp.ai.domain.account.model.valobj.LoginResult;
import org.zipp.ai.domain.account.model.valobj.PasswordResetResult;
import org.zipp.ai.domain.account.model.valobj.TokenPurpose;
import org.zipp.ai.domain.account.service.DefaultAccountService;
import org.zipp.ai.domain.account.service.IAccountService;
import org.zipp.ai.domain.account.service.IAccountDeletionService;
import org.zipp.ai.domain.account.service.IAccountTokenStore;
import org.zipp.ai.domain.account.service.IPasswordHasher;
import org.zipp.ai.domain.account.service.ISecureTokenFactory;
import org.zipp.ai.domain.account.service.ITokenHasher;
import org.zipp.ai.domain.account.service.IUserAccountStore;
import org.zipp.ai.domain.account.service.UsageCounterRateLimiter;
import org.zipp.ai.trigger.http.AuthController;
import org.zipp.ai.trigger.http.service.AdminAuthorizationService;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Integration-lite coverage for the login/logout/me controller path. Uses in-memory fakes for the
 * account stores and Spring's mock request/response so the {@link HttpSessionSecurityContextRepository}
 * writes into a real (fake) {@link HttpSession}. That way we exercise the same session mechanics the
 * app will run with, without booting Spring.
 */
public class AuthControllerLoginTest {

    private FakeUserAccountStore users;
    private FakeAccountTokenStore tokens;
    private FakeEmailSender emailSender;
    private AuthController controller;
    private SecurityContextRepository contextRepository;
    private MutableClock clock;

    @Before
    public void setUp() throws Exception {
        users = new FakeUserAccountStore();
        tokens = new FakeAccountTokenStore();
        emailSender = new FakeEmailSender();
        clock = new MutableClock(Instant.parse("2026-07-02T10:00:00Z"));
        UsageCounterRateLimiter usageCounterRateLimiter = new UsageCounterRateLimiter(clock);
        IAccountService service = new DefaultAccountService(
                users, tokens,
                new FakePasswordHasher(),
                new PrefixTokenHasher(),
                new SequentialTokenFactory(),
                emailSender,
                "http://localhost:3000/verify-email",
                "http://localhost:3000/reset-password/confirm",
                clock,
                usageCounterRateLimiter);
        contextRepository = new HttpSessionSecurityContextRepository();
        controller = new AuthController();
        AdminAuthorizationService adminAuthorization = new AdminAuthorizationService();
        inject(adminAuthorization, "accountService", service);
        inject(adminAuthorization, "adminEmails", "admin@example.com");
        inject(controller, "accountService", service);
        inject(controller, "adminAuthorizationService", adminAuthorization);
        inject(controller, "securityContextRepository", contextRepository);
        inject(controller, "usageCounterRateLimiter", usageCounterRateLimiter);
    }

    @After
    public void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void loginSucceedsForVerifiedUserAndBindsSessionPrincipal() {
        registerAndVerify("alice@example.com", "password123");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        Response<LoginResponseDTO> body = controller.login(loginRequest("Alice@Example.com", "password123"), request, response);

        assertEquals("0000", body.getCode());
        assertEquals(LoginResult.Outcome.SUCCESS.name(), body.getData().getStatus());
        assertFalse(body.getData().isAdmin());
        HttpSession session = request.getSession(false);
        assertNotNull("login must create an HTTP session", session);
        // Reload the security context from the session repository to verify it was persisted.
        MockHttpServletRequest reload = new MockHttpServletRequest();
        reload.setSession(session);
        SecurityContext reloaded = contextRepository.loadContext(new HttpRequestResponseHolder(reload, new MockHttpServletResponse()));
        Authentication authentication = reloaded.getAuthentication();
        assertNotNull(authentication);
        assertEquals(users.findByEmailNormalized("alice@example.com").orElseThrow().getId(),
                authentication.getPrincipal().toString());
    }

    @Test
    public void loginAndMeExposeConfiguredAdminStatus() {
        registerAndVerify("admin@example.com", "password123");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        Response<LoginResponseDTO> login = controller.login(
                loginRequest("admin@example.com", "password123"), request, response);

        assertTrue(login.getData().isAdmin());
        assertTrue(controller.me().getData().isAdmin());
    }

    @Test
    public void loginRejectsUnverifiedUserWithoutCreatingSession() {
        registerOnly("bob@example.com", "password123");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        Response<LoginResponseDTO> body = controller.login(loginRequest("bob@example.com", "password123"), request, response);

        assertEquals("0000", body.getCode());
        assertEquals(LoginResult.Outcome.NOT_VERIFIED.name(), body.getData().getStatus());
        assertNull("no session for unverified users", request.getSession(false));
    }

    @Test
    public void loginRejectsDisabledUser() {
        registerAndVerify("carol@example.com", "password123");
        users.findByEmailNormalized("carol@example.com").orElseThrow().setStatus(AccountStatus.DISABLED);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        Response<LoginResponseDTO> body = controller.login(loginRequest("carol@example.com", "password123"), request, response);

        assertEquals(LoginResult.Outcome.DISABLED.name(), body.getData().getStatus());
        assertNull(request.getSession(false));
    }

    @Test
    public void disabledUserCannotContinueAuthenticatedUse() {
        registerAndVerify("disabled-session@example.com", "password123");
        MockHttpServletRequest loginRequest = new MockHttpServletRequest();
        MockHttpServletResponse loginResponse = new MockHttpServletResponse();
        controller.login(loginRequest("disabled-session@example.com", "password123"), loginRequest, loginResponse);
        HttpSession oldSession = loginRequest.getSession(false);
        assertNotNull(oldSession);
        UserAccount user = users.findByEmailNormalized("disabled-session@example.com").orElseThrow();
        users.disableAndIncrementSessionVersion(user.getId(), clock.instant());

        MockHttpServletRequest staleRequest = new MockHttpServletRequest();
        staleRequest.setSession(oldSession);
        SecurityContext staleContext = contextRepository.loadContext(
                new HttpRequestResponseHolder(staleRequest, new MockHttpServletResponse()));
        SecurityContextHolder.setContext(staleContext);
        Response<LoginResponseDTO> me = controller.me(staleRequest);

        assertEquals("ANONYMOUS", me.getData().getStatus());
    }

    @Test
    public void loginRejectsUnknownEmailWithGenericInvalidCredentials() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        Response<LoginResponseDTO> body = controller.login(loginRequest("nobody@example.com", "password123"), request, response);

        assertEquals(LoginResult.Outcome.INVALID_CREDENTIALS.name(), body.getData().getStatus());
        assertNull(request.getSession(false));
    }

    @Test
    public void logoutInvalidatesSessionAndClearsPrincipal() {
        registerAndVerify("dave@example.com", "password123");
        MockHttpServletRequest loginRequest = new MockHttpServletRequest();
        MockHttpServletResponse loginResponse = new MockHttpServletResponse();
        controller.login(loginRequest("dave@example.com", "password123"), loginRequest, loginResponse);
        HttpSession session = loginRequest.getSession(false);
        assertNotNull(session);

        MockHttpServletRequest logoutRequest = new MockHttpServletRequest();
        logoutRequest.setSession(session);
        controller.logout(logoutRequest);

        MockHttpServletRequest reload = new MockHttpServletRequest();
        reload.setSession(session);
        try {
            contextRepository.loadContext(new HttpRequestResponseHolder(reload, new MockHttpServletResponse()));
            // An invalidated session should be unusable — either throw, or hand back an anonymous
            // context. In both cases, no authenticated principal can be recovered from it.
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        } catch (IllegalStateException expected) {
            // Servlet spec throws when reading attributes on an invalidated session.
        }
    }

    @Test
    public void deleteCurrentAccountDelegatesDeletionAndInvalidatesSession() throws Exception {
        registerAndVerify("delete-me@example.com", "password123");
        MockHttpServletRequest loginRequest = new MockHttpServletRequest();
        MockHttpServletResponse loginResponse = new MockHttpServletResponse();
        controller.login(loginRequest("delete-me@example.com", "password123"), loginRequest, loginResponse);
        HttpSession session = loginRequest.getSession(false);
        assertNotNull(session);
        FakeAccountDeletionService deletionService = new FakeAccountDeletionService();
        inject(controller, "accountDeletionService", deletionService);

        Response<Void> deleted = controller.deleteCurrentAccount(loginRequest);

        assertEquals("0000", deleted.getCode());
        assertNotNull(deletionService.deletedUserId);
        MockHttpServletRequest staleRequest = new MockHttpServletRequest();
        staleRequest.setSession(session);
        try {
            contextRepository.loadContext(new HttpRequestResponseHolder(staleRequest, new MockHttpServletResponse()));
            Response<LoginResponseDTO> me = controller.me(staleRequest);
            assertEquals("ANONYMOUS", me.getData().getStatus());
        } catch (IllegalStateException expected) {
            // Invalidated sessions can throw when Spring attempts to read attributes.
        }
    }

    @Test
    public void meReflectsAuthenticatedUserAfterLogin() {
        registerAndVerify("erin@example.com", "password123");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.login(loginRequest("erin@example.com", "password123"), request, response);

        Response<LoginResponseDTO> me = controller.me();

        assertEquals(LoginResult.Outcome.SUCCESS.name(), me.getData().getStatus());
        assertEquals("erin@example.com",
                users.findById(me.getData().getUserId()).orElseThrow().getEmailNormalized());
    }

    @Test
    public void meReturnsAnonymousWhenNoSession() {
        Response<LoginResponseDTO> me = controller.me();

        assertEquals("ANONYMOUS", me.getData().getStatus());
        assertNull(me.getData().getUserId());
    }

    @Test
    public void passwordResetRequestUsesGenericResponseForKnownAndUnknownEmails() {
        registerAndVerify("fran@example.com", "password123");
        PasswordResetRequestDTO known = new PasswordResetRequestDTO();
        known.setEmail("fran@example.com");
        PasswordResetRequestDTO unknown = new PasswordResetRequestDTO();
        unknown.setEmail("unknown@example.com");

        Response<Void> knownResponse = controller.requestPasswordReset(known);
        Response<Void> unknownResponse = controller.requestPasswordReset(unknown);

        assertEquals("0000", knownResponse.getCode());
        assertEquals("0000", unknownResponse.getCode());
        assertEquals(1, emailSender.passwordResetTokens.size());
    }

    @Test
    public void registrationAndVerificationSendAttemptsShareIpHourlyLimit() {
        String ip = "203.0.113.10";
        for (int i = 0; i < 10; i++) {
            controller.register(registerRequest("reg-hour-" + i + "@example.com", "short"), requestFrom(ip));
        }
        for (int i = 0; i < 10; i++) {
            controller.resendVerification(resendRequest("verify-hour-" + i + "@example.com"), requestFrom(ip));
        }

        Response<RegisterAccountResponseDTO> denied =
                controller.register(registerRequest("reg-hour-final@example.com", "password123"), requestFrom(ip));

        assertEquals("AUTH_RATE_LIMITED", denied.getCode());
    }

    @Test
    public void registrationAndVerificationSendAttemptsShareIpDailyLimit() {
        String ip = "203.0.113.11";
        for (int i = 0; i < 100; i++) {
            controller.register(registerRequest("reg-day-" + i + "@example.com", "short"), requestFrom(ip));
            clock.advance(Duration.ofMinutes(12));
        }

        Response<RegisterAccountResponseDTO> denied =
                controller.register(registerRequest("reg-day-final@example.com", "password123"), requestFrom(ip));
        assertEquals("AUTH_RATE_LIMITED", denied.getCode());

        clock.advance(Duration.ofDays(1));
        Response<RegisterAccountResponseDTO> allowed =
                controller.register(registerRequest("reg-day-reset@example.com", "short"), requestFrom(ip));
        assertEquals("0001", allowed.getCode());
    }

    @Test
    public void passwordResetAttemptsAreLimitedByIpPerHour() {
        String ip = "203.0.113.12";
        for (int i = 0; i < 20; i++) {
            controller.requestPasswordReset(resetRequest("reset-hour-" + i + "@example.com"), requestFrom(ip));
        }

        Response<Void> denied = controller.requestPasswordReset(
                resetRequest("reset-hour-final@example.com"), requestFrom(ip));

        assertEquals("AUTH_RATE_LIMITED", denied.getCode());
    }

    @Test
    public void loginAttemptsAreLimitedByIpPerHour() {
        registerAndVerify("login-hour@example.com", "password123");
        String ip = "203.0.113.13";
        for (int i = 0; i < 30; i++) {
            controller.login(loginRequest("login-hour@example.com", "wrong-password"), requestFrom(ip),
                    new MockHttpServletResponse());
        }

        Response<LoginResponseDTO> denied = controller.login(
                loginRequest("login-hour@example.com", "password123"), requestFrom(ip), new MockHttpServletResponse());

        assertEquals("AUTH_RATE_LIMITED", denied.getCode());
    }

    @Test
    public void resetPasswordConfirmReturnsStatusAndInvalidatesExistingSession() {
        registerAndVerify("gail@example.com", "password123");
        MockHttpServletRequest loginRequest = new MockHttpServletRequest();
        MockHttpServletResponse loginResponse = new MockHttpServletResponse();
        controller.login(loginRequest("gail@example.com", "password123"), loginRequest, loginResponse);
        HttpSession oldSession = loginRequest.getSession(false);
        assertNotNull(oldSession);
        PasswordResetRequestDTO resetRequest = new PasswordResetRequestDTO();
        resetRequest.setEmail("gail@example.com");
        controller.requestPasswordReset(resetRequest);
        PasswordResetConfirmRequestDTO confirm = new PasswordResetConfirmRequestDTO();
        confirm.setToken(emailSender.lastPasswordResetToken());
        confirm.setPassword("new-password");

        Response<PasswordResetConfirmResponseDTO> reset = controller.confirmPasswordReset(confirm);

        assertEquals("0000", reset.getCode());
        assertEquals(PasswordResetResult.SUCCESS.name(), reset.getData().getStatus());
        MockHttpServletRequest staleRequest = new MockHttpServletRequest();
        staleRequest.setSession(oldSession);
        SecurityContext staleContext = contextRepository.loadContext(
                new HttpRequestResponseHolder(staleRequest, new MockHttpServletResponse()));
        SecurityContextHolder.setContext(staleContext);
        Response<LoginResponseDTO> me = controller.me(staleRequest);

        assertEquals("ANONYMOUS", me.getData().getStatus());
        try {
            oldSession.getId();
            oldSession.getAttributeNames();
        } catch (IllegalStateException expected) {
            return;
        }
        assertNull("stale session should not retain an authenticated principal",
                SecurityContextHolder.getContext().getAuthentication());
    }

    private LoginRequestDTO loginRequest(String email, String password) {
        LoginRequestDTO dto = new LoginRequestDTO();
        dto.setEmail(email);
        dto.setPassword(password);
        return dto;
    }

    private RegisterAccountRequestDTO registerRequest(String email, String password) {
        RegisterAccountRequestDTO dto = new RegisterAccountRequestDTO();
        dto.setEmail(email);
        dto.setPassword(password);
        return dto;
    }

    private ResendVerificationRequestDTO resendRequest(String email) {
        ResendVerificationRequestDTO dto = new ResendVerificationRequestDTO();
        dto.setEmail(email);
        return dto;
    }

    private PasswordResetRequestDTO resetRequest(String email) {
        PasswordResetRequestDTO dto = new PasswordResetRequestDTO();
        dto.setEmail(email);
        return dto;
    }

    private MockHttpServletRequest requestFrom(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        return request;
    }

    private void registerOnly(String email, String password) {
        RegisterAccountRequestDTO dto = new RegisterAccountRequestDTO();
        dto.setEmail(email);
        dto.setPassword(password);
        controller.register(dto);
    }

    private void registerAndVerify(String email, String password) {
        registerOnly(email, password);
        controller.verifyEmail(emailSender.lastToken());
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    // ---- Fakes (mirroring DefaultAccountServiceTest so the two test files stay self-contained) ----

    private static final class FakeUserAccountStore implements IUserAccountStore {
        private final ConcurrentHashMap<String, UserAccount> byId = new ConcurrentHashMap<>();

        @Override public Optional<UserAccount> findByEmailNormalized(String emailNormalized) {
            return byId.values().stream().filter(u -> u.getEmailNormalized().equals(emailNormalized)).findFirst();
        }
        @Override public Optional<UserAccount> findById(String id) { return Optional.ofNullable(byId.get(id)); }
        @Override public List<UserAccount> listAll() { return new ArrayList<>(byId.values()); }
        @Override public void insert(UserAccount account) { byId.put(account.getId(), account); }
        @Override public void markVerified(String userId, java.time.Instant verifiedAt) {
            UserAccount user = byId.get(userId);
            if (user != null) {
                user.setStatus(AccountStatus.ACTIVE);
                user.setVerifiedAt(verifiedAt);
                user.setUpdatedAt(verifiedAt);
            }
        }
        @Override public boolean updatePasswordHashAndIncrementSessionVersion(String userId, String passwordHash, java.time.Instant updatedAt) {
            UserAccount user = byId.get(userId);
            if (user == null) return false;
            user.setPasswordHash(passwordHash);
            user.setSessionVersion(user.getSessionVersion() + 1);
            user.setUpdatedAt(updatedAt);
            return true;
        }
        @Override public boolean disableAndIncrementSessionVersion(String userId, java.time.Instant updatedAt) {
            UserAccount user = byId.get(userId);
            if (user == null || user.getStatus() == AccountStatus.DISABLED || user.getStatus() == AccountStatus.DELETED) return false;
            user.setStatus(AccountStatus.DISABLED);
            user.setSessionVersion(user.getSessionVersion() + 1);
            user.setUpdatedAt(updatedAt);
            return true;
        }
    }

    private static final class FakeAccountTokenStore implements IAccountTokenStore {
        private final List<AccountToken> all = new ArrayList<>();
        @Override public void insert(AccountToken token) { all.add(token); }
        @Override public Optional<AccountToken> findByHashAndPurpose(String tokenHash, TokenPurpose purpose) {
            return all.stream().filter(t -> t.getTokenHash().equals(tokenHash) && t.getPurpose() == purpose).findFirst();
        }
        @Override public boolean markUsed(String tokenId, java.time.Instant usedAt) {
            for (AccountToken token : all) {
                if (token.getId().equals(tokenId)) {
                    if (token.getUsedAt() != null) return false;
                    token.setUsedAt(usedAt);
                    return true;
                }
            }
            return false;
        }
    }

    private static final class FakePasswordHasher implements IPasswordHasher {
        @Override public String hash(String rawPassword) { return "H:" + Base64.getEncoder().encodeToString(rawPassword.getBytes()); }
        @Override public boolean matches(String rawPassword, String passwordHash) { return hash(rawPassword).equals(passwordHash); }
    }

    private static final class PrefixTokenHasher implements ITokenHasher {
        @Override public String hash(String rawToken) { return "th:" + rawToken; }
    }

    private static final class SequentialTokenFactory implements ISecureTokenFactory {
        private final AtomicInteger counter = new AtomicInteger();
        @Override public String newToken() { return "raw-token-" + counter.incrementAndGet(); }
    }

    private static final class FakeEmailSender implements IEmailSender {
        private final List<String> tokens = new ArrayList<>();
        private final List<String> passwordResetTokens = new ArrayList<>();
        @Override public void sendVerificationEmail(String email, String verificationUrl) {
            int idx = verificationUrl.indexOf("token=");
            tokens.add(verificationUrl.substring(idx + "token=".length()));
        }
        @Override public void sendPasswordResetEmail(String email, String resetUrl) {
            int idx = resetUrl.indexOf("token=");
            passwordResetTokens.add(resetUrl.substring(idx + "token=".length()));
        }
        String lastToken() { return tokens.get(tokens.size() - 1); }
        String lastPasswordResetToken() { return passwordResetTokens.get(passwordResetTokens.size() - 1); }
    }

    private static final class FakeAccountDeletionService implements IAccountDeletionService {
        private String deletedUserId;

        @Override
        public Optional<AccountDeletionResult> deleteAccount(String userId) {
            deletedUserId = userId;
            return Optional.of(AccountDeletionResult.builder()
                    .anonymizedUserId("deleted_usr_test")
                    .deletedAt(Instant.parse("2026-07-02T10:00:00Z"))
                    .build());
        }
    }
}
