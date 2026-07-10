UPDATE agent_debug_trace_capture
SET payload_kind = CASE
    WHEN payload_kind IN ('RUN_INPUT', 'STEP_INPUT', 'LLM_INPUT') THEN 'INPUT'
    WHEN payload_kind IN ('RUN_OUTPUT', 'STEP_OUTPUT', 'LLM_OUTPUT') THEN 'OUTPUT'
    WHEN payload_kind = 'TOOL_INPUT' THEN 'TOOL_ARGS'
    WHEN payload_kind = 'TOOL_OUTPUT' THEN 'TOOL_RESULT'
    ELSE payload_kind
END
WHERE payload_kind IN (
    'RUN_INPUT', 'STEP_INPUT', 'LLM_INPUT',
    'RUN_OUTPUT', 'STEP_OUTPUT', 'LLM_OUTPUT',
    'TOOL_INPUT', 'TOOL_OUTPUT'
);
