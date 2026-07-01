type BuildDrawioChatRequestPayloadInput = {
  agentId: string;
  userId: string;
  sessionId: string;
  userMessage: string;
  diagramId?: string;
  expectedVersion?: number;
  canvasXml?: string;
  canvasSummary?: string;
  customBaseUrl?: string;
  customApiKey?: string;
  customCompletionsPath?: string;
  customModel?: string;
  maxReviewIterations?: number;
  skills?: string[];
};

export const buildDrawioChatRequestPayload = ({
  userMessage,
  canvasXml,
  canvasSummary,
  maxReviewIterations,
  skills,
  ...base
}: BuildDrawioChatRequestPayloadInput) => {
  const clientHints =
    maxReviewIterations !== undefined || (skills && skills.length > 0)
      ? {
          ...(maxReviewIterations !== undefined && { maxReviewIterations }),
          ...(skills && skills.length > 0 && { skills }),
        }
      : undefined;

  return {
    ...base,
    // Keep message as the raw user request; canvas context travels in structured fields.
    message: userMessage,
    ...(canvasXml && { canvasXml }),
    ...(canvasSummary && { canvasSummary }),
    ...(maxReviewIterations !== undefined && { maxReviewIterations }),
    ...(skills && skills.length > 0 && { skills }),
    ...(clientHints && { clientHints }),
  };
};
