package org.zipp.ai.domain.agent.service.evaluation;

/** Marks an episode as unscorable infrastructure ERROR and eligible for whole-episode retry. */
public class EvalInfrastructureException extends RuntimeException {
    public EvalInfrastructureException(String message, Throwable cause) { super(message, cause); }
    public EvalInfrastructureException(String message) { super(message); }
}
