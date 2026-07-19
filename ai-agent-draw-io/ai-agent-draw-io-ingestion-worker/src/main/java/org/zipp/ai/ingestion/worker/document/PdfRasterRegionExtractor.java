package org.zipp.ai.ingestion.worker.document;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.zipp.ai.domain.ingestion.model.valobj.NormalizedBoundingBox;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Finds placed raster regions without interpreting their visual content. */
final class PdfRasterRegionExtractor extends PDFGraphicsStreamEngine {

    private final PDPage page;
    private final double displayWidth;
    private final double displayHeight;
    private final List<NormalizedBoundingBox> regions = new ArrayList<>();
    private Point2D currentPoint;

    private PdfRasterRegionExtractor(PDPage page, double displayWidth, double displayHeight) {
        super(page);
        this.page = page;
        this.displayWidth = displayWidth;
        this.displayHeight = displayHeight;
    }

    static List<NormalizedBoundingBox> extract(PDPage page, double displayWidth,
                                                double displayHeight) throws IOException {
        PdfRasterRegionExtractor extractor = new PdfRasterRegionExtractor(page, displayWidth, displayHeight);
        extractor.processPage(page);
        return List.copyOf(extractor.regions);
    }

    @Override
    public void drawImage(PDImage image) {
        var matrix = getGraphicsState().getCurrentTransformationMatrix();
        List<Point2D.Float> corners = List.of(matrix.transformPoint(0, 0), matrix.transformPoint(1, 0),
                matrix.transformPoint(0, 1), matrix.transformPoint(1, 1));
        List<Point2D.Double> displayed = corners.stream().map(this::toDisplayed).toList();
        double minX = displayed.stream().mapToDouble(Point2D::getX).min().orElse(0);
        double maxX = displayed.stream().mapToDouble(Point2D::getX).max().orElse(0);
        double minY = displayed.stream().mapToDouble(Point2D::getY).min().orElse(0);
        double maxY = displayed.stream().mapToDouble(Point2D::getY).max().orElse(0);
        double x1 = clamp(minX / displayWidth);
        double y1 = clamp(minY / displayHeight);
        double x2 = clamp(maxX / displayWidth);
        double y2 = clamp(maxY / displayHeight);
        if (x2 > x1 && y2 > y1) {
            regions.add(new NormalizedBoundingBox(x1, y1, x2, y2));
        }
    }

    private Point2D.Double toDisplayed(Point2D point) {
        double x = point.getX() - page.getCropBox().getLowerLeftX();
        double y = point.getY() - page.getCropBox().getLowerLeftY();
        double rawWidth = page.getCropBox().getWidth();
        double rawHeight = page.getCropBox().getHeight();
        return switch (Math.floorMod(page.getRotation(), 360)) {
            case 90 -> new Point2D.Double(y, x);
            case 180 -> new Point2D.Double(rawWidth - x, y);
            case 270 -> new Point2D.Double(rawHeight - y, rawWidth - x);
            default -> new Point2D.Double(x, rawHeight - y);
        };
    }

    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
    @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) { }
    @Override public void clip(int windingRule) { }
    @Override public void moveTo(float x, float y) { currentPoint = new Point2D.Float(x, y); }
    @Override public void lineTo(float x, float y) { currentPoint = new Point2D.Float(x, y); }
    @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        currentPoint = new Point2D.Float(x3, y3);
    }
    @Override public Point2D getCurrentPoint() { return currentPoint; }
    @Override public void closePath() { }
    @Override public void endPath() { }
    @Override public void strokePath() { }
    @Override public void fillPath(int windingRule) { }
    @Override public void fillAndStrokePath(int windingRule) { }
    @Override public void shadingFill(COSName shadingName) { }
}
