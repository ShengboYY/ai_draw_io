package org.zipp.ai.ingestion.worker.fake;

import org.zipp.ai.domain.ingestion.model.valobj.NativeTextQuality;
import org.zipp.ai.domain.ingestion.model.valobj.PageExtraction;
import org.zipp.ai.domain.ingestion.model.valobj.ParsedDocument;
import org.zipp.ai.domain.ingestion.model.valobj.ParsedPage;
import org.zipp.ai.domain.ingestion.port.DocumentParserPort;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

public final class FakeDocumentParser implements DocumentParserPort {
    private final int pageCount;

    public FakeDocumentParser(int pageCount) {
        this.pageCount = pageCount;
    }

    @Override
    public ParsedDocument parse(Path original, String detectedMediaType, Path workingDirectory) {
        return new ParsedDocument(IntStream.rangeClosed(1, pageCount)
                .mapToObj(page -> new ParsedPage(new PageExtraction(page, 100, 100, List.of(),
                        NativeTextQuality.empty(), null), workingDirectory.resolve("page-" + page + ".png")))
                .toList());
    }
}
