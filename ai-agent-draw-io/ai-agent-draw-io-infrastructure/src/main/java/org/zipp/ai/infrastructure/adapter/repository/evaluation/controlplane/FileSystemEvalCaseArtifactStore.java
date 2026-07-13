package org.zipp.ai.infrastructure.adapter.repository.evaluation.controlplane;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.IEvalCaseArtifactStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/** Content-addressed local adapter; its configured root must remain outside the product Git checkout. */
@Repository
public class FileSystemEvalCaseArtifactStore implements IEvalCaseArtifactStore {
    private final Path root;
    public FileSystemEvalCaseArtifactStore(@Value("${zipp.evaluation.artifact-root:${java.io.tmpdir}/freedraw-eval-artifacts}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        if (this.root.startsWith(workingDirectory)) {
            throw new IllegalArgumentException("Eval artifact root must be outside the product working directory");
        }
    }
    @Override public String putIfAbsent(String hash, byte[] content) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("valid SHA-256 content hash is required");
        try {
            String actual = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(content));
            if (!hash.equals(actual)) throw new IllegalArgumentException("content does not match its SHA-256 hash");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        try {
            Files.createDirectories(root);
            Path target = root.resolve(hash + ".json");
            if (!Files.exists(target)) {
                Path temporary = Files.createTempFile(root, hash, ".tmp");
                Files.write(temporary, content);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.FileAlreadyExistsException ignored) {
                    // A concurrent publisher won the same content hash; both bytes are canonical and identical.
                    Files.deleteIfExists(temporary);
                }
            }
            return target.toString();
        } catch (IOException e) { throw new IllegalStateException("failed to store Eval Case artifact", e); }
    }
    @Override public Optional<byte[]> read(String ref) {
        if (ref == null) return Optional.empty();
        Path path = Path.of(ref).toAbsolutePath().normalize();
        if (!path.startsWith(root)) throw new SecurityException("artifact reference is outside the configured root");
        try { return Files.exists(path) ? Optional.of(Files.readAllBytes(path)) : Optional.empty(); }
        catch (IOException e) { throw new IllegalStateException("failed to read Eval Case artifact", e); }
    }
}
