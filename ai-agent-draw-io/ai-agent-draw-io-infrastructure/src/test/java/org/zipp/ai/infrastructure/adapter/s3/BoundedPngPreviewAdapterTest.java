package org.zipp.ai.infrastructure.adapter.s3;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.ingestion.port.RevisionArtifactPort;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BoundedPngPreviewAdapterTest {
    @Test
    void readsTheExactObjectVersionAndBoundsTheRenderedImage() throws Exception {
        BufferedImage source = new BufferedImage(2400, 1200, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(source, "png", bytes));
        StoredArtifact artifact = new StoredArtifact("revisions/rev/pages/1/page.png", "version-7",
                "a".repeat(64), bytes.size(), "image/png");
        ExactReadArtifacts artifacts = new ExactReadArtifacts(bytes.toByteArray());

        var preview = new BoundedPngPreviewAdapter(artifacts).render(artifact);

        BufferedImage rendered = ImageIO.read(new java.io.ByteArrayInputStream(preview.bytes()));
        assertSame(artifact, artifacts.requested);
        assertEquals(1600, rendered.getWidth());
        assertEquals(800, rendered.getHeight());
        assertEquals("image/png", preview.contentType());
        assertEquals(64, preview.contentSha256().length());
    }

    private static final class ExactReadArtifacts implements RevisionArtifactPort {
        private final byte[] bytes;
        private StoredArtifact requested;

        private ExactReadArtifacts(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override public StoredArtifact putImmutable(String key, byte[] content, String type) {
            throw new UnsupportedOperationException();
        }
        @Override public byte[] read(StoredArtifact artifact, long maximumBytes) {
            requested = artifact;
            assertTrue(maximumBytes >= bytes.length);
            return bytes.clone();
        }
        @Override public Path download(StoredArtifact artifact, long maximumBytes, Path destination) {
            throw new UnsupportedOperationException();
        }
    }
}
