export type ConversationAttachment = {
  uploadId: string;
  fileName: string;
  state: string;
  errorCode?: string;
};

type StorageLike = Pick<Storage, 'getItem' | 'setItem'>;

const keyFor = (sessionId: string) => `drawio_conversation_attachments:${sessionId}`;

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
