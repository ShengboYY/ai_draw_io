type BuildDrawioChatRequestPayloadInput = {
  agentId: string;
  userId: string;
  sessionId: string;
  userMessage: string;
  diagramId?: string;
  expectedVersion?: number;
  canvasXml?: string;
  canvasSummary?: string;
  modelCredentialId?: string;
  customBaseUrl?: string;
  customApiKey?: string;
  customCompletionsPath?: string;
  customModel?: string;
  maxReviewIterations?: number;
  skills?: string[];
};

export const buildDrawioChatRequestPayload = ({
  agentId,
  userId,
  sessionId,
  diagramId,
  expectedVersion,
  modelCredentialId,
  userMessage,
  canvasXml,
  canvasSummary,
  maxReviewIterations,
  skills,
}: BuildDrawioChatRequestPayloadInput) => {
  const clientHints =
    maxReviewIterations !== undefined || (skills && skills.length > 0)
      ? {
          ...(maxReviewIterations !== undefined && { maxReviewIterations }),
          ...(skills && skills.length > 0 && { skills }),
        }
      : undefined;

  return {
    agentId,
    userId,
    sessionId,
    ...(diagramId && { diagramId }),
    ...(expectedVersion !== undefined && { expectedVersion }),
    ...(modelCredentialId && { modelCredentialId }),
    // Keep message as the raw user request; canvas context travels in structured fields.
    message: userMessage,
    ...(canvasXml && { canvasXml }),
    ...(canvasSummary && { canvasSummary }),
    // Legacy raw custom fields are intentionally dropped; chat accepts saved credential ids only.
    ...(maxReviewIterations !== undefined && { maxReviewIterations }),
    ...(skills && skills.length > 0 && { skills }),
    ...(clientHints && { clientHints }),
  };
};
