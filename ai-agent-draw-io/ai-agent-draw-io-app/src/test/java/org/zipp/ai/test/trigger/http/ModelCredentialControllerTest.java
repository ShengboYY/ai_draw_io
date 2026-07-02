package org.zipp.ai.test.trigger.http;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.zipp.ai.api.dto.CreateModelCredentialRequestDTO;
import org.zipp.ai.api.dto.ModelCredentialResponseDTO;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.account.model.valobj.AccountStatus;
import org.zipp.ai.domain.account.model.valobj.CreateModelCredentialCommand;
import org.zipp.ai.domain.account.model.valobj.EmailVerificationResult;
import org.zipp.ai.domain.account.model.valobj.LoginAccountCommand;
import org.zipp.ai.domain.account.model.valobj.LoginResult;
import org.zipp.ai.domain.account.model.valobj.ModelCredentialSecret;
import org.zipp.ai.domain.account.model.valobj.ModelCredentialStatus;
import org.zipp.ai.domain.account.model.valobj.ModelCredentialSummary;
import org.zipp.ai.domain.account.model.valobj.PasswordResetResult;
import org.zipp.ai.domain.account.model.valobj.RegisterAccountCommand;
import org.zipp.ai.domain.account.model.valobj.RegistrationResult;
import org.zipp.ai.domain.account.service.IAccountService;
import org.zipp.ai.domain.account.service.IModelCredentialService;
import org.zipp.ai.trigger.http.CurrentOwnerHttpResolver;
import org.zipp.ai.trigger.http.ModelCredentialController;
import org.zipp.ai.trigger.http.service.AuthenticatedSessionUser;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ModelCredentialControllerTest {

    private ModelCredentialController controller;
    private FakeModelCredentialService credentials;

    @Before
    public void setUp() throws Exception {
        credentials = new FakeModelCredentialService();
        CurrentOwnerHttpResolver ownerResolver = new CurrentOwnerHttpResolver();
        inject(ownerResolver, "accountService", new FakeAccountService());

        controller = new ModelCredentialController();
        inject(controller, "modelCredentialService", credentials);
        inject(controller, "currentOwnerHttpResolver", ownerResolver);
    }

    @After
    public void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void createUsesVerifiedSessionUserAndReturnsMaskedResponse() {
        authenticate("usr_alice", 0);

        Response<ModelCredentialResponseDTO> response = controller.create(request());

        assertEquals("0000", response.getCode());
        assertEquals("usr_alice", credentials.created.getUserId());
        assertEquals("sk-live-secret-1234", credentials.created.getApiKey());
        assertEquals("****1234", response.getData().getMaskedApiKey());
        assertNull("raw API key must never be returned", rawApiKeyField(response.getData()));
    }

    @Test
    public void listUsesVerifiedSessionUserAndResponseShapeHasNoRawKeyFields() {
        authenticate("usr_alice", 0);

        Response<List<ModelCredentialResponseDTO>> response = controller.list();

        assertEquals("0000", response.getCode());
        assertEquals("usr_alice", credentials.listedUserId);
        assertEquals("****1234", response.getData().get(0).getMaskedApiKey());
        assertFalse(hasFieldNamed(ModelCredentialResponseDTO.class, "apiKey"));
        assertFalse(hasFieldNamed(ModelCredentialResponseDTO.class, "encryptedApiKey"));
    }

    @Test
    public void anonymousUsersCannotManageCredentials() {
        Response<ModelCredentialResponseDTO> response = controller.create(request());

        assertEquals("0001", response.getCode());
        assertNull(credentials.created);
    }

    @Test
    public void disableAndDeleteUseVerifiedSessionUser() {
        authenticate("usr_alice", 0);

        assertEquals("0000", controller.disable("mcr_1").getCode());
        assertEquals("usr_alice", credentials.disabledUserId);
        assertEquals("mcr_1", credentials.disabledId);

        assertEquals("0000", controller.delete("mcr_1").getCode());
        assertEquals("usr_alice", credentials.deletedUserId);
        assertEquals("mcr_1", credentials.deletedId);
    }

    private CreateModelCredentialRequestDTO request() {
        CreateModelCredentialRequestDTO dto = new CreateModelCredentialRequestDTO();
        dto.setProvider("openai");
        dto.setBaseUrl("https://api.openai.com/v1");
        dto.setModel("gpt-4o");
        dto.setCompletionPath("/chat/completions");
        dto.setDisplayName("OpenAI production");
        dto.setApiKey("sk-live-secret-1234");
        return dto;
    }

    private void authenticate(String userId, int sessionVersion) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedSessionUser(userId, sessionVersion),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);
    }

    private Object rawApiKeyField(ModelCredentialResponseDTO response) {
        try {
            Field field = response.getClass().getDeclaredField("apiKey");
            field.setAccessible(true);
            return field.get(response);
        } catch (NoSuchFieldException expected) {
            return null;
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private boolean hasFieldNamed(Class<?> type, String name) {
        for (Field field : type.getDeclaredFields()) {
            if (field.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class FakeModelCredentialService implements IModelCredentialService {
        private CreateModelCredentialCommand created;
        private String listedUserId;
        private String disabledUserId;
        private String disabledId;
        private String deletedUserId;
        private String deletedId;

        @Override
        public ModelCredentialSummary create(CreateModelCredentialCommand command) {
            created = command;
            return summary();
        }

        @Override
        public List<ModelCredentialSummary> list(String userId) {
            listedUserId = userId;
            return List.of(summary());
        }

        @Override
        public ModelCredentialSecret resolveForChat(String userId, String credentialId) {
            return ModelCredentialSecret.builder().id(credentialId).build();
        }

        @Override
        public boolean disable(String userId, String credentialId) {
            disabledUserId = userId;
            disabledId = credentialId;
            return true;
        }

        @Override
        public boolean delete(String userId, String credentialId) {
            deletedUserId = userId;
            deletedId = credentialId;
            return true;
        }

        private ModelCredentialSummary summary() {
            return ModelCredentialSummary.builder()
                    .id("mcr_1")
                    .provider("openai")
                    .baseUrl("https://api.openai.com/v1")
                    .model("gpt-4o")
                    .completionPath("/chat/completions")
                    .displayName("OpenAI production")
                    .maskedApiKey("****1234")
                    .encryptionProvider("ENV_AES_GCM")
                    .encryptionKeyId("env-key-1")
                    .keyLastFour("1234")
                    .status(ModelCredentialStatus.ACTIVE)
                    .createdAt(Instant.parse("2026-07-02T10:00:00Z"))
                    .updatedAt(Instant.parse("2026-07-02T10:00:00Z"))
                    .build();
        }
    }

    private static final class FakeAccountService implements IAccountService {
        @Override public RegistrationResult register(RegisterAccountCommand command) { return null; }
        @Override public EmailVerificationResult verifyEmail(String rawToken) { return null; }
        @Override public void resendVerification(String email) {}
        @Override public void requestPasswordReset(String email) {}
        @Override public PasswordResetResult resetPassword(String rawToken, String rawPassword) { return null; }
        @Override public LoginResult login(LoginAccountCommand command) { return null; }

        @Override
        public Optional<UserAccount> findById(String userId) {
            return Optional.of(UserAccount.builder()
                    .id(userId)
                    .status(AccountStatus.ACTIVE)
                    .sessionVersion(0)
                    .build());
        }

        @Override
        public List<UserAccount> listUsers() {
            return List.of();
        }

        @Override
        public Optional<UserAccount> disableUser(String userId) {
            return Optional.empty();
        }
    }
}
