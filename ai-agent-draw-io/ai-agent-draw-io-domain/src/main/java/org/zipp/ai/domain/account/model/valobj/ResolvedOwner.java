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
}
