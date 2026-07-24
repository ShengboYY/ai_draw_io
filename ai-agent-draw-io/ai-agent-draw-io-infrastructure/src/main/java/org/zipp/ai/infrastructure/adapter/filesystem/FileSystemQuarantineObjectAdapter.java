package org.zipp.ai.infrastructure.adapter.filesystem;

import org.zipp.ai.domain.ingestion.model.valobj.DownloadedQuarantineObject;
import org.zipp.ai.domain.ingestion.model.valobj.QuarantineObjectVersion;
import org.zipp.ai.domain.ingestion.port.PinnedQuarantineContentPort;
import org.zipp.ai.domain.ingestion.port.QuarantineObjectPort;
import org.zipp.ai.domain.ingestion.port.QuarantineObjectWriterPort;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

public final class FileSystemQuarantineObjectAdapter
        implements QuarantineObjectPort, QuarantineObjectWriterPort, PinnedQuarantineContentPort {

    private final Path root;

    public FileSystemQuarantineObjectAdapter(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void write(String bucket, String objectKey, InputStream content, long expectedSize) {
        if (expectedSize < 1) {
            throw new IllegalArgumentException("expectedSize must be positive");
        }
        Path object = resolve(bucket, objectKey);
        Path temporary = null;
        try {
            Files.createDirectories(object.getParent());
            temporary = Files.createTempFile(object.getParent(), ".material-upload-", ".tmp");
            long written = copyBounded(content, temporary, expectedSize);
            if (written != expectedSize) {
                throw new IllegalArgumentException("uploaded object size does not match declaration");
            }
            String versionId = HexFormat.of().formatHex(digest(temporary));
            Path version = versionPath(object, versionId);
            Files.createDirectories(version.getParent());
            publishImmutableVersion(temporary, version);
            temporary = null;
            publishLatestVersion(object, versionId);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to store local material upload", e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The unpublished temporary file can be removed by routine local cleanup.
                }
            }
        }
    }

    @Override
    public Optional<QuarantineObjectVersion> headLatestVersion(String bucket, String objectKey) {
        Path object = resolve(bucket, objectKey);
        Path marker = latestMarker(object);
        if (!Files.isRegularFile(marker)) {
            return Optional.empty();
        }
        try {
            String versionId = requireVersionId(Files.readString(marker));
            Path version = versionPath(object, versionId);
            if (!Files.isRegularFile(version)) {
                return Optional.empty();
            }
            byte[] digest = digest(version);
            String checksum = Base64.getEncoder().encodeToString(digest);
            String eTag = HexFormat.of().formatHex(digest);
            return Optional.of(new QuarantineObjectVersion(versionId, eTag, checksum, Files.size(version)));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to inspect local material upload", e);
        }
    }

    @Override
    public DownloadedQuarantineObject downloadPinnedVersion(String bucket, String objectKey, String versionId,
                                                             long maximumBytes, Path destination) {
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        Path object = resolve(bucket, objectKey);
        String pinnedVersion = requireVersionId(versionId);
        Path version = versionPath(object, pinnedVersion);
        Path target = java.util.Objects.requireNonNull(destination, "destination");
        try {
            long size = Files.size(version);
            if (size < 1 || size > maximumBytes) {
                throw new IllegalArgumentException("pinned object exceeds its bounded intake size");
            }
            Files.copy(version, target, StandardCopyOption.REPLACE_EXISTING);
            String contentSha256 = HexFormat.of().formatHex(digest(target));
            if (!contentSha256.equals(pinnedVersion)) {
                throw new IllegalStateException("pinned local object failed content verification");
            }
            return new DownloadedQuarantineObject(
                    objectKey + "?versionId=" + pinnedVersion, target, size, contentSha256);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read local material upload", e);
        }
    }

    @Override
    public boolean deletePinnedVersion(String bucket, String objectKey, String versionId) {
        Path object = resolve(bucket, objectKey);
        String pinnedVersion = requireVersionId(versionId);
        try {
            boolean deleted = Files.deleteIfExists(versionPath(object, pinnedVersion));
            if (Files.isRegularFile(latestMarker(object))
                    && pinnedVersion.equals(Files.readString(latestMarker(object)).trim())) {
                Files.deleteIfExists(latestMarker(object));
            }
            return deleted;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to delete local material upload", e);
        }
    }

    private Path resolve(String bucket, String objectKey) {
        String normalizedBucket = requireText(bucket, "bucket");
        String normalizedKey = requireText(objectKey, "objectKey");
        Path keyPath = Path.of(normalizedKey);
        boolean containsTraversal = false;
        for (Path part : keyPath) {
            containsTraversal |= part.toString().equals("..");
        }
        if (keyPath.isAbsolute() || containsTraversal) {
            throw new IllegalArgumentException("object path must not contain traversal segments");
        }
        Path target = root.resolve(normalizedBucket).resolve(normalizedKey).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("object path must stay inside the configured root");
        }
        return target;
    }

    private static long copyBounded(InputStream content, Path target, long expectedSize) throws IOException {
        long total = 0;
        byte[] buffer = new byte[16 * 1024];
        try (InputStream input = java.util.Objects.requireNonNull(content, "content");
             OutputStream output = Files.newOutputStream(target)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > expectedSize) {
                    throw new IllegalArgumentException("uploaded object size does not match declaration");
                }
                output.write(buffer, 0, read);
            }
        }
        return total;
    }

    private static void publishImmutableVersion(Path source, Path target) throws IOException {
        if (Files.isRegularFile(target)) {
            Files.delete(source);
            return;
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            moveWithoutReplacing(source, target);
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            // Concurrent identical content shares the same immutable digest version.
            Files.deleteIfExists(source);
        }
    }

    private static void moveWithoutReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target);
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            Files.deleteIfExists(source);
        }
    }

    private static void publishLatestVersion(Path object, String versionId) throws IOException {
        Path marker = latestMarker(object);
        Path temporary = Files.createTempFile(marker.getParent(), ".material-latest-", ".tmp");
        try {
            Files.writeString(temporary, versionId);
            try {
                Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path latestMarker(Path object) {
        return object.resolveSibling(object.getFileName() + ".latest");
    }

    private static Path versionPath(Path object, String versionId) {
        return object.resolveSibling(object.getFileName() + ".versions").resolve(requireVersionId(versionId));
    }

    private static byte[] digest(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String requireVersionId(String value) {
        String normalized = requireText(value, "versionId");
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("versionId must be a SHA-256 digest");
        }
        return normalized;
    }
}
