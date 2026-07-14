package org.zipp.ai.trigger.http.service;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Iterator;

@Component
public class CanvasReviewImageValidator {

    private static final String PNG_PREFIX = "data:image/png;base64,";
    private static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_DATA_URL_LENGTH = 2_800_000;
    private static final int MAX_DIMENSION = 4096;
    private static final byte[] PNG_SIGNATURE = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};

    public ValidatedImage validate(String dataUrl) {
        if (dataUrl == null || dataUrl.length() > MAX_DATA_URL_LENGTH || !dataUrl.startsWith(PNG_PREFIX)) {
            throw new IllegalArgumentException("invalid_png_data_url");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(dataUrl.substring(PNG_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid_png_base64", e);
        }
        if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES || !hasPngSignature(bytes)) {
            throw new IllegalArgumentException("invalid_png_bytes");
        }

        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("invalid_png_image");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
                    throw new IllegalArgumentException("invalid_png_dimensions");
                }
                // Decode once so a valid signature and metadata cannot hide a truncated/corrupt payload.
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new IllegalArgumentException("invalid_png_image");
                }
                return new ValidatedImage(bytes, width, height);
            } finally {
                reader.dispose();
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid_png_image", e);
        }
    }

    private boolean hasPngSignature(byte[] bytes) {
        if (bytes.length < PNG_SIGNATURE.length) return false;
        for (int index = 0; index < PNG_SIGNATURE.length; index++) {
            if (bytes[index] != PNG_SIGNATURE[index]) return false;
        }
        return true;
    }

    public record ValidatedImage(byte[] bytes, int width, int height) {
    }
}
