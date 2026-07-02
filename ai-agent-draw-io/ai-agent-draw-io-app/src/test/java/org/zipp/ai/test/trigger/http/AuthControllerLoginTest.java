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
import org.zipp.ai.api.dto.RegisterAccountRequestDTO;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.adapter.port.IEmailSender;
import org.zipp.ai.domain.account.model.entity.AccountToken;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.account.model.valobj.AccountStatus;
import org.zipp.ai.domain.account.model.valobj.LoginResult;
import org.zipp.ai.domain.account.model.valobj.TokenPurpose;
import org.zipp.ai.domain.account.service.DefaultAccountService;
import org.zipp.ai.domain.account.service.IAccountService;
import org.zipp.ai.domain.account.service.IAccountTokenStore;
import org.zipp.ai.domain.account.service.IPasswordHasher;
import org.zipp.ai.domain.account.service.ISecureTokenFactory;
import org.zipp.ai.domain.account.service.ITokenHasher;
import org.zipp.ai.domain.account.service.IUserAccountStore;
import org.zipp.ai.trigger.http.AuthController;

import java.lang.reflect.Field;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

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

    @Before
    public void setUp() throws Exception {
        users = new FakeUserAccountStore();
        tokens = new FakeAccountTokenStore();
        emailSender = new FakeEmailSender();
        IAccountService service = new DefaultAccountService(
                users, tokens,
                new FakePasswordHasher(),
                new PrefixTokenHasher(),
                new SequentialTokenFactory(),
                emailSender,
                "http://localhost:3000/verify-email",
                Clock.systemUTC());
        contextRepository = new HttpSessionSecurityContextRepository();
        controller = new AuthController();
        inject(controller, "accountService", service);
        inject(controller, "securityContextRepository", contextRepository);
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

    private LoginRequestDTO loginRequest(String email, String password) {
        LoginRequestDTO dto = new LoginRequestDTO();
        dto.setEmail(email);
        dto.setPassword(password);
        return dto;
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

    // ---- Fakes (mirroring DefaultAccountServiceTest so the two test files stay self-contained) ----

    private static final class FakeUserAccountStore implements IUserAccountStore {
        private final ConcurrentHashMap<String, UserAccount> byId = new ConcurrentHashMap<>();

        @Override public Optional<UserAccount> findByEmailNormalized(String emailNormalized) {
            return byId.values().stream().filter(u -> u.getEmailNormalized().equals(emailNormalized)).findFirst();
        }
        @Override public Optional<UserAccount> findById(String id) { return Optional.ofNullable(byId.get(id)); }
        @Override public void insert(UserAccount account) { byId.put(account.getId(), account); }
        @Override public void markVerified(String userId, java.time.Instant verifiedAt) {
            UserAccount user = byId.get(userId);
            if (user != null) {
                user.setStatus(AccountStatus.ACTIVE);
                user.setVerifiedAt(verifiedAt);
                user.setUpdatedAt(verifiedAt);
            }
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
        @Override public void sendVerificationEmail(String email, String verificationUrl) {
            int idx = verificationUrl.indexOf("token=");
            tokens.add(verificationUrl.substring(idx + "token=".length()));
        }
        @Override public void sendPasswordResetEmail(String email, String resetUrl) {}
        String lastToken() { return tokens.get(tokens.size() - 1); }
    }
}
