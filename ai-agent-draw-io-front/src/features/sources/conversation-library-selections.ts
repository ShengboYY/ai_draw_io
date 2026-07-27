export type ConversationLibrarySelection = {
  materialId: string;
  versionId: string;
  displayName: string;
  kind: string;
};

export const MAX_CONVERSATION_LIBRARY_SELECTIONS = 20;

type StorageLike = Pick<Storage, 'getItem' | 'setItem'>;

const storageKey = (sessionId: string) =>
  `drawio_conversation_library_selections:${sessionId.trim()}`;

const isSelection = (value: unknown): value is ConversationLibrarySelection => {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Record<string, unknown>;
  return ['materialId', 'versionId', 'displayName', 'kind']
    .every(field => typeof candidate[field] === 'string' && candidate[field].trim().length > 0);
};

export const readConversationLibrarySelections = (
  storage: StorageLike,
  sessionId: string,
): ConversationLibrarySelection[] => {
  if (!sessionId.trim()) return [];
  try {
    const parsed = JSON.parse(storage.getItem(storageKey(sessionId)) || '[]') as unknown;
    return Array.isArray(parsed)
      ? parsed.filter(isSelection).slice(0, MAX_CONVERSATION_LIBRARY_SELECTIONS)
      : [];
  } catch {
    return [];
  }
};

export const writeConversationLibrarySelections = (
  storage: StorageLike,
  sessionId: string,
  selections: ConversationLibrarySelection[],
) => {
  if (!sessionId.trim()) return;
  // Store immutable version IDs so replacing a Library material never changes an in-flight conversation.
  storage.setItem(storageKey(sessionId), JSON.stringify(
    selections.filter(isSelection).slice(0, MAX_CONVERSATION_LIBRARY_SELECTIONS),
  ));
};
