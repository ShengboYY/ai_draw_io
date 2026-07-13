package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.zipp.ai.infrastructure.adapter.repository.evaluation.controlplane.FileSystemEvalCaseArtifactStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.Assert.*;

public class FileSystemEvalCaseArtifactStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void contentAddressedArtifactIsIdempotentAndRootScoped() throws Exception {
        FileSystemEvalCaseArtifactStore store = new FileSystemEvalCaseArtifactStore(temporary.newFolder("artifacts").getPath());
        byte[] content = "{\"caseId\":\"synthetic\"}".getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));

        String first = store.putIfAbsent(hash, content);
        String second = store.putIfAbsent(hash, content);

        assertEquals(first, second);
        assertArrayEquals(content, store.read(first).orElseThrow());
        assertThrows(SecurityException.class, () -> store.read(temporary.newFile("outside.json").getPath()));
    }

    @Test
    public void artifactRootCannotPointInsideTheProductCheckout() {
        assertThrows(IllegalArgumentException.class,
                () -> new FileSystemEvalCaseArtifactStore("target/eval-artifacts"));
    }
}
