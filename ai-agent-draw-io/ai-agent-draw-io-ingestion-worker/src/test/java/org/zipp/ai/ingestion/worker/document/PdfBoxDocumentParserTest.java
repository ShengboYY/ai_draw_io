package org.zipp.ai.ingestion.worker.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zipp.ai.domain.ingestion.model.valobj.TextSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfBoxDocumentParserTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void extractsNativeTextWithNormalizedCoordinatesAndRendersAnOcrPage() throws Exception {
        Path pdf = temporaryDirectory.resolve("guide.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                content.newLineAtOffset(72, 700);
                content.showText("Agile delivery flow");
                content.endText();
            }
            document.save(pdf.toFile());
        }

        var parsed = new PdfBoxDocumentParser(200).parse(
                pdf, "application/pdf", temporaryDirectory.resolve("rendered"));

        assertEquals(1, parsed.pageCount());
        var page = parsed.pages().get(0);
        assertTrue(page.extraction().nativeBlocks().stream().anyMatch(block -> block.text().contains("Agile")));
        assertTrue(page.extraction().nativeBlocks().stream()
                .allMatch(block -> block.textSource() == TextSource.NATIVE));
        assertTrue(page.extraction().nativeBlocks().stream().flatMap(block -> block.regions().stream())
                .allMatch(box -> box.x1() >= 0 && box.y1() >= 0 && box.x2() <= 1 && box.y2() <= 1));
        var agileBlock = page.extraction().nativeBlocks().stream()
                .filter(block -> block.text().contains("Agile")).findFirst().orElseThrow();
        assertTrue(agileBlock.sourceMap().size() > 1);
        assertTrue(agileBlock.sourceMap().stream().allMatch(span -> span.regions().size() == 1));
        assertFalse(page.extraction().nativeTextQuality().effectiveCharacters() == 0);
        assertTrue(Files.size(page.renderedImage()) > 0);
    }

    @Test
    void rotatesPageDimensionsAndReportsPlacedRasterRegions() throws Exception {
        Path pdf = temporaryDirectory.resolve("rotated.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            page.setRotation(90);
            document.addPage(page);
            BufferedImage raster = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(LosslessFactory.createFromImage(document, raster), 100, 200, 300, 200);
            }
            document.save(pdf.toFile());
        }

        var extraction = new PdfBoxDocumentParser(200).parse(pdf, "application/pdf",
                temporaryDirectory.resolve("rotated-render")).pages().get(0).extraction();

        assertEquals(792, extraction.width());
        assertEquals(612, extraction.height());
        assertFalse(extraction.rasterRegions().isEmpty());
        assertTrue(extraction.rasterRegions().stream().allMatch(region ->
                region.x1() >= 0 && region.y1() >= 0 && region.x2() <= 1 && region.y2() <= 1));
    }

    @Test
    void appliesJpegExifOrientationBeforeCreatingTheCanonicalPageImage() throws Exception {
        Path jpeg = temporaryDirectory.resolve("oriented.jpg");
        BufferedImage source = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(source, "jpeg", jpeg.toFile());
        byte[] original = Files.readAllBytes(jpeg);
        byte[] exif = new byte[] {
                (byte) 0xff, (byte) 0xe1, 0, 34, 'E', 'x', 'i', 'f', 0, 0,
                'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0,
                0x12, 0x01, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0,
                0, 0, 0, 0
        };
        byte[] oriented = new byte[original.length + exif.length];
        System.arraycopy(original, 0, oriented, 0, 2);
        System.arraycopy(exif, 0, oriented, 2, exif.length);
        System.arraycopy(original, 2, oriented, 2 + exif.length, original.length - 2);
        Files.write(jpeg, oriented);

        var page = new PdfBoxDocumentParser(200).parse(jpeg, "image/jpeg",
                temporaryDirectory.resolve("oriented-render")).pages().get(0);

        assertEquals(10, page.extraction().width());
        assertEquals(20, page.extraction().height());
        assertEquals(List.of(new org.zipp.ai.domain.ingestion.model.valobj.NormalizedBoundingBox(0, 0, 1, 1)),
                page.extraction().rasterRegions());
        BufferedImage normalized = ImageIO.read(page.renderedImage().toFile());
        assertEquals(10, normalized.getWidth());
        assertEquals(20, normalized.getHeight());
    }
}
