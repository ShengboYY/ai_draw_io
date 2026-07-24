package org.zipp.ai.infrastructure.adapter.filesystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemQuarantineObjectAdapterTest {

    @TempDir
    Path root;

    @Test
    void storesUploadAndExposesStableVersionMetadata() throws Exception {
        byte[] content = "local material".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var adapter = new FileSystemQuarantineObjectAdapter(root);

        adapter.write("quarantine", "incoming/owner/upload/object", new ByteArrayInputStream(content),
                content.length);

        var stored = adapter.headLatestVersion("quarantine", "incoming/owner/upload/object").orElseThrow();
        assertEquals(content.length, stored.byteSize());
        Path downloaded = root.resolve("downloaded");
        var localCopy = adapter.downloadPinnedVersion(
                "quarantine", "incoming/owner/upload/object", stored.versionId(), 1024, downloaded);
        assertEquals(stored.versionId(), localCopy.contentSha256());
        assertEquals(content.length, localCopy.byteSize());
    }

    @Test
    void rejectsWrongLengthWithoutPublishingPartialFile() {
        var adapter = new FileSystemQuarantineObjectAdapter(root);

        assertThrows(IllegalArgumentException.class, () ->
                adapter.write("quarantine", "incoming/owner/upload/object",
                        new ByteArrayInputStream(new byte[]{1, 2}), 3));

        assertTrue(adapter.headLatestVersion("quarantine", "incoming/owner/upload/object").isEmpty());
    }

    @Test
    void rejectsPathsOutsideConfiguredRoot() {
        var adapter = new FileSystemQuarantineObjectAdapter(root);

        assertThrows(IllegalArgumentException.class, () ->
                adapter.write("quarantine", "../escape", new ByteArrayInputStream(new byte[]{1}), 1));
    }

    @Test
    void pinnedVersionRemainsReadableAfterAReplacementUpload() throws Exception {
        var adapter = new FileSystemQuarantineObjectAdapter(root);
        byte[] first = "first version".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] second = "second version".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        adapter.write("quarantine", "incoming/owner/upload/object", new ByteArrayInputStream(first), first.length);
        String pinned = adapter.headLatestVersion(
                "quarantine", "incoming/owner/upload/object").orElseThrow().versionId();

        adapter.write("quarantine", "incoming/owner/upload/object", new ByteArrayInputStream(second), second.length);

        Path downloaded = root.resolve("pinned-copy");
        adapter.downloadPinnedVersion("quarantine", "incoming/owner/upload/object", pinned, 1024, downloaded);
        assertEquals("first version", java.nio.file.Files.readString(downloaded));
    }
}
