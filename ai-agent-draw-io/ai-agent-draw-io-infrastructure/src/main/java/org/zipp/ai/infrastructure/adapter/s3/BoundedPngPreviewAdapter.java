package org.zipp.ai.infrastructure.adapter.s3;

import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.ingestion.port.RevisionArtifactPort;
import org.zipp.ai.domain.material.model.valobj.CatalogErrorCode;
import org.zipp.ai.domain.material.model.valobj.CatalogOperationException;
import org.zipp.ai.domain.material.model.valobj.MaterialPreviewImage;
import org.zipp.ai.domain.material.port.MaterialPreviewContentPort;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Objects;

/** Renders a bounded online preview from one exact immutable page-image version. */
public final class BoundedPngPreviewAdapter implements MaterialPreviewContentPort {
    private static final long MAX_SOURCE_BYTES = 20L * 1024 * 1024;
    private static final long MAX_SOURCE_PIXELS = 20_000_000L;
    private static final int MAX_DIMENSION = 1600;
    private static final int MAX_PREVIEW_BYTES = 8 * 1024 * 1024;

    private final RevisionArtifactPort artifacts;

    public BoundedPngPreviewAdapter(RevisionArtifactPort artifacts) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    }

    @Override
    public MaterialPreviewImage render(StoredArtifact pageImage) {
        try {
            StoredArtifact exact = Objects.requireNonNull(pageImage, "pageImage");
            if (!"image/png".equals(exact.contentType())) {
                throw unavailable();
            }
            byte[] sourceBytes = artifacts.read(exact, MAX_SOURCE_BYTES);
            BufferedImage source = boundedRead(sourceBytes);
            BufferedImage preview = resize(source);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(preview, "png", output)) throw unavailable();
            byte[] bytes = output.toByteArray();
            if (bytes.length < 1 || bytes.length > MAX_PREVIEW_BYTES) throw unavailable();
            return new MaterialPreviewImage(bytes, "image/png", sha256(bytes));
        } catch (CatalogOperationException e) {
            throw e;
        } catch (RuntimeException | IOException e) {
            throw unavailable();
        }
    }

    private BufferedImage boundedRead(byte[] bytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) throw unavailable();
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw unavailable();
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > MAX_SOURCE_PIXELS) {
                    throw unavailable();
                }
                BufferedImage image = reader.read(0);
                if (image == null) throw unavailable();
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    private BufferedImage resize(BufferedImage source) {
        double scale = Math.min(1d, (double) MAX_DIMENSION
                / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        // Opaque RGB gives predictable memory and file-size bounds for document previews.
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private CatalogOperationException unavailable() {
        return new CatalogOperationException(CatalogErrorCode.PREVIEW_UNAVAILABLE);
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }
}
