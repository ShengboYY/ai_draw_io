package org.zipp.ai.domain.agent.model.valobj.visualreview;

public enum CanvasVisualIssueSeverity {
    MINOR,
    MAJOR,
    CRITICAL;

    public boolean isBlocking() {
        return this == MAJOR || this == CRITICAL;
    }
}
