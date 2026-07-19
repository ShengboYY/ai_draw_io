package org.zipp.ai.api.dto;

import lombok.Data;

@Data
public class ImportAnonymousWorkspaceRequestDTO {

    /** @deprecated Source ownership is derived from the HttpOnly credential cookie. */
    @Deprecated
    private String anonymousWorkspaceId;

}
