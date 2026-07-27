package org.zipp.ai.ingestion.worker.document;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.model.valobj.NormalizedBoundingBox;
import org.zipp.ai.domain.ingestion.model.valobj.VisualCandidate;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualCropDeriverTest {

    @Test
    void derivesTheExactUnionOfNormalizedSourceRegionsAsLosslessPng() throws Exception {
        BufferedImage page = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);
        var graphics = page.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 100, 80);
        graphics.dispose();
        ByteArrayOutputStream encodedPage = new ByteArrayOutputStream();
        ImageIO.write(page, "png", encodedPage);
        VisualCandidate candidate = new VisualCandidate("vis_1", 1,
                List.of(new NormalizedBoundingBox(0.25, 0.25, 0.75, 0.75)), null);

        byte[] crop = new VisualCropDeriver(25_000_000, 10 * 1024 * 1024)
                .derive(encodedPage.toByteArray(), candidate);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(crop));
        assertEquals(50, decoded.getWidth());
        assertEquals(40, decoded.getHeight());
    }

    @Test
    void rejectsImagesFromTheirHeaderBeforeTheOversizedRasterIsDecoded() throws Exception {
        BufferedImage page = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream encodedPage = new ByteArrayOutputStream();
        ImageIO.write(page, "png", encodedPage);
        VisualCandidate candidate = new VisualCandidate("vis_1", 1,
                List.of(new NormalizedBoundingBox(0, 0, 1, 1)), null);

        // IHDR is sufficient to read dimensions but not to decode the raster body.
        byte[] headerOnly = Arrays.copyOf(encodedPage.toByteArray(), 33);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new VisualCropDeriver(399, 1024 * 1024).derive(headerOnly, candidate));

        assertTrue(failure.getMessage().contains("pixel budget"));
    }

    @Test
    void abortsEncodingWhenTheOutputBudgetIsCrossed() throws Exception {
        BufferedImage page = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream encodedPage = new ByteArrayOutputStream();
        ImageIO.write(page, "png", encodedPage);
        VisualCandidate candidate = new VisualCandidate("vis_1", 1,
                List.of(new NormalizedBoundingBox(0, 0, 1, 1)), null);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new VisualCropDeriver(400, 16).derive(encodedPage.toByteArray(), candidate));

        assertTrue(failure.getMessage().contains("encoded byte budget"));
    }

    @Test
    void fingerprintsCropAlgorithmRuntimeAndLimits() {
        VisualCropDeriver baseline = new VisualCropDeriver(400, 1024);

        assertTrue(baseline.fingerprint().contains("visual-crop-v1"));
        assertNotEquals(baseline.fingerprint(), new VisualCropDeriver(399, 1024).fingerprint());
        assertNotEquals(baseline.fingerprint(), new VisualCropDeriver(400, 1023).fingerprint());
    }
}
