package org.zipp.ai.domain.account.model.valobj;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OwnerResolutionCommand {

    private String workspaceId;

}
