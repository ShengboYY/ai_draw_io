package org.zipp.ai.domain.account.model.valobj;

import lombok.Builder;
import lombok.Data;

/**
 * Inputs the resolver considers when deciding who owns the request. An anonymous owner id is
 * deliberately absent because it is an identifier, not proof of access.
 */
@Data
@Builder
public class OwnerResolutionCommand {

    /** Opaque server-issued credential supplied by the HttpOnly cookie. */
    private String anonymousCredential;
    /** User id from a valid Spring Security session, if any. */
    private String authenticatedUserId;

}
