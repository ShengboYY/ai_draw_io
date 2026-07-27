package org.zipp.ai.domain.agent.model.valobj.intent;

import org.zipp.ai.domain.agent.service.chat.CustomApiConfigManager;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class IntentRoutingCommand {

    private String userId;

    private String message;

    /** Trusted counts and enums only; raw canvas/document content is not part of the router contract. */
    private IntentRoutingProbe requestProbe;

    private CustomApiConfigManager.CustomApiConfig customApiConfig;

}
