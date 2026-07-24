package org.zipp.ai.domain.grounding;

import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasStateSaveResult;

import java.util.List;

public record CanvasCommitResult(boolean committed, String canvasXml, CanvasStateSaveResult saveResult,
                                 List<String> errors, FailureKind failureKind) {
    public CanvasCommitResult { errors = List.copyOf(errors == null ? List.of() : errors); }

    public static CanvasCommitResult committed(String xml, CanvasStateSaveResult result) {
        return new CanvasCommitResult(true, xml, result, List.of(), FailureKind.NONE);
    }

    public static CanvasCommitResult rejected(String xml, List<String> errors) {
        return new CanvasCommitResult(false, xml, null, errors, FailureKind.REJECTED);
    }

    public static CanvasCommitResult unavailable(String xml, String error) {
        return new CanvasCommitResult(
                false, xml, null, List.of(error), FailureKind.UNAVAILABLE);
    }

    public enum FailureKind { NONE, REJECTED, UNAVAILABLE }
}
