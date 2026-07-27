package org.zipp.ai.application.turn.context;

import org.zipp.ai.application.turn.classification.SemanticRouterInput;

public interface SemanticRouterContextProjector {

    SemanticRouterInput forRouter(BaseTurnContext base);
}
