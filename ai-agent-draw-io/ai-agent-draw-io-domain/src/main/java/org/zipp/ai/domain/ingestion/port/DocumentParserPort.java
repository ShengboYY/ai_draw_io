package org.zipp.ai.domain.ingestion.port;

import org.zipp.ai.domain.ingestion.model.valobj.ParsedDocument;

import java.nio.file.Path;

public interface DocumentParserPort {
    ParsedDocument parse(Path original, String detectedMediaType, Path workingDirectory);
}
