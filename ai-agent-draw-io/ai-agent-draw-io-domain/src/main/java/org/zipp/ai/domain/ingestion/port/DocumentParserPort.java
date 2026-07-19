package org.zipp.ai.domain.ingestion.port;

import org.zipp.ai.domain.ingestion.model.valobj.MaterialObject;
import org.zipp.ai.domain.ingestion.model.valobj.ParsedDocument;

public interface DocumentParserPort {
    ParsedDocument parse(MaterialObject object);
}
