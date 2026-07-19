package org.zipp.ai.ingestion.worker.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MultilingualE5TokenCounterTest {

    private static final String PINNED_SHA =
            "62c24cdc13d4c9952d63718d6c9fa4c287974249e16b7ade6d5a85e7bbb75626";

    @Test
    void fingerprintIncludesTheExactTokenizerContentHash() {
        assertEquals("intfloat/multilingual-e5-large:tokenizer-json-sha256=" + PINNED_SHA,
                MultilingualE5TokenCounter.fingerprint(PINNED_SHA));
    }

    @Test
    void rejectsAnUnpinnedTokenizerBeforeLoadingNativeCode(@TempDir Path directory) throws IOException {
        Path tokenizer = directory.resolve("tokenizer.json");
        Files.writeString(tokenizer, "{}");

        assertThrows(IllegalStateException.class,
                () -> new MultilingualE5TokenCounter(tokenizer, PINNED_SHA));
    }
}
