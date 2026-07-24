package org.zipp.ai.domain.retrieval.port;

import org.zipp.ai.domain.retrieval.RequestSourceResolutionCommand;

import java.util.List;

public interface RequestSourceResolutionPort {
    List<SourceResolutionCandidate> resolveAttachments(RequestSourceResolutionCommand command);
    List<SourceResolutionCandidate> resolveExplicitVersions(RequestSourceResolutionCommand command);
    List<SourceResolutionCandidate> resolveAutomatic(RequestSourceResolutionCommand command, int limit);
    int countPendingConversationUploads(RequestSourceResolutionCommand command);
}
