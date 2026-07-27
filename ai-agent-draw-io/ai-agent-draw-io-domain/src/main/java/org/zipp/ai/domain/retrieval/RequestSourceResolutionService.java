package org.zipp.ai.domain.retrieval;

public interface RequestSourceResolutionService {
    ResolvedSourceSet resolve(RequestSourceResolutionCommand command);
}
