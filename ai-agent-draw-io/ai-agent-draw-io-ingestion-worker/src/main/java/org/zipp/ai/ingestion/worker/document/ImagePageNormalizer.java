package org.zipp.ai.ingestion.worker.document;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Decodes an image into orientation-corrected JVM sRGB pixels. */
final class ImagePageNormalizer {

    private ImagePageNormalizer() { }

    static BufferedImage read(Path image) throws IOException {
        BufferedImage decoded = ImageIO.read(image.toFile());
        if (decoded == null || decoded.getWidth() < 1 || decoded.getHeight() < 1) {
            throw new IllegalArgumentException("image could not be decoded");
        }
        return normalize(decoded, jpegExifOrientation(image));
    }

    private static BufferedImage normalize(BufferedImage source, int orientation) {
        boolean swapDimensions = orientation >= 5 && orientation <= 8;
        int width = swapDimensions ? source.getHeight() : source.getWidth();
        int height = swapDimensions ? source.getWidth() : source.getHeight();
        int type = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage normalized = new BufferedImage(width, height, type);
        Graphics2D graphics = normalized.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            AffineTransform transform = switch (orientation) {
                case 2 -> new AffineTransform(-1, 0, 0, 1, source.getWidth(), 0);
                case 3 -> new AffineTransform(-1, 0, 0, -1, source.getWidth(), source.getHeight());
                case 4 -> new AffineTransform(1, 0, 0, -1, 0, source.getHeight());
                case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
                case 6 -> new AffineTransform(0, 1, -1, 0, source.getHeight(), 0);
                case 7 -> new AffineTransform(0, -1, -1, 0, source.getHeight(), source.getWidth());
                case 8 -> new AffineTransform(0, -1, 1, 0, 0, source.getWidth());
                default -> new AffineTransform();
            };
            // Drawing into a standard BufferedImage converts embedded profiles into JVM sRGB.
            graphics.drawImage(source, transform, null);
        } finally {
            graphics.dispose();
        }
        return normalized;
    }

    private static int jpegExifOrientation(Path image) {
        try {
            byte[] bytes = Files.readAllBytes(image);
            if (bytes.length < 4 || (bytes[0] & 0xff) != 0xff || (bytes[1] & 0xff) != 0xd8) {
                return 1;
            }
            for (int offset = 2; offset + 4 < bytes.length;) {
                if ((bytes[offset] & 0xff) != 0xff) {
                    break;
                }
                int marker = bytes[offset + 1] & 0xff;
                int length = unsignedShort(bytes, offset + 2, false);
                if (length < 2 || offset + 2 + length > bytes.length) {
                    break;
                }
                if (marker == 0xe1 && length >= 16 && matchesExif(bytes, offset + 4)) {
                    return readTiffOrientation(bytes, offset + 10, offset + 2 + length);
                }
                offset += 2 + length;
            }
        } catch (IOException ignored) {
            // ImageIO already validated the image; missing metadata only disables auto-rotation.
        }
        return 1;
    }

    private static boolean matchesExif(byte[] bytes, int offset) {
        byte[] marker = {'E', 'x', 'i', 'f', 0, 0};
        if (offset + marker.length > bytes.length) {
            return false;
        }
        for (int index = 0; index < marker.length; index++) {
            if (bytes[offset + index] != marker[index]) {
                return false;
            }
        }
        return true;
    }

    private static int readTiffOrientation(byte[] bytes, int tiffStart, int segmentEnd) {
        if (tiffStart + 8 > segmentEnd) {
            return 1;
        }
        boolean littleEndian = bytes[tiffStart] == 'I' && bytes[tiffStart + 1] == 'I';
        if (!littleEndian && !(bytes[tiffStart] == 'M' && bytes[tiffStart + 1] == 'M')) {
            return 1;
        }
        int ifdOffset = signedInt(bytes, tiffStart + 4, littleEndian);
        int ifd = tiffStart + ifdOffset;
        if (ifd < tiffStart || ifd + 2 > segmentEnd) {
            return 1;
        }
        int entries = unsignedShort(bytes, ifd, littleEndian);
        for (int index = 0; index < entries; index++) {
            int entry = ifd + 2 + index * 12;
            if (entry + 12 > segmentEnd) {
                break;
            }
            if (unsignedShort(bytes, entry, littleEndian) == 0x0112) {
                int orientation = unsignedShort(bytes, entry + 8, littleEndian);
                return orientation >= 1 && orientation <= 8 ? orientation : 1;
            }
        }
        return 1;
    }

    private static int unsignedShort(byte[] bytes, int offset, boolean littleEndian) {
        int first = bytes[offset] & 0xff;
        int second = bytes[offset + 1] & 0xff;
        return littleEndian ? first | second << 8 : first << 8 | second;
    }

    private static int signedInt(byte[] bytes, int offset, boolean littleEndian) {
        if (littleEndian) {
            return (bytes[offset] & 0xff) | (bytes[offset + 1] & 0xff) << 8
                    | (bytes[offset + 2] & 0xff) << 16 | (bytes[offset + 3] & 0xff) << 24;
        }
        return (bytes[offset] & 0xff) << 24 | (bytes[offset + 1] & 0xff) << 16
                | (bytes[offset + 2] & 0xff) << 8 | (bytes[offset + 3] & 0xff);
    }
}
