package org.zipp.ai.infrastructure.adapter.filesystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zipp.ai.domain.ingestion.model.valobj.OriginalPromotionWork;
import org.zipp.ai.domain.material.model.valobj.MaterialObjectVersion;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemMaterialObjectAdapterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void promotesTheExactPinnedQuarantineVersionIntoImmutableLocalMaterials() throws Exception {
        Path quarantineRoot = temporaryDirectory.resolve("uploads");
        Path materialsRoot = temporaryDirectory.resolve("materials");
        byte[] content = "verified local material".getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256(content);
        var quarantine = new FileSystemQuarantineObjectAdapter(quarantineRoot);
        quarantine.write("quarantine", "incoming/owner/upload/object",
                new ByteArrayInputStream(content), content.length);
        String sourceVersion = quarantine.headLatestVersion(
                "quarantine", "incoming/owner/upload/object").orElseThrow().versionId();
        var work = new OriginalPromotionWork(
                "upl_1", 1, "quarantine", "incoming/owner/upload/object", sourceVersion,
                "original/owner/blob", "blob_1", "mat_1", "ver_1", "rev_1",
                "application/pdf", content.length, sha256, "d".repeat(64), null);

        var promoted = new FileSystemMaterialObjectAdapter(quarantineRoot, materialsRoot).promote(work);

        assertEquals("original/owner/blob", promoted.objectKey());
        assertEquals(sha256, promoted.objectVersionId());
        assertEquals(sha256, promoted.eTag());
        assertEquals(content.length, promoted.byteSize());
        assertArrayEquals(content, Files.readAllBytes(
                materialsRoot.resolve("original/owner/blob.versions").resolve(sha256)));
    }

    @Test
    void storesFindsReadsDownloadsAndDeletesAnExactRevisionArtifact() throws Exception {
        Path materialsRoot = temporaryDirectory.resolve("materials");
        var adapter = new FileSystemMaterialObjectAdapter(
                temporaryDirectory.resolve("uploads"), materialsRoot);
        byte[] content = "canonical page".getBytes(StandardCharsets.UTF_8);

        var stored = adapter.putImmutable(
                "revisions/rev_1/pages/1/canonical-page.json.gz", content, "application/gzip");
        var found = adapter.findImmutable(stored.objectKey(), stored.contentType(), 1024).orElseThrow();
        Path destination = temporaryDirectory.resolve("downloaded-artifact");

        assertEquals(stored, found);
        assertArrayEquals(content, adapter.read(stored, 1024));
        assertEquals(destination, adapter.download(stored, 1024, destination));
        assertArrayEquals(content, Files.readAllBytes(destination));
        assertTrue(adapter.deleteExact(stored));
        assertFalse(adapter.findImmutable(stored.objectKey(), stored.contentType(), 1024).isPresent());
    }

    @Test
    void materialDeletionRemovesOnlyTheRecordedLocalVersion() {
        var adapter = new FileSystemMaterialObjectAdapter(
                temporaryDirectory.resolve("uploads"), temporaryDirectory.resolve("materials"));
        var stored = adapter.putImmutable("revisions/rev_1/manifest.json",
                "{}".getBytes(StandardCharsets.UTF_8), "application/json");

        var receipt = adapter.delete(List.of(new MaterialObjectVersion(
                "MATERIALS", stored.objectKey(), stored.objectVersionId())));

        assertEquals(1, receipt.deletedCount());
        assertTrue(receipt.allRequestedHandled());
        assertFalse(adapter.findImmutable(stored.objectKey(), stored.contentType(), 1024).isPresent());
    }

    @Test
    void discardedPromotionDoesNotDeleteAContentAddressedVersionSharedByAnotherCommit() throws Exception {
        Path quarantineRoot = temporaryDirectory.resolve("uploads");
        Path materialsRoot = temporaryDirectory.resolve("materials");
        byte[] content = "shared content".getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256(content);
        var quarantine = new FileSystemQuarantineObjectAdapter(quarantineRoot);
        quarantine.write("quarantine", "incoming/shared",
                new ByteArrayInputStream(content), content.length);
        var work = new OriginalPromotionWork(
                "upl_1", 1, "quarantine", "incoming/shared", sha256,
                "original/shared/blob", "blob_1", "mat_1", "ver_1", "rev_1",
                "application/pdf", content.length, sha256, "d".repeat(64), null);
        var adapter = new FileSystemMaterialObjectAdapter(quarantineRoot, materialsRoot);
        var first = adapter.promote(work);
        var concurrentRetry = adapter.promote(work);

        adapter.discard(concurrentRetry);

        assertArrayEquals(content, Files.readAllBytes(
                materialsRoot.resolve("original/shared/blob.versions").resolve(first.objectVersionId())));
    }

    @Test
    void concurrentDifferentContentCannotReplaceAnImmutableArtifactKey() throws Exception {
        Path uploads = temporaryDirectory.resolve("uploads");
        Path materials = temporaryDirectory.resolve("materials");
        var firstAdapter = new FileSystemMaterialObjectAdapter(uploads, materials);
        var secondAdapter = new FileSystemMaterialObjectAdapter(uploads, materials);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> putAfter(start, firstAdapter, "first", succeeded, rejected));
            var second = executor.submit(() -> putAfter(start, secondAdapter, "second", succeeded, rejected));
            start.countDown();
            first.get();
            second.get();
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, succeeded.get());
        assertEquals(1, rejected.get());
        assertTrue(firstAdapter.findImmutable("revisions/rev_1/immutable.json",
                "application/json", 1024).isPresent());
    }

    @Test
    void crossInstanceWriteAndDeleteNeverLeaveADanglingCurrentMarker() throws Exception {
        Path uploads = temporaryDirectory.resolve("uploads");
        Path materials = temporaryDirectory.resolve("materials");
        var writer = new FileSystemMaterialObjectAdapter(uploads, materials);
        var deleter = new FileSystemMaterialObjectAdapter(uploads, materials);
        var executor = Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 25; attempt++) {
                String key = "revisions/rev_1/race-" + attempt + ".json";
                byte[] content = ("content-" + attempt).getBytes(StandardCharsets.UTF_8);
                var stored = writer.putImmutable(key, content, "application/json");
                CountDownLatch start = new CountDownLatch(1);
                var rewrite = executor.submit(() -> {
                    await(start);
                    writer.putImmutable(key, content, "application/json");
                });
                var deletion = executor.submit(() -> {
                    await(start);
                    deleter.deleteExact(stored);
                });
                start.countDown();
                rewrite.get();
                deletion.get();

                var current = writer.findImmutable(key, "application/json", 1024);
                if (current.isPresent()) {
                    assertArrayEquals(content, writer.read(current.orElseThrow(), 1024));
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsSymbolicLinkComponentsThatEscapeTheMaterialsRoot() throws Exception {
        Path materialsRoot = temporaryDirectory.resolve("materials");
        Path outside = temporaryDirectory.resolve("outside");
        Files.createDirectories(materialsRoot);
        Files.createDirectories(outside);
        Files.createSymbolicLink(materialsRoot.resolve("escape"), outside);
        var adapter = new FileSystemMaterialObjectAdapter(
                temporaryDirectory.resolve("uploads"), materialsRoot);

        assertThrows(IllegalArgumentException.class, () -> adapter.putImmutable(
                "escape/artifact.json", "{}".getBytes(StandardCharsets.UTF_8), "application/json"));
    }

    private static void putAfter(CountDownLatch start, FileSystemMaterialObjectAdapter adapter, String content,
                                 AtomicInteger succeeded, AtomicInteger rejected) {
        try {
            start.await();
            adapter.putImmutable("revisions/rev_1/immutable.json",
                    content.getBytes(StandardCharsets.UTF_8), "application/json");
            succeeded.incrementAndGet();
        } catch (IllegalStateException expected) {
            rejected.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
