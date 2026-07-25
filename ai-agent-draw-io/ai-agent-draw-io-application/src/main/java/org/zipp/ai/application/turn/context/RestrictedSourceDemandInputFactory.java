package org.zipp.ai.application.turn.context;

import org.zipp.ai.application.turn.demand.RestrictedSourceDemandInput;

public interface RestrictedSourceDemandInputFactory {

    RestrictedSourceDemandInput create(BaseTurnContext base);
}
