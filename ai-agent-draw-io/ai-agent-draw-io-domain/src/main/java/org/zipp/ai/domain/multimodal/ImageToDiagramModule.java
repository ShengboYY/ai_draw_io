package org.zipp.ai.domain.multimodal;

public interface ImageToDiagramModule {
    ImageToDiagramOutcome convert(ImageToDiagramCommand command);
}
