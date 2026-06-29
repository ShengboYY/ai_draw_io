package org.zipp.ai.domain.agent.service;

import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingCommand;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingResult;

public interface IIntentRoutingService {

    IntentRoutingResult route(IntentRoutingCommand command);

}
