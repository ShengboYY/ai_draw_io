package org.zipp.ai.domain.multimodal;

/** Converts an already verified explicit graph; it never reads pixels or infers topology. */
public interface ImageToDiagramModule {
    ImageToDiagramOutcome convert(ImageToDiagramCommand command);
}
