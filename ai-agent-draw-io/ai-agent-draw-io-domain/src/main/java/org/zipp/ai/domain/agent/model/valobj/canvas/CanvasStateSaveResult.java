package org.zipp.ai.domain.agent.model.valobj.canvas;

public class CanvasStateSaveResult {

    private final CanvasStateSaveStatus status;
    private final CanvasState state;

    private CanvasStateSaveResult(CanvasStateSaveStatus status, CanvasState state) {
        this.status = status;
        this.state = state;
    }

    public static CanvasStateSaveResult created(CanvasState state) {
        return new CanvasStateSaveResult(CanvasStateSaveStatus.CREATED, state);
    }

    public static CanvasStateSaveResult updated(CanvasState state) {
        return new CanvasStateSaveResult(CanvasStateSaveStatus.UPDATED, state);
    }

    public static CanvasStateSaveResult noop(CanvasState state) {
        return new CanvasStateSaveResult(CanvasStateSaveStatus.NOOP, state);
    }

    public CanvasStateSaveStatus getStatus() {
        return status;
    }

    public CanvasState getState() {
        return state;
    }
}
