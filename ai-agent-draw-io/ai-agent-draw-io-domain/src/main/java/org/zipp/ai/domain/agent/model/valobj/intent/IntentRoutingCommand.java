package org.zipp.ai.domain.agent.model.valobj.intent;

import org.zipp.ai.domain.agent.service.chat.CustomApiConfigManager;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class IntentRoutingCommand {

    private String userId;

    private String message;

    private String canvasXml;

    private String canvasSummary;

    private CustomApiConfigManager.CustomApiConfig customApiConfig;

}
