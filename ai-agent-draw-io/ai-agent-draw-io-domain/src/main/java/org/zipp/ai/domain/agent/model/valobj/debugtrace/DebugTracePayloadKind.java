package org.zipp.ai.domain.agent.model.valobj.debugtrace;

/** Stable payload semantics shared by run, step, LLM, and tool spans. */
public enum DebugTracePayloadKind {
    INPUT,
    OUTPUT,
    TOOL_ARGS,
    TOOL_RESULT,
    ERROR
}
