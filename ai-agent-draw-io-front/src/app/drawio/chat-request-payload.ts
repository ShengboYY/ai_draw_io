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
  expectedContentHash?: string;
  canvasXml?: string;
  canvasSummary?: string;
  canvasImageDataUrl?: string;
  canvasImageRendererVersion?: 'drawio-embed-png-v1';
  modelCredentialId?: string;
  customBaseUrl?: string;
  customApiKey?: string;
  customCompletionsPath?: string;
  customModel?: string;
  maxDeterministicRepairRounds?: number;
  skills?: string[];
  conversationMessages?: ConversationMessageInput[];
};

const MAX_CONVERSATION_CONTEXT_MESSAGES = 6;
const MAX_CONVERSATION_CONTEXT_CHARS = 800;

const newRequestId = () => {
  // Keep this human-copyable for support tickets while still unique enough per browser request.
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return `req-${crypto.randomUUID()}`;
  }
  return `req-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
};

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
  expectedContentHash,
  modelCredentialId,
  userMessage,
  canvasXml,
  canvasSummary,
  canvasImageDataUrl,
  canvasImageRendererVersion,
  maxDeterministicRepairRounds,
  skills,
  conversationMessages,
}: BuildDrawioChatRequestPayloadInput) => {
  const clientHints =
    maxDeterministicRepairRounds !== undefined || (skills && skills.length > 0)
      ? {
          ...(maxDeterministicRepairRounds !== undefined && { maxDeterministicRepairRounds }),
          ...(skills && skills.length > 0 && { skills }),
      }
      : undefined;
  const compactConversationMessages = toConversationContextMessages(conversationMessages);

  return {
    agentId,
    userId,
    sessionId,
    requestId: newRequestId(),
    ...(diagramId && { diagramId }),
    ...(expectedVersion !== undefined && { expectedVersion }),
    ...(expectedContentHash?.trim() && { expectedContentHash: expectedContentHash.trim() }),
    ...(modelCredentialId && { modelCredentialId }),
    // Keep message as the raw user request; canvas context travels in structured fields.
    message: userMessage,
    ...(canvasXml && { canvasXml }),
    ...(canvasSummary && { canvasSummary }),
    ...(canvasImageDataUrl && { canvasImageDataUrl }),
    ...(canvasImageRendererVersion && { canvasImageRendererVersion }),
    // Legacy raw custom fields are intentionally dropped; chat accepts saved credential ids only.
    ...(maxDeterministicRepairRounds !== undefined && { maxDeterministicRepairRounds }),
    ...(skills && skills.length > 0 && { skills }),
    ...(clientHints && { clientHints }),
    ...(compactConversationMessages.length > 0 && { conversationMessages: compactConversationMessages }),
  };
};
