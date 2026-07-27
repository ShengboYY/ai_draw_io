package org.zipp.ai.test.trigger.http;

import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.zipp.ai.api.dto.AnonymousWorkspaceResponseDTO;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.valobj.IssuedAnonymousWorkspace;
import org.zipp.ai.domain.account.model.valobj.ResolvedOwner;
import org.zipp.ai.domain.account.service.IAnonymousWorkspaceIdentityService;
import org.zipp.ai.trigger.http.AnonymousWorkspaceController;
import org.zipp.ai.trigger.http.AnonymousWorkspaceCookie;

import jakarta.servlet.http.Cookie;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AnonymousWorkspaceControllerTest {

    private static final String OWNER_ID = "anon_123e4567-e89b-42d3-a456-426614174000";
    private static final String CREDENTIAL = "awc_123e4567-e89b-42d3-a456-426614174000.secret-value";

    @Test
    public void shouldIssueCredentialInHttpOnlyCookieWithoutReturningSecretInBody() {
        StubIdentityService identity = new StubIdentityService();
        AnonymousWorkspaceController controller = new AnonymousWorkspaceController(
                identity, new AnonymousWorkspaceCookie(true));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        Response<AnonymousWorkspaceResponseDTO> response = controller.ensure(
                new MockHttpServletRequest(), servletResponse);

        assertEquals("0000", response.getCode());
        assertEquals(OWNER_ID, response.getData().getOwnerId());
        assertFalse(response.toString().contains(CREDENTIAL));
        String setCookie = servletResponse.getHeader("Set-Cookie");
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("Secure"));
        assertTrue(setCookie.contains("SameSite=Lax"));
        assertEquals(1, identity.issueCalls);
    }

    @Test
    public void shouldReuseValidCookieInsteadOfIssuingAnotherCredential() {
        StubIdentityService identity = new StubIdentityService();
        AnonymousWorkspaceController controller = new AnonymousWorkspaceController(
                identity, new AnonymousWorkspaceCookie(false));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AnonymousWorkspaceCookie.NAME, CREDENTIAL));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        Response<AnonymousWorkspaceResponseDTO> response = controller.ensure(request, servletResponse);

        assertEquals(OWNER_ID, response.getData().getOwnerId());
        assertEquals(0, identity.issueCalls);
        assertFalse(servletResponse.containsHeader("Set-Cookie"));
    }

    private static final class StubIdentityService implements IAnonymousWorkspaceIdentityService {
        private int issueCalls;

        @Override
        public IssuedAnonymousWorkspace issue() {
            issueCalls++;
            return new IssuedAnonymousWorkspace(OWNER_ID, CREDENTIAL);
        }

        @Override
        public Optional<ResolvedOwner> authenticate(String rawCredential) {
            return CREDENTIAL.equals(rawCredential)
                    ? Optional.of(ResolvedOwner.anonymous(OWNER_ID))
                    : Optional.empty();
        }

        @Override
        public String claim(String rawCredential, String targetUserId) {
            throw new UnsupportedOperationException("Not needed by endpoint tests");
        }
    }
}
