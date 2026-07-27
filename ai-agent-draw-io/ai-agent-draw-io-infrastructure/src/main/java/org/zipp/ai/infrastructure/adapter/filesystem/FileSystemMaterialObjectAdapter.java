package org.zipp.ai.infrastructure.adapter.filesystem;

import org.zipp.ai.domain.ingestion.model.valobj.OriginalPromotionWork;
import org.zipp.ai.domain.ingestion.model.valobj.PromotedOriginal;
import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.ingestion.port.OriginalPromotionPort;
import org.zipp.ai.domain.ingestion.port.RevisionArtifactPort;
import org.zipp.ai.domain.material.model.valobj.MaterialDeletionReceipt;
import org.zipp.ai.domain.material.model.valobj.MaterialObjectVersion;
import org.zipp.ai.domain.material.port.MaterialDeletionObjectPort;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Stores immutable promoted originals on a shared local filesystem. */
public final class FileSystemMaterialObjectAdapter
        implements OriginalPromotionPort, RevisionArtifactPort, MaterialDeletionObjectPort {

    private static final ConcurrentHashMap<Path, Object> JVM_ROOT_LOCKS = new ConcurrentHashMap<>();

    private final Path quarantineRoot;
    private final Path materialsRoot;

    public FileSystemMaterialObjectAdapter(Path quarantineRoot, Path materialsRoot) {
        this.quarantineRoot = normalizedRoot(quarantineRoot, "quarantineRoot");
        this.materialsRoot = normalizedRoot(materialsRoot, "materialsRoot");
    }

    @Override
    public PromotedOriginal promote(OriginalPromotionWork work) {
        OriginalPromotionWork source = Objects.requireNonNull(work, "work");
        String versionId = requireDigest(source.quarantineVersionId(), "quarantineVersionId");
        Path quarantineObject = resolve(quarantineRoot, source.quarantineBucket(), source.quarantineKey());
        Path sourceVersion = versionPath(quarantineRoot, quarantineObject, versionId);
        Path destinationObject = resolve(materialsRoot, source.destinationKey());
        Path destinationVersion = versionPath(materialsRoot, destinationObject,
                requireDigest(source.contentSha256(), "contentSha256"));
        try {
            return withMaterialLock(() -> {
                verify(sourceVersion, source.byteSize(), source.contentSha256());
                Files.createDirectories(destinationVersion.getParent());
                publishCopy(sourceVersion, destinationVersion);
                verify(destinationVersion, source.byteSize(), source.contentSha256());
                return new PromotedOriginal(source.destinationKey(), source.contentSha256(), source.contentSha256(),
                        Base64.getEncoder().encodeToString(HexFormat.of().parseHex(source.contentSha256())),
                        source.byteSize());
            });
        } catch (IOException e) {
            throw new UncheckedIOException("failed to promote local material original", e);
        }
    }

    @Override
    public StoredArtifact putImmutable(String objectKey, byte[] content, String contentType) {
        Path object = resolve(materialsRoot, objectKey);
        byte[] bytes = Objects.requireNonNull(content, "content").clone();
        if (bytes.length < 1) {
            throw new IllegalArgumentException("artifact content cannot be empty");
        }
        String type = requireMetadataText(contentType, "contentType");
        String versionId = sha256(bytes);
        try {
            return withMaterialLock(() -> {
                Optional<StoredArtifact> current = findImmutable(objectKey, type, Long.MAX_VALUE);
                if (current.isPresent()) {
                    if (!versionId.equals(current.get().contentSha256())) {
                        throw new IllegalStateException("immutable artifact key already contains different content");
                    }
                    return current.get();
                }
                Path version = versionPath(materialsRoot, object, versionId);
                Files.createDirectories(version.getParent());
                publishBytes(bytes, version);
                verify(version, bytes.length, versionId);
                publishCurrent(materialsRoot, object, versionId, type);
                StoredArtifact published = findImmutable(objectKey, type, Long.MAX_VALUE).orElseThrow();
                if (!versionId.equals(published.objectVersionId())) {
                    throw new IllegalStateException("immutable artifact key was concurrently published");
                }
                return published;
            });
        } catch (IOException e) {
            throw new UncheckedIOException("failed to store local revision artifact", e);
        }
    }

    @Override
    public Optional<StoredArtifact> findImmutable(String objectKey, String contentType, long maximumBytes) {
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        Path object = resolve(materialsRoot, objectKey);
        String expectedType = requireMetadataText(contentType, "contentType");
        Path marker = currentMarker(materialsRoot, object);
        if (!Files.isRegularFile(marker)) {
            return Optional.empty();
        }
        try {
            String[] metadata = Files.readString(marker).split("\\n", -1);
            if (metadata.length != 2) {
                throw new IllegalStateException("local artifact metadata is invalid");
            }
            String versionId = requireDigest(metadata[0], "objectVersionId");
            if (!expectedType.equals(metadata[1])) {
                throw new IllegalStateException("immutable artifact content type does not match");
            }
            Path version = versionPath(materialsRoot, object, versionId);
            long size = Files.size(version);
            if (size < 1 || size > maximumBytes) {
                throw new IllegalStateException("immutable artifact exceeds its bounded read size");
            }
            verify(version, size, versionId);
            return Optional.of(new StoredArtifact(objectKey, versionId, versionId, size, expectedType));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to inspect local revision artifact", e);
        }
    }

    @Override
    public boolean deleteExact(StoredArtifact artifact) {
        StoredArtifact exact = Objects.requireNonNull(artifact, "artifact");
        Path object = resolve(materialsRoot, exact.objectKey());
        try {
            return withMaterialLock(() -> {
                boolean deleted = Files.deleteIfExists(versionPath(materialsRoot, object,
                        requireDigest(exact.objectVersionId(), "objectVersionId")));
                Path marker = currentMarker(materialsRoot, object);
                if (Files.isRegularFile(marker)
                        && Files.readString(marker).startsWith(exact.objectVersionId() + "\n")) {
                    Files.deleteIfExists(marker);
                }
                return deleted;
            });
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public byte[] read(StoredArtifact artifact, long maximumBytes) {
        StoredArtifact expected = bounded(artifact, maximumBytes);
        Path version = versionPath(materialsRoot, resolve(materialsRoot, expected.objectKey()),
                requireDigest(expected.objectVersionId(), "objectVersionId"));
        try {
            verify(version, expected.byteSize(), expected.contentSha256());
            return Files.readAllBytes(version);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read local revision artifact", e);
        }
    }

    @Override
    public Path download(StoredArtifact artifact, long maximumBytes, Path destination) {
        StoredArtifact expected = bounded(artifact, maximumBytes);
        Path source = versionPath(materialsRoot, resolve(materialsRoot, expected.objectKey()),
                requireDigest(expected.objectVersionId(), "objectVersionId"));
        Path target = Objects.requireNonNull(destination, "destination");
        try {
            verify(source, expected.byteSize(), expected.contentSha256());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            verify(target, expected.byteSize(), expected.contentSha256());
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to download local revision artifact", e);
        }
    }

    @Override
    public MaterialDeletionReceipt delete(List<MaterialObjectVersion> objects) {
        List<MaterialObjectVersion> exactObjects = List.copyOf(
                Objects.requireNonNull(objects, "objects"));
        try {
            return withMaterialLock(() -> {
                java.util.ArrayList<String> operationIds = new java.util.ArrayList<>();
                for (MaterialObjectVersion objectVersion : exactObjects) {
                    Path object = resolve(materialsRoot, objectVersion.objectKey());
                    String versionId = requireDigest(objectVersion.objectVersionId(), "objectVersionId");
                // Local deletion is idempotent, matching S3 delete-version semantics.
                    Files.deleteIfExists(versionPath(materialsRoot, object, versionId));
                    Path marker = currentMarker(materialsRoot, object);
                    if (Files.isRegularFile(marker) && Files.readString(marker).startsWith(versionId + "\n")) {
                        Files.deleteIfExists(marker);
                    }
                    operationIds.add("local:" + objectVersion.bucket() + ":"
                            + objectVersion.objectKey() + ":" + versionId);
                }
                return MaterialDeletionReceipt.from(exactObjects.size(), operationIds);
            });
        } catch (IOException e) {
            throw new UncheckedIOException("failed to delete local material object", e);
        }
    }

    private static void publishCopy(Path source, Path destination) throws IOException {
        if (Files.isRegularFile(destination)) {
            return;
        }
        Path temporary = Files.createTempFile(destination.getParent(), ".material-original-", ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                moveWithoutReplacing(temporary, destination);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // Concurrent promotion of the same digest shares one immutable local version.
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveWithoutReplacing(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination);
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            // Another worker published the same verified digest first.
        }
    }

    private static void publishBytes(byte[] content, Path destination) throws IOException {
        if (Files.isRegularFile(destination)) {
            return;
        }
        Path temporary = Files.createTempFile(destination.getParent(), ".material-artifact-", ".tmp");
        try {
            Files.write(temporary, content);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                moveWithoutReplacing(temporary, destination);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // Concurrent identical artifact writes share the digest-addressed version.
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void publishCurrent(Path root, Path object, String versionId, String contentType)
            throws IOException {
        Path marker = currentMarker(root, object);
        Path temporary = Files.createTempFile(marker.getParent(), ".material-current-", ".tmp");
        try {
            Files.writeString(temporary, versionId + "\n" + contentType);
            try {
                Files.move(temporary, marker);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // Immutable keys may only retain the already-published identity.
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void verify(Path path, long expectedSize, String expectedSha256) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) != expectedSize
                || !expectedSha256.equals(sha256(path))) {
            throw new IllegalStateException("local material object failed immutable identity verification");
        }
    }

    private static Path resolve(Path root, String first, String... remaining) {
        Path target = root.resolve(requireRelative(first, "objectPath"));
        for (String part : remaining) {
            target = target.resolve(requireRelative(part, "objectPath"));
        }
        target = target.normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("object path must stay inside the configured root");
        }
        rejectSymbolicLinks(root, target);
        return target;
    }

    private static Path requireRelative(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        Path path = Path.of(value.trim());
        for (Path part : path) {
            if ("..".equals(part.toString())) {
                throw new IllegalArgumentException("object path must not contain traversal segments");
            }
        }
        if (path.isAbsolute()) {
            throw new IllegalArgumentException("object path must be relative");
        }
        return path;
    }

    private static Path versionPath(Path root, Path object, String versionId) {
        Path path = object.resolveSibling(object.getFileName() + ".versions").resolve(versionId);
        rejectSymbolicLinks(root, path);
        return path;
    }

    private static Path currentMarker(Path root, Path object) {
        Path path = object.resolveSibling(object.getFileName() + ".current");
        rejectSymbolicLinks(root, path);
        return path;
    }

    private static void rejectSymbolicLinks(Path root, Path target) {
        Path current = root;
        if (Files.isSymbolicLink(current)) {
            throw new IllegalArgumentException("configured storage root must not be a symbolic link");
        }
        for (Path part : root.relativize(target)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("object path must not contain symbolic links");
            }
        }
    }

    private static Path normalizedRoot(Path root, String field) {
        return Objects.requireNonNull(root, field).toAbsolutePath().normalize();
    }

    private static String requireDigest(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
        }
        return value;
    }

    private static String requireMetadataText(String value, String field) {
        if (value == null || value.isBlank() || value.contains("\n") || value.contains("\r")) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static StoredArtifact bounded(StoredArtifact artifact, long maximumBytes) {
        StoredArtifact expected = Objects.requireNonNull(artifact, "artifact");
        if (maximumBytes < 1 || expected.byteSize() > maximumBytes) {
            throw new IllegalArgumentException("artifact exceeds its bounded read size");
        }
        return expected;
    }

    private <T> T withMaterialLock(IoOperation<T> operation) throws IOException {
        Files.createDirectories(materialsRoot);
        Path lockPath = materialsRoot.resolve(".material-store.lock");
        rejectSymbolicLinks(materialsRoot, lockPath);
        Object jvmLock = JVM_ROOT_LOCKS.computeIfAbsent(materialsRoot, ignored -> new Object());
        synchronized (jvmLock) {
            try (FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var ignored = channel.lock()) {
                return operation.run();
            }
        }
    }

    @FunctionalInterface
    private interface IoOperation<T> {
        T run() throws IOException;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }
}
