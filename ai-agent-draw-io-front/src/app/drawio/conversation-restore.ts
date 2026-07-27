type RestorableConversationMessage = {
  clientMessageId?: string;
  turnId?: string;
  role?: string;
  content?: string;
  createdAt?: string;
  evidenceClaims?: RestoredChatMessage['evidenceClaims'];
  evidenceSources?: RestoredChatMessage['evidenceSources'];
  attachmentRefs?: string[];
};

export type RestoredMessageStep = {
  id?: string;
  phase: string;
  label: string;
  content: string;
  status: 'running' | 'done' | 'pending';
};

export type RestoredChatMessage = {
  id: string;
  role: 'user' | 'agent';
  content: string;
  timestamp: number;
  reasoning?: string;
  routeType?: string;
  language?: 'zh' | 'en';
  steps?: RestoredMessageStep[];
  evidenceClaims?: Array<{
    claimKey: string;
    citationKeys: string[];
    supportType: 'DIRECT' | 'SYNTHESIZED' | 'VISUAL_VERIFIED' | 'AI_KNOWLEDGE';
  }>;
  evidenceSources?: Array<{
    citationKey: string;
    sourceLabel: string;
    pageNumber?: number;
    modality?: string;
    origin: 'EXISTING_REFERENCE' | 'EXPLICIT' | 'SEARCH' | 'SUPPLEMENTAL';
  }>;
  attachments?: Array<{
    uploadId: string;
    fileName: string;
    state: string;
    materialId?: string;
    versionId?: string;
  }>;
};

export type RestoredAttachmentMetadata = {
  fileName: string;
  state: string;
  materialId?: string;
  versionId?: string;
};

const normalizeRole = (role?: string): 'user' | 'agent' => (
  role === 'user' ? 'user' : 'agent'
);

const parseTimestamp = (value?: string) => {
  const parsed = value ? Date.parse(value) : Number.NaN;
  return Number.isNaN(parsed) ? Date.now() : parsed;
};

const parseOptionalTimestamp = (value?: string) => {
  const parsed = value ? Date.parse(value) : Number.NaN;
  return Number.isNaN(parsed) ? undefined : parsed;
};

const normalizeContent = (value?: string) => (value || '').replace(/\s+/g, ' ').trim();
const LEGACY_DUPLICATE_WINDOW_MS = 5 * 60 * 1000;

export const removeLegacyV2DuplicateMessages = (
  messages: RestorableConversationMessage[] = [],
): RestorableConversationMessage[] => messages.filter((candidate, candidateIndex) => {
  if (normalizeRole(candidate.role) !== 'user'
      || candidate.turnId?.trim()
      || !normalizeContent(candidate.content)) {
    return true;
  }

  const candidateTimestamp = parseOptionalTimestamp(candidate.createdAt);
  if (candidateTimestamp === undefined) return true;

  for (let sourceIndex = candidateIndex - 1; sourceIndex >= 0; sourceIndex -= 1) {
    const source = messages[sourceIndex];
    const sourceTurnId = source.turnId?.trim();
    if (normalizeRole(source.role) !== 'user'
        || !sourceTurnId
        || normalizeContent(source.content) !== normalizeContent(candidate.content)) {
      continue;
    }

    const sourceTimestamp = parseOptionalTimestamp(source.createdAt);
    if (sourceTimestamp === undefined
        || candidateTimestamp < sourceTimestamp
        || candidateTimestamp - sourceTimestamp > LEGACY_DUPLICATE_WINDOW_MS) {
      continue;
    }

    const hasTurnAssistantBetween = messages
      .slice(sourceIndex + 1, candidateIndex)
      .some(message => normalizeRole(message.role) === 'agent'
        && message.turnId?.trim() === sourceTurnId);
    if (hasTurnAssistantBetween) {
      // Old product UI wrote the user message again after V2 had already committed the turn.
      return false;
    }
  }

  return true;
});

const presentationMatchIndex = (
  message: RestoredChatMessage,
  restoredIndex: number,
  cachedMessages: RestoredChatMessage[],
  usedCachedIndexes: Set<number>,
) => {
  const available = (candidate: RestoredChatMessage, index: number) => (
    !usedCachedIndexes.has(index) && candidate.role === message.role
  );
  const exactIndex = cachedMessages.findIndex((candidate, index) => (
    available(candidate, index) && candidate.id === message.id
  ));
  if (exactIndex >= 0) return exactIndex;

  const normalizedMessage = normalizeContent(message.content);
  const contentIndex = cachedMessages.findIndex((candidate, index) => (
    available(candidate, index) && normalizeContent(candidate.content) === normalizedMessage
  ));
  if (contentIndex >= 0) return contentIndex;

  // Backend content can be normalized after commit. A bounded timestamp match preserves only
  // presentation metadata and never replaces the durable message body or attachments.
  let closestIndex = -1;
  let closestDistance = LEGACY_DUPLICATE_WINDOW_MS + 1;
  cachedMessages.forEach((candidate, index) => {
    if (!available(candidate, index)) return;
    const distance = Math.abs(candidate.timestamp - message.timestamp);
    if (distance <= LEGACY_DUPLICATE_WINDOW_MS
        && (distance < closestDistance
          || (distance === closestDistance
            && Math.abs(index - restoredIndex) < Math.abs(closestIndex - restoredIndex)))) {
      closestIndex = index;
      closestDistance = distance;
    }
  });
  return closestIndex;
};

export const mergeRestoredConversationPresentation = (
  restoredMessages: RestoredChatMessage[] = [],
  cachedMessages: RestoredChatMessage[] = [],
): RestoredChatMessage[] => {
  const usedCachedIndexes = new Set<number>();
  return restoredMessages.map((message, restoredIndex) => {
    const cachedIndex = presentationMatchIndex(
      message,
      restoredIndex,
      cachedMessages,
      usedCachedIndexes,
    );
    if (cachedIndex < 0) return message;

    usedCachedIndexes.add(cachedIndex);
    const cached = cachedMessages[cachedIndex];
    return {
      ...message,
      ...(cached.reasoning && { reasoning: cached.reasoning }),
      ...(cached.routeType && { routeType: cached.routeType }),
      ...(cached.language && { language: cached.language }),
      ...(cached.steps?.length && { steps: cached.steps }),
    };
  });
};

export const buildRestoredConversationMessages = (
  messages: RestorableConversationMessage[] = [],
  diagramTitle = 'Restored Diagram',
  attachmentMetadata: Record<string, RestoredAttachmentMetadata> = {},
): RestoredChatMessage[] => {
  const restored = removeLegacyV2DuplicateMessages(messages)
    .filter(message => message.content?.trim())
    .map((message, index) => ({
      id: message.clientMessageId || `restored-message-${index}`,
      role: normalizeRole(message.role),
      content: message.content?.trim() || '',
      timestamp: parseTimestamp(message.createdAt),
      evidenceClaims: message.evidenceClaims,
      evidenceSources: message.evidenceSources,
      attachments: message.attachmentRefs?.map(ref => {
        const metadata = attachmentMetadata[ref];
        return {
          uploadId: ref,
          fileName: metadata?.fileName || ref,
          state: metadata?.state || 'SUCCEEDED',
          ...(metadata?.materialId && { materialId: metadata.materialId }),
          ...(metadata?.versionId && { versionId: metadata.versionId }),
        };
      }),
    }));

  if (restored.length > 0) {
    return restored;
  }

  return [{
    id: `${Date.now()}-restore-loaded`,
    role: 'agent',
    content: `Loaded "${diagramTitle}".`,
    timestamp: Date.now(),
  }];
};
