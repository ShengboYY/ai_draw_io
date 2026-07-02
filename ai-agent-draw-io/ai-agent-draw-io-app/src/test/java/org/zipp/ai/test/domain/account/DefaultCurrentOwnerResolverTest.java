package org.zipp.ai.test.domain.account;

import org.junit.Test;
import org.zipp.ai.domain.account.model.valobj.AccountStatus;
import org.zipp.ai.domain.account.model.valobj.OwnerResolutionCommand;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.account.model.valobj.ResolvedOwner;
import org.zipp.ai.domain.account.service.DefaultCurrentOwnerResolver;
import org.zipp.ai.domain.account.service.ICurrentOwnerResolver;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DefaultCurrentOwnerResolverTest {

    private static final String VALID_WORKSPACE_ID = "anon_123e4567-e89b-42d3-a456-426614174000";

    private final ICurrentOwnerResolver resolver = new DefaultCurrentOwnerResolver();

    @Test
    public void shouldResolveValidAnonymousWorkspaceOwner() {
        Optional<ResolvedOwner> owner = resolver.resolve(OwnerResolutionCommand.builder()
                .workspaceId("  ANON_123E4567-E89B-42D3-A456-426614174000  ")
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
                .workspaceId(" ")
                .build());

        assertFalse(owner.isPresent());
    }

    @Test
    public void shouldRejectPredictableWorkspaceOwner() {
        Optional<ResolvedOwner> owner = resolver.resolve(OwnerResolutionCommand.builder()
                .workspaceId("admin")
                .build());

        assertFalse(owner.isPresent());
    }

    @Test
    public void shouldPreferAuthenticatedUserOverWorkspaceHeader() {
        Optional<ResolvedOwner> owner = resolver.resolve(OwnerResolutionCommand.builder()
                .authenticatedUserId(" usr_abc-123 ")
                .workspaceId(VALID_WORKSPACE_ID)
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
                .workspaceId(VALID_WORKSPACE_ID)
                .build());

        assertTrue(owner.isPresent());
        assertEquals(OwnerType.ANONYMOUS, owner.get().getOwnerType());
        assertFalse(owner.get().isAuthenticated());
    }
}
