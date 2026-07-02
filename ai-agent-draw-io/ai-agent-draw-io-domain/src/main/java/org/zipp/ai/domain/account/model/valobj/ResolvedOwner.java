package org.zipp.ai.domain.account.model.valobj;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ResolvedOwner {

    private String ownerId;
    private OwnerType ownerType;
    private boolean authenticated;
    private boolean emailVerified;
    private AccountStatus accountStatus;

    public static ResolvedOwner anonymous(String ownerId) {
        return ResolvedOwner.builder()
                .ownerId(ownerId)
                .ownerType(OwnerType.ANONYMOUS)
                .authenticated(false)
                .emailVerified(false)
                .accountStatus(AccountStatus.ANONYMOUS)
                .build();
    }

    /**
     * Authenticated session owner. Only produced for {@link AccountStatus#ACTIVE} users; pending,
     * disabled, or deleted accounts must never surface here.
     */
    public static ResolvedOwner authenticated(String userId) {
        return ResolvedOwner.builder()
                .ownerId(userId)
                .ownerType(OwnerType.USER)
                .authenticated(true)
                .emailVerified(true)
                .accountStatus(AccountStatus.ACTIVE)
                .build();
    }
}
