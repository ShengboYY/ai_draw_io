package org.zipp.ai.domain.agent.model.valobj.review;

import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingResult;
import org.zipp.ai.domain.agent.service.chat.CustomApiConfigManager;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CanvasReviewCommand {

    private String userId;

    private String message;

    private IntentRoutingResult routingResult;

    private List<String> selectedSkillNames;

    private CustomApiConfigManager.CustomApiConfig customApiConfig;

}
