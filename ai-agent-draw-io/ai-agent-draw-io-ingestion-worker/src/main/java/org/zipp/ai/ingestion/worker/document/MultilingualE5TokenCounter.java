package org.zipp.ai.ingestion.worker.document;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import org.zipp.ai.domain.retrieval.projection.RetrievalTokenCounter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/** Exact local token counter backed by the pinned multilingual-e5-large tokenizer.json. */
public final class MultilingualE5TokenCounter implements RetrievalTokenCounter, AutoCloseable {

    private static final String MODEL = "intfloat/multilingual-e5-large";

    private final HuggingFaceTokenizer tokenizer;
    private final String fingerprint;

    public MultilingualE5TokenCounter(Path tokenizerPath, String expectedSha256) {
        Path path = Objects.requireNonNull(tokenizerPath, "tokenizerPath").toAbsolutePath().normalize();
        String expected = requireSha256(expectedSha256);
        String actual = sha256(path);
        if (!expected.equals(actual)) {
            throw new IllegalStateException("multilingual-e5 tokenizer checksum does not match the worker profile");
        }
        try {
            tokenizer = HuggingFaceTokenizer.newInstance(path);
        } catch (IOException e) {
            throw new IllegalStateException("multilingual-e5 tokenizer cannot be loaded", e);
        }
        fingerprint = fingerprint(expected);
    }

    @Override
    public int count(String text) {
        return tokenizer.encode(Objects.requireNonNull(text, "text")).getIds().length;
    }

    @Override
    public String fingerprint() {
        return fingerprint;
    }

    @Override
    public void close() {
        tokenizer.close();
    }

    public static String fingerprint(String tokenizerSha256) {
        return MODEL + ":tokenizer-json-sha256=" + requireSha256(tokenizerSha256);
    }

    private static String sha256(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("multilingual-e5 tokenizer cannot be verified", e);
        }
    }

    private static String requireSha256(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("tokenizerSha256 must be lowercase SHA-256");
        }
        return value;
    }
}
