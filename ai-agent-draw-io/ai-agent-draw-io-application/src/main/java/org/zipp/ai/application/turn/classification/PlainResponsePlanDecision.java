package org.zipp.ai.application.turn.classification;

/** Closed source-free response planning result used before any source I/O. */
public sealed interface PlainResponsePlanDecision
        permits PlainResponsePlanReady, PlainResponsePlanRejected {
}
