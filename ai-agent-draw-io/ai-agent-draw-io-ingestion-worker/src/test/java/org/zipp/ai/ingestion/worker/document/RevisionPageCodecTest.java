package org.zipp.ai.ingestion.worker.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.model.valobj.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class RevisionPageCodecTest {

    @Test
    void rejectsACompressedCanonicalPageAboveTheDecompressedByteBudget() {
        String text = "a".repeat(10_000);
        NormalizedBoundingBox region = new NormalizedBoundingBox(0.1, 0.1, 0.9, 0.2);
        CanonicalBlock block = new CanonicalBlock("block_1", TextBlockKind.PARAGRAPH, 1,
                List.of(region), TextSource.NATIVE, text, text,
                List.of(new SourceMapSpan(0, text.length(), 0, text.length(), List.of(region))),
                0.95, BoilerplatePosition.NONE);
        CanonicalPage page = new CanonicalPage(1, 100, 100, List.of(block),
                NativeTextQuality.empty(), null, false);
        RevisionPageCodec codec = new RevisionPageCodec(new ObjectMapper());

        byte[] compressed = codec.encode(page);

        assertThrows(ProcessingLimitExceededException.class,
                () -> codec.decodeCanonicalPage(compressed, 1024));
    }
}
