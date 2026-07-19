package org.zipp.ai.domain.ingestion.model.valobj;

public record BoilerplateBlockRef(int pageNo, String blockId, String normalizedText,
                                  BoilerplatePosition position) {
    public BoilerplateBlockRef {
        if (pageNo < 1 || blockId == null || blockId.isBlank()
                || normalizedText == null || normalizedText.isBlank()
                || position == null || position == BoilerplatePosition.NONE) {
            throw new IllegalArgumentException("boilerplate block identity is invalid");
        }
    }
}
