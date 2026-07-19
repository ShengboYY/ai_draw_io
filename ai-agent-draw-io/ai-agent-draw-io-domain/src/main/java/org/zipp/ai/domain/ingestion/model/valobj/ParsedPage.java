package org.zipp.ai.domain.ingestion.model.valobj;

import java.nio.file.Path;
import java.util.Objects;

public record ParsedPage(PageExtraction extraction, Path renderedImage) {
    public ParsedPage {
        extraction = Objects.requireNonNull(extraction, "extraction");
        renderedImage = Objects.requireNonNull(renderedImage, "renderedImage");
    }
}
