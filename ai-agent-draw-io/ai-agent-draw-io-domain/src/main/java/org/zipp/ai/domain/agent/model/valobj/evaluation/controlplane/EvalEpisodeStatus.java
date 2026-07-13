package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

/** Per case/repetition outcome with infrastructure states kept separate. */
public enum EvalEpisodeStatus {
    PASS,
    FAIL,
    ERROR,
    UNAVAILABLE
}
