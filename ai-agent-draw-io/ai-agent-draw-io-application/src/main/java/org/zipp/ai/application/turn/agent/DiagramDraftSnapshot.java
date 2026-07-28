package org.zipp.ai.application.turn.agent;

/** Immutable XML state stored inside one fenced attempt. */
public record DiagramDraftSnapshot(
        DraftRef ref,
        String digest,
        String canvasXml,
        int version
) {

    public DiagramDraftSnapshot {
        if (ref == null || !sha256Digest(digest)
                || canvasXml == null || canvasXml.isBlank() || version <= 0) {
            throw new IllegalArgumentException("diagram draft snapshot is invalid");
        }
        canvasXml = canvasXml.trim();
    }

    private static boolean sha256Digest(String value) {
        if (value == null) {
            return false;
        }
        String hex = value.startsWith("sha256:") ? value.substring("sha256:".length()) : value;
        return hex.length() == 64 && hex.chars().allMatch(character ->
                (character >= '0' && character <= '9')
                        || (character >= 'a' && character <= 'f'));
    }
}
