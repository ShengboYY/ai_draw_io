package org.zipp.ai.application.turn.agent;

import org.zipp.ai.application.turn.FencedAttempt;

import java.util.List;

/** Attempt-owned workspace; no method writes the committed canvas. */
public interface DiagramDraftStore {

    DiagramDraftSnapshot create(FencedAttempt attempt, String canvasXml);

    DiagramDraftSnapshot read(FencedAttempt attempt, DraftRef ref);

    DraftPatchResult patch(
            FencedAttempt attempt,
            DraftRef ref,
            String expectedDigest,
            List<DraftCellMutation> mutations);

    void discard(FencedAttempt attempt);
}
