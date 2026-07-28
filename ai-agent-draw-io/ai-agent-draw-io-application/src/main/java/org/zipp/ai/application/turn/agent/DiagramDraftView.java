package org.zipp.ai.application.turn.agent;

/** XML-free draft identity returned to the model after mutation. */
public record DiagramDraftView(
        DraftRef ref,
        String digest,
        int version
) {

    public DiagramDraftView {
        if (ref == null || digest == null || digest.isBlank() || version <= 0) {
            throw new IllegalArgumentException("diagram draft view is invalid");
        }
        digest = digest.trim();
    }

    public static DiagramDraftView from(DiagramDraftSnapshot snapshot) {
        return new DiagramDraftView(snapshot.ref(), snapshot.digest(), snapshot.version());
    }
}
