package org.zipp.ai.domain.retrieval.port;

import org.zipp.ai.domain.account.model.valobj.OwnerType;

/** Converts an Owner identity into stable opaque metadata safe for external vector storage. */
public interface TenantKeyPort {
    String opaqueKey(OwnerType ownerType, String ownerKey);
}
