package org.zipp.ai.ingestion.worker.document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Fail-fast guard that binds the declared processing profile to the installed OCR runtime. */
public final class TesseractInstallationVerifier {

    private TesseractInstallationVerifier() { }

    public static void verify(String executable, String languages, String declaredRuntime, Duration timeout) {
        String command = requireText(executable, "executable");
        Duration maximum = Objects.requireNonNull(timeout, "timeout");
        if (maximum.isZero() || maximum.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        String actualRuntime = firstLine(run(command, "--version", maximum)).replace(' ', '-');
        if (!requireText(declaredRuntime, "declaredRuntime").equals(actualRuntime)) {
            throw new IllegalStateException("declared Tesseract runtime does not match installed runtime");
        }
        Set<String> installedLanguages = new HashSet<>(Arrays.asList(
                run(command, "--list-langs", maximum).split("\\R")));
        for (String language : requireText(languages, "languages").split("\\+")) {
            if (!installedLanguages.contains(language)) {
                throw new IllegalStateException("required Tesseract language is not installed: " + language);
            }
        }
    }

    private static String run(String executable, String argument, Duration timeout) {
        try {
            // Executable and fixed control arguments bypass any command shell.
            Process process = new ProcessBuilder(executable, argument).redirectErrorStream(true).start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Tesseract installation check timed out");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0 || output.isBlank()) {
                throw new IllegalStateException("Tesseract installation check failed");
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("Tesseract installation check could not start", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Tesseract installation check was interrupted", e);
        }
    }

    private static String firstLine(String output) {
        return output.lines().findFirst().orElseThrow();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
