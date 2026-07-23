package org.zipp.ai.ingestion.worker.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Resolves only publisher-supplied identities; evaluator task and answer files are never inputs. */
final class SourceEvidenceIdentityManifest {

    private static final String SCHEMA_VERSION = "material-rag-source-evidence-identities-v1";
    private final List<Identity> identities;

    private SourceEvidenceIdentityManifest(List<Identity> identities) {
        this.identities = List.copyOf(identities);
    }

    static SourceEvidenceIdentityManifest load(Path path, ObjectMapper json) throws IOException {
        JsonNode payload = json.readTree(path.toFile());
        if (!SCHEMA_VERSION.equals(payload.path("schemaVersion").asText())) {
            throw new IllegalArgumentException("unexpected source evidence identity schema");
        }
        List<Identity> identities = new ArrayList<>();
        for (JsonNode node : payload.path("identities")) {
            String id = node.path("sourceEvidenceId").asText();
            String sourceVersion = node.path("sourceVersion").asText();
            int page = node.path("page").asInt();
            JsonNode match = node.path("match");
            String kind = match.path("kind").asText();
            String text = match.path("text").asText("");
            if (id.isBlank() || sourceVersion.isBlank() || page < 1
                    || !("exact_text".equals(kind) || "visual_page".equals(kind))
                    || ("exact_text".equals(kind) && text.isBlank())) {
                throw new IllegalArgumentException("source evidence identity is invalid");
            }
            identities.add(new Identity(id, sourceVersion, page, kind, text));
        }
        return new SourceEvidenceIdentityManifest(identities);
    }

    List<String> resolve(String sourceVersion, int page, String modality, String retrievalText) {
        String normalizedText = normalize(retrievalText);
        return identities.stream()
                .filter(identity -> identity.sourceVersion().equals(sourceVersion) && identity.page() == page)
                .filter(identity -> "visual_page".equals(identity.kind())
                        ? "VISUAL".equals(modality)
                        : normalizedText.contains(normalize(identity.text())))
                .map(Identity::sourceEvidenceId)
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim().toLowerCase(java.util.Locale.ROOT);
    }

    private record Identity(String sourceEvidenceId, String sourceVersion, int page, String kind, String text) { }
}
