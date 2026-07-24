export type ConversationAttachment = {
  uploadId: string;
  fileName: string;
  state: string;
  materialId?: string;
  versionId?: string;
  errorCode?: string;
};

type StorageLike = Pick<Storage, 'getItem' | 'setItem'>;

const keyFor = (sessionId: string) => `drawio_conversation_attachments:${sessionId}`;
const selectionKeyFor = (sessionId: string) => `drawio_conversation_attachment_selection:${sessionId}`;
const opaqueIds = (values: string[]) => [...new Set(values.map(value => value.trim()).filter(Boolean))];

export const readConversationAttachments = (storage: StorageLike | null, sessionId: string): ConversationAttachment[] => {
  if (!storage || !sessionId) return [];
  try {
    const parsed = JSON.parse(storage.getItem(keyFor(sessionId)) || '[]');
    return Array.isArray(parsed)
      ? parsed.filter(item => item && typeof item.uploadId === 'string' && typeof item.fileName === 'string')
      : [];
  } catch {
    return [];
  }
};

export const writeConversationAttachments = (
  storage: StorageLike | null,
  sessionId: string,
  attachments: ConversationAttachment[],
) => {
  if (!storage || !sessionId) return;
  storage.setItem(keyFor(sessionId), JSON.stringify(attachments));
};

/** A missing key means legacy state; an empty array is an intentional "use no attachments" choice. */
export const readConversationAttachmentSelection = (
  storage: StorageLike | null,
  sessionId: string,
): string[] | null => {
  if (!storage || !sessionId) return null;
  const stored = storage.getItem(selectionKeyFor(sessionId));
  if (stored === null) return null;
  try {
    const parsed = JSON.parse(stored);
    return Array.isArray(parsed) ? opaqueIds(parsed.filter(value => typeof value === 'string')) : null;
  } catch {
    return null;
  }
};

export const writeConversationAttachmentSelection = (
  storage: StorageLike | null,
  sessionId: string,
  selectedUploadIds: string[],
) => {
  if (!storage || !sessionId) return;
  storage.setItem(selectionKeyFor(sessionId), JSON.stringify(opaqueIds(selectedUploadIds)));
};

/** Prevents a stale selection from referring to an attachment outside the visible session pool. */
export const reconcileAttachmentSelection = (
  attachments: ConversationAttachment[],
  selectedUploadIds: string[],
) => {
  const available = new Set(attachments.map(attachment => attachment.uploadId));
  return opaqueIds(selectedUploadIds).filter(uploadId => available.has(uploadId));
};

/** Newly uploaded material participates in the current request unless the user opts it out. */
export const addAttachmentToCurrentSelection = (selectedUploadIds: string[], uploadId: string) =>
  opaqueIds([...selectedUploadIds, uploadId]);

/** Sends the visible selection unchanged so the server can return a typed state/authorization stop. */
export const selectedAttachmentIdsForRequest = (
  attachments: ConversationAttachment[],
  selectedUploadIds: string[],
) => {
  const selected = new Set(opaqueIds(selectedUploadIds));
  return attachments
    .map(attachment => attachment.uploadId)
    .filter(uploadId => selected.has(uploadId));
};
