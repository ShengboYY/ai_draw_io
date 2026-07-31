package org.zipp.ai.application.memory;

import java.util.List;

/** Tool-free model boundary. It may propose observations but can never write Memory directly. */
public interface AutoMemoryExtractionPort {
    List<AutoMemoryExtractionDraft> extract(AutoMemoryExtractionInput input);
}
