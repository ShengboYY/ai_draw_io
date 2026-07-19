package org.zipp.ai.ingestion.worker.document;

import org.zipp.ai.domain.ingestion.model.valobj.VisualCandidate;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Objects;

/** Produces a lossless local crop from normalized regions without model or network access. */
public final class VisualCropDeriver {

    private final long maximumDecodedPixels;
    private final int maximumEncodedBytes;

    public VisualCropDeriver(long maximumDecodedPixels, int maximumEncodedBytes) {
        if (maximumDecodedPixels < 1 || maximumEncodedBytes < 1) {
            throw new IllegalArgumentException("visual crop limits must be positive");
        }
        this.maximumDecodedPixels = maximumDecodedPixels;
        this.maximumEncodedBytes = maximumEncodedBytes;
    }

    /** Identifies every setting that can change crop bytes or acceptance. */
    public String fingerprint() {
        return "visual-crop-v1:union-bbox:imageio-png:jvm=" + Runtime.version()
                + ":max-decoded-pixels=" + maximumDecodedPixels
                + ":max-encoded-bytes=" + maximumEncodedBytes;
    }

    public byte[] derive(byte[] pageImage, VisualCandidate candidate) {
        Objects.requireNonNull(pageImage, "pageImage");
        VisualCandidate source = Objects.requireNonNull(candidate, "candidate");
        try {
            BufferedImage page = decodeWithinBudget(pageImage);
            double x1 = source.regions().stream().mapToDouble(region -> region.x1()).min().orElseThrow();
            double y1 = source.regions().stream().mapToDouble(region -> region.y1()).min().orElseThrow();
            double x2 = source.regions().stream().mapToDouble(region -> region.x2()).max().orElseThrow();
            double y2 = source.regions().stream().mapToDouble(region -> region.y2()).max().orElseThrow();
            int left = Math.max(0, (int) Math.floor(x1 * page.getWidth()));
            int top = Math.max(0, (int) Math.floor(y1 * page.getHeight()));
            int right = Math.min(page.getWidth(), (int) Math.ceil(x2 * page.getWidth()));
            int bottom = Math.min(page.getHeight(), (int) Math.ceil(y2 * page.getHeight()));
            BufferedImage view = page.getSubimage(left, top, right - left, bottom - top);
            int imageType = page.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
            BufferedImage crop = new BufferedImage(view.getWidth(), view.getHeight(), imageType);
            var graphics = crop.createGraphics();
            try {
                graphics.drawImage(view, 0, 0, null);
            } finally {
                graphics.dispose();
            }
            BoundedByteArrayOutputStream output = new BoundedByteArrayOutputStream(maximumEncodedBytes);
            if (!ImageIO.write(crop, "png", output)) {
                throw new IllegalArgumentException("visual crop exceeds the encoded byte budget");
            }
            return output.toByteArray();
        } catch (EncodedByteBudgetExceededException e) {
            throw new IllegalArgumentException("visual crop exceeds the encoded byte budget", e);
        } catch (IOException e) {
            throw new IllegalArgumentException("page image cannot be decoded for visual cropping", e);
        }
    }

    private BufferedImage decodeWithinBudget(byte[] pageImage) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(pageImage))) {
            if (input == null) {
                throw new IllegalArgumentException("page image is invalid or exceeds the visual pixel budget");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("page image is invalid or exceeds the visual pixel budget");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                // Reject from image metadata before allocating the decoded raster.
                if ((long) width * height > maximumDecodedPixels) {
                    throw new IllegalArgumentException("page image is invalid or exceeds the visual pixel budget");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    /** Prevents the retained encoded byte array from growing beyond the configured budget. */
    private static final class BoundedByteArrayOutputStream extends OutputStream {
        private final int maximumBytes;
        private final ByteArrayOutputStream delegate;

        private BoundedByteArrayOutputStream(int maximumBytes) {
            this.maximumBytes = maximumBytes;
            this.delegate = new ByteArrayOutputStream(Math.min(maximumBytes, 8192));
        }

        @Override
        public void write(int value) throws IOException {
            ensureCapacity(1);
            delegate.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            ensureCapacity(length);
            delegate.write(bytes, offset, length);
        }

        private void ensureCapacity(int additionalBytes) {
            if (additionalBytes > maximumBytes - delegate.size()) {
                throw new EncodedByteBudgetExceededException();
            }
        }

        private byte[] toByteArray() {
            return delegate.toByteArray();
        }
    }

    private static final class EncodedByteBudgetExceededException extends RuntimeException {
    }
}
