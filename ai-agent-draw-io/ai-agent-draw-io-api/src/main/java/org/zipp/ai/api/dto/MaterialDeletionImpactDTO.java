package org.zipp.ai.api.dto;

import java.time.Instant;
import java.util.List;

public record MaterialDeletionImpactDTO(String materialId, long lifecycleGeneration,
                                        long versionCount, long diagramCount,
                                        long chartbookCount, long citationCount,
                                        List<String> versionIds, List<String> pinnedSourceIds,
                                        List<String> diagramIds, List<String> chartbookIds,
                                        List<String> citationIds,
                                        String deletionConfirmationToken,
                                        Instant confirmationExpiresAt) { }
