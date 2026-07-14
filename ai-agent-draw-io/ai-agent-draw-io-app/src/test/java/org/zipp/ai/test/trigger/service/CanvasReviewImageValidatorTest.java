package org.zipp.ai.test.trigger.service;

import org.junit.Test;
import org.zipp.ai.trigger.http.service.CanvasReviewImageValidator;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class CanvasReviewImageValidatorTest {

    private final CanvasReviewImageValidator validator = new CanvasReviewImageValidator();

    @Test
    public void acceptsARealBoundedPng() throws Exception {
        CanvasReviewImageValidator.ValidatedImage image = validator.validate(png(32, 24));

        assertEquals(32, image.width());
        assertEquals(24, image.height());
    }

    @Test
    public void rejectsWrongPrefixFakePngOversizedBytesAndDimensions() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> validator.validate("data:image/jpeg;base64,AAAA"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("data:image/png;base64," + Base64.getEncoder().encodeToString("not png".getBytes())));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("data:image/png;base64," + Base64.getEncoder().encodeToString(new byte[2 * 1024 * 1024 + 1])));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(png(4097, 1)));
    }

    private String png(int width, int height) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", output);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
    }
}
