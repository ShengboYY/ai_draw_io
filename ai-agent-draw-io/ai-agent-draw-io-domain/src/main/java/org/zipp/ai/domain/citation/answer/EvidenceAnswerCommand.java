package org.zipp.ai.domain.citation.answer;

import org.zipp.ai.domain.material.model.valobj.CatalogOwner;

/** Authoritative identifiers and canvas tuple required by the answer transaction. */
public record EvidenceAnswerCommand(CatalogOwner owner, String diagramId, String sessionId,
                                    String messageId, String requestId, String runId,
                                    String question, String targetContext, String conversationContext,
                                    Long expectedCanvasVersion, String expectedCanvasContentHash,
                                    boolean aiKnowledgeAllowed) { }
