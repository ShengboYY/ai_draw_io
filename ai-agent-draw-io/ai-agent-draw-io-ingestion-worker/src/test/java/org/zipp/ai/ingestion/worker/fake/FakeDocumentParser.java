package org.zipp.ai.ingestion.worker.fake;

import org.zipp.ai.domain.ingestion.model.valobj.MaterialObject;
import org.zipp.ai.domain.ingestion.model.valobj.ParsedDocument;
import org.zipp.ai.domain.ingestion.port.DocumentParserPort;

public final class FakeDocumentParser implements DocumentParserPort {
    private final int pageCount;

    public FakeDocumentParser(int pageCount) {
        this.pageCount = pageCount;
    }

    @Override
    public ParsedDocument parse(MaterialObject object) {
        return new ParsedDocument(pageCount);
    }
}
