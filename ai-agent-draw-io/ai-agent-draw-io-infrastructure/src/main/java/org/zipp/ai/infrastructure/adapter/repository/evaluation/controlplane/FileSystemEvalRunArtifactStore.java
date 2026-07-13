package org.zipp.ai.infrastructure.adapter.repository.evaluation.controlplane;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.IEvalRunArtifactStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/** Repository-external storage for synthetic run evidence; production Debug Trace is never copied here. */
@Repository
public class FileSystemEvalRunArtifactStore implements IEvalRunArtifactStore {
    private final Path root;
    public FileSystemEvalRunArtifactStore(@Value("${zipp.evaluation.artifact-root:${java.io.tmpdir}/freedraw-eval-artifacts}") String configuredRoot) {
        this.root = Path.of(configuredRoot).toAbsolutePath().normalize().resolve("runs");
        if (this.root.startsWith(Path.of("").toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Eval artifact root must be outside the product working directory");
        }
    }
    @Override public String put(String runId, String episodeId, String type, byte[] content) {
        String safeRun = safe(runId, "runId"); String safeEpisode = safe(episodeId, "episodeId"); String safeType = safe(type, "artifactType");
        try {
            Path directory = root.resolve(safeRun).normalize();
            if (!directory.startsWith(root)) throw new SecurityException("run artifact path escapes the configured root");
            Files.createDirectories(directory);
            Path target = directory.resolve(safeEpisode + "-" + safeType + ".json");
            Path temporary = Files.createTempFile(directory, "eval-", ".tmp");
            Files.write(temporary, content);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
            return target.toString();
        } catch (Exception e) { throw new IllegalStateException("failed to store Eval Run artifact", e); }
    }
    @Override public Optional<byte[]> read(String ref) {
        if (ref == null) return Optional.empty();
        Path path = Path.of(ref).toAbsolutePath().normalize();
        if (!path.startsWith(root)) throw new SecurityException("run artifact reference is outside the configured root");
        try { return Files.exists(path) ? Optional.of(Files.readAllBytes(path)) : Optional.empty(); }
        catch (Exception e) { throw new IllegalStateException("failed to read Eval Run artifact", e); }
    }
    private String safe(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,179}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }
}
