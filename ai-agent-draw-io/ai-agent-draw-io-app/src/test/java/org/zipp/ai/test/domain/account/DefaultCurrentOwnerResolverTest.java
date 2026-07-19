package org.zipp.ai.test.domain.account;

import org.junit.Test;
import org.zipp.ai.domain.account.model.valobj.AccountStatus;
import org.zipp.ai.domain.account.model.valobj.OwnerResolutionCommand;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.account.model.valobj.ResolvedOwner;
import org.zipp.ai.domain.account.service.DefaultCurrentOwnerResolver;
import org.zipp.ai.domain.account.service.IAnonymousWorkspaceIdentityService;
import org.zipp.ai.domain.account.service.ICurrentOwnerResolver;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DefaultCurrentOwnerResolverTest {

    private static final String VALID_CREDENTIAL = "awc_123e4567-e89b-42d3-a456-426614174000.secret-value";
    private static final String VALID_WORKSPACE_ID = "anon_123e4567-e89b-42d3-a456-426614174000";

    private final ICurrentOwnerResolver resolver = new DefaultCurrentOwnerResolver(identityService());

    @Test
    public void shouldResolveValidAnonymousWorkspaceOwner() {
        Optional<ResolvedOwner> owner = resolver.resolve(OwnerResolutionCommand.builder()
                .anonymousCredential(VALID_CREDENTIAL)
                .build());

        assertTrue(owner.isPresent());
        assertEquals(VALID_WORKSPACE_ID, owner.get().getOwnerId());
        assertEquals(OwnerType.ANONYMOUS, owner.get().getOwnerType());
        assertEquals(AccountStatus.ANONYMOUS, owner.get().getAccountStatus());
        assertFalse(owner.get().isAuthenticated());
        assertFalse(owner.get().isEmailVerified());
    }

    @Test
    public void shouldRejectBlankWorkspaceOwner() {
        Optional<ResolvedOwner> owner = resolver.resolve(OwnerResolutionCommand.builder()
                .anonymousCredential(" ")
                .build());

        assertFalse(owner.isPresent());
    }

    @Test
    public void shouldRejectPredictableWorkspaceOwner() {
        Optional<ResolvedOwner> owner = resolver.resolve(OwnerResolutionCommand.builder()
                .anonymousCredential(VALID_WORKSPACE_ID)
                .build());

        assertFalse(owner.isPresent());
    }

    @Test
    public void shouldPreferAuthenticatedUserOverWorkspaceHeader() {
        Optional<ResolvedOwner> owner = resolver.resolve(OwnerResolutionCommand.builder()
                .authenticatedUserId(" usr_abc-123 ")
                .anonymousCredential(VALID_CREDENTIAL)
                .build());

        assertTrue(owner.isPresent());
        assertEquals("usr_abc-123", owner.get().getOwnerId());
        assertEquals(OwnerType.USER, owner.get().getOwnerType());
        assertEquals(AccountStatus.ACTIVE, owner.get().getAccountStatus());
        assertTrue(owner.get().isAuthenticated());
        assertTrue(owner.get().isEmailVerified());
    }

    @Test
    public void shouldFallBackToAnonymousWhenAuthenticatedUserIsBlank() {
        Optional<ResolvedOwner> owner = resolver.resolve(OwnerResolutionCommand.builder()
                .authenticatedUserId("   ")
                .anonymousCredential(VALID_CREDENTIAL)
                .build());

        assertTrue(owner.isPresent());
        assertEquals(OwnerType.ANONYMOUS, owner.get().getOwnerType());
        assertFalse(owner.get().isAuthenticated());
    }

    private IAnonymousWorkspaceIdentityService identityService() {
        return new IAnonymousWorkspaceIdentityService() {
            @Override
            public org.zipp.ai.domain.account.model.valobj.IssuedAnonymousWorkspace issue() {
                throw new UnsupportedOperationException("Not needed by owner resolution tests");
            }

            @Override
            public Optional<ResolvedOwner> authenticate(String rawCredential) {
                return VALID_CREDENTIAL.equals(rawCredential)
                        ? Optional.of(ResolvedOwner.anonymous(VALID_WORKSPACE_ID))
                        : Optional.empty();
            }

            @Override
            public String claim(String rawCredential, String targetUserId) {
                throw new UnsupportedOperationException("Not needed by owner resolution tests");
            }
        };
    }
}
