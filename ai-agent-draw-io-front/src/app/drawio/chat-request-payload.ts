type ConversationMessageInput = {
  id?: string;
  clientMessageId?: string;
  sessionId?: string;
  role: 'user' | 'agent';
  content?: string;
};

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
  conversationMessages?: ConversationMessageInput[];
};

const MAX_CONVERSATION_CONTEXT_MESSAGES = 6;
const MAX_CONVERSATION_CONTEXT_CHARS = 800;

const sanitizeConversationContent = (content?: string) => {
  const compact = (content || '')
    .replace(/<mxGraphModel[\s\S]*?<\/mxGraphModel>/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  return compact.length <= MAX_CONVERSATION_CONTEXT_CHARS
    ? compact
    : `${compact.slice(0, MAX_CONVERSATION_CONTEXT_CHARS)}...`;
};

const toConversationContextMessages = (messages?: ConversationMessageInput[]) => {
  if (!messages || messages.length === 0) return [];

  // Keep only compact visible chat text so intent routing can resolve follow-up answers
  // without inheriting execution steps, canvas XML, or unrelated long artifacts.
  return messages
    .slice(-MAX_CONVERSATION_CONTEXT_MESSAGES)
    .map(message => ({
      clientMessageId: message.clientMessageId || message.id || '',
      ...(message.sessionId && { sessionId: message.sessionId }),
      role: message.role,
      content: sanitizeConversationContent(message.content),
    }))
    .filter(message => message.content);
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
  conversationMessages,
}: BuildDrawioChatRequestPayloadInput) => {
  const clientHints =
    maxReviewIterations !== undefined || (skills && skills.length > 0)
      ? {
          ...(maxReviewIterations !== undefined && { maxReviewIterations }),
          ...(skills && skills.length > 0 && { skills }),
      }
      : undefined;
  const compactConversationMessages = toConversationContextMessages(conversationMessages);

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
    ...(compactConversationMessages.length > 0 && { conversationMessages: compactConversationMessages }),
  };
};
