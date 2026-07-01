type RestorableConversationMessage = {
  clientMessageId?: string;
  role?: string;
  content?: string;
  createdAt?: string;
};

type RestoredChatMessage = {
  id: string;
  role: 'user' | 'agent';
  content: string;
  timestamp: number;
};

const normalizeRole = (role?: string): 'user' | 'agent' => (
  role === 'user' ? 'user' : 'agent'
);

const parseTimestamp = (value?: string) => {
  const parsed = value ? Date.parse(value) : Number.NaN;
  return Number.isNaN(parsed) ? Date.now() : parsed;
};

export const buildRestoredConversationMessages = (
  messages: RestorableConversationMessage[] = [],
  diagramTitle = 'Restored Diagram',
): RestoredChatMessage[] => {
  const restored = messages
    .filter(message => message.content?.trim())
    .map((message, index) => ({
      id: message.clientMessageId || `restored-message-${index}`,
      role: normalizeRole(message.role),
      content: message.content?.trim() || '',
      timestamp: parseTimestamp(message.createdAt),
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
