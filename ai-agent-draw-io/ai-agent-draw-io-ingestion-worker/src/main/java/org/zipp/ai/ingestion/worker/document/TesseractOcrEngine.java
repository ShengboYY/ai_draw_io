package org.zipp.ai.ingestion.worker.document;

import org.zipp.ai.domain.ingestion.model.valobj.NormalizedBoundingBox;
import org.zipp.ai.domain.ingestion.model.valobj.OcrResult;
import org.zipp.ai.domain.ingestion.model.valobj.OcrWord;
import org.zipp.ai.domain.ingestion.port.OcrEnginePort;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class TesseractOcrEngine implements OcrEnginePort {

    private final String executable;
    private final String languages;
    private final Duration timeout;

    public TesseractOcrEngine(String executable, String languages, Duration timeout) {
        this.executable = requireText(executable, "executable");
        this.languages = requireText(languages, "languages");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    @Override
    public OcrResult recognize(Path pageImage, int pageNo) {
        if (pageNo < 1) {
            throw new IllegalArgumentException("pageNo must be positive");
        }
        Path image = Objects.requireNonNull(pageImage, "pageImage");
        Path temporaryDirectory = null;
        try {
            BufferedImage dimensions = ImageIO.read(image.toFile());
            if (dimensions == null || dimensions.getWidth() < 1 || dimensions.getHeight() < 1) {
                throw new IllegalArgumentException("OCR page image is invalid");
            }
            temporaryDirectory = Files.createTempDirectory("tesseract-ocr-");
            Path outputBase = temporaryDirectory.resolve("result");
            Path processLog = temporaryDirectory.resolve("process.log");
            // ProcessBuilder receives fixed argv entries; no user text is ever interpreted by a shell.
            ProcessBuilder builder = new ProcessBuilder(executable, image.toString(), outputBase.toString(),
                    "-l", languages, "--dpi", "200", "tsv");
            builder.redirectErrorStream(true).redirectOutput(processLog.toFile());
            Process process = builder.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Tesseract timed out");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Tesseract failed with exit code " + process.exitValue());
            }
            return parseTsv(outputBase.resolveSibling(outputBase.getFileName() + ".tsv"), pageNo,
                    dimensions.getWidth(), dimensions.getHeight());
        } catch (IOException e) {
            throw new IllegalStateException("Tesseract could not process the page", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Tesseract was interrupted", e);
        } finally {
            deleteTree(temporaryDirectory);
        }
    }

    private static OcrResult parseTsv(Path tsv, int pageNo, int imageWidth, int imageHeight) throws IOException {
        List<OcrWord> words = new ArrayList<>();
        for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
            String[] columns = line.split("\\t", -1);
            if (columns.length < 12 || !"5".equals(columns[0]) || columns[11].isBlank()) {
                continue;
            }
            double confidence = Double.parseDouble(columns[10]);
            if (confidence < 0) {
                continue;
            }
            double left = Double.parseDouble(columns[6]);
            double top = Double.parseDouble(columns[7]);
            double width = Double.parseDouble(columns[8]);
            double height = Double.parseDouble(columns[9]);
            NormalizedBoundingBox box = new NormalizedBoundingBox(
                    clamp(left / imageWidth), clamp(top / imageHeight),
                    clamp((left + width) / imageWidth), clamp((top + height) / imageHeight));
            String lineId = "line:" + columns[2] + ":" + columns[3] + ":" + columns[4];
            words.add(new OcrWord(columns[11], box, confidence / 100.0, lineId));
        }
        String text = words.stream().map(OcrWord::text)
                .collect(java.util.stream.Collectors.joining(" "));
        double mean = words.stream().mapToDouble(OcrWord::confidence).average().orElse(0);
        return new OcrResult(pageNo, text, mean, words);
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static void deleteTree(Path directory) {
        if (directory == null) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Worker temp storage is ephemeral and is discarded with the task.
                }
            });
        } catch (IOException ignored) {
            // Nothing in the temp directory is authoritative or user-visible.
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
