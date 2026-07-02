package org.zipp.ai.domain.account.model.valobj;

import lombok.Builder;
import lombok.Data;

/**
 * Inputs the resolver considers when deciding who owns the request. When a session-authenticated
 * user id is present it wins outright; the workspace header is only consulted for anonymous callers.
 */
@Data
@Builder
public class OwnerResolutionCommand {

    private String workspaceId;
    /** User id from a valid Spring Security session, if any. */
    private String authenticatedUserId;

}
