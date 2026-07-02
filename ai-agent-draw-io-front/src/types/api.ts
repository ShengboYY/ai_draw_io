export interface Response<T> {
    code: string;
    info: string;
    data: T;
}

export type ApiErrorCode = 'AUTH_RATE_LIMITED' | 'DEMO_QUOTA_EXHAUSTED' | 'PLATFORM_QUOTA_EXHAUSTED' | (string & {});

export interface AiAgentConfigResponseDTO {
    agentId: string;
    agentName: string;
    agentDesc: string;
}

export interface CreateSessionRequestDTO {
    agentId: string;
    userId?: string;
}

export interface CreateSessionResponseDTO {
    sessionId: string;
}

export interface CurrentAccountResponseDTO {
  ownerId: string;
  ownerType: 'ANONYMOUS' | 'USER';
  authenticated: boolean;
  emailVerified: boolean;
  accountStatus: 'ANONYMOUS' | 'PENDING_VERIFICATION' | 'ACTIVE' | 'DISABLED' | 'DELETED';
  demoQuotaLimit?: number;
  demoQuotaUsed?: number;
  demoQuotaRemaining?: number;
  demoQuotaExhausted?: boolean;
  platformDailyQuotaLimit?: number;
  platformDailyQuotaUsed?: number;
  platformDailyQuotaRemaining?: number;
  platformDailyQuotaExhausted?: boolean;
  platformDailyQuotaDate?: string;
  platformRunCount?: number;
  userKeyRunCount?: number;
  platformLlmCallCount?: number;
  userKeyLlmCallCount?: number;
  toolCallCount?: number;
  knownTotalTokens?: number;
  unknownTokenLlmCallCount?: number;
}

export interface DiagramSummaryResponseDTO {
  diagramId: string;
  title?: string;
  diagramType?: string;
  version?: number;
  updatedAt?: string;
}

export interface DiagramCanvasStateResponseDTO {
  diagramId: string;
  userId?: string;
  title?: string;
  diagramType?: string;
  currentXml?: string;
  summary?: string;
  version?: number;
  updatedAt?: string;
}

export interface UpdateDiagramTitleRequestDTO {
  userId?: string;
  title: string;
}

export interface DiagramConversationMessageDTO {
  clientMessageId: string;
  sessionId?: string;
  role: 'user' | 'agent';
  content: string;
  createdAt?: string;
}

export interface SaveDiagramMessagesRequestDTO {
  userId?: string;
  sessionId?: string;
  messages: DiagramConversationMessageDTO[];
}

export interface ImportAnonymousWorkspaceRequestDTO {
  anonymousWorkspaceId: string;
}

export interface ImportAnonymousWorkspaceResponseDTO {
  importedCount: number;
  diagrams: DiagramSummaryResponseDTO[];
}

export interface ChatRequestDTO {
  agentId: string;
  userId: string;
  sessionId: string;
  message: string;
  /** Stable diagram id used by the backend CanvasStateStore. */
  diagramId?: string;
  /** Optimistic-lock version expected by this mutation request. */
  expectedVersion?: number;
  /** Current Draw.io XML, kept out of message so routers can avoid full-canvas prompt noise. */
  canvasXml?: string;
  /** Compact canvas summary for intent routing and answer-only requests. */
  canvasSummary?: string;
  canvasSnapshot?: {
    valid?: boolean;
    nodeCount?: number;
    edgeCount?: number;
    bounds?: { x?: number; y?: number; width?: number; height?: number };
    labels?: string[];
  };
  clientHints?: {
    maxReviewIterations?: number;
    skills?: string[];
  };
  modelCredentialId?: string;
  customBaseUrl?: string;
  customApiKey?: string;
  customCompletionsPath?: string;
  customModel?: string;
  maxReviewIterations?: number;
  /** User-specified skills (via the "/" picker); overrides the router's auto-selection. */
  skills?: string[];
}

export interface ChatResponseDTO {
    content: string;
    type: string;
}

export type LoginStatus =
  | 'SUCCESS'
  | 'INVALID_CREDENTIALS'
  | 'NOT_VERIFIED'
  | 'DISABLED'
  | 'LOCKED'
  | 'ANONYMOUS';

export interface LoginRequestDTO {
  email: string;
  password: string;
}

export interface LoginResponseDTO {
  status: LoginStatus;
  userId?: string;
  email?: string;
  accountStatus?: 'ANONYMOUS' | 'PENDING_VERIFICATION' | 'ACTIVE' | 'DISABLED' | 'DELETED';
}

export interface RegisterAccountRequestDTO {
  email: string;
  password: string;
}

export interface RegisterAccountResponseDTO {
  submitted: boolean;
}

export interface ResendVerificationRequestDTO {
  email: string;
}

export type EmailVerificationStatus = 'SUCCESS' | 'EXPIRED' | 'ALREADY_USED' | 'INVALID';

export interface VerifyEmailResponseDTO {
  status: EmailVerificationStatus;
}

export interface PasswordResetRequestDTO {
  email: string;
}

export interface PasswordResetConfirmRequestDTO {
  token: string;
  password: string;
}

export type PasswordResetStatus = 'SUCCESS' | 'EXPIRED' | 'ALREADY_USED' | 'INVALID';

export interface PasswordResetConfirmResponseDTO {
  status: PasswordResetStatus;
}

export interface CreateModelCredentialRequestDTO {
  provider: string;
  baseUrl: string;
  model: string;
  completionPath: string;
  displayName: string;
  apiKey: string;
}

export interface ModelCredentialResponseDTO {
  id: string;
  provider: string;
  baseUrl: string;
  model: string;
  completionPath: string;
  displayName: string;
  maskedApiKey: string;
  encryptionProvider?: string;
  encryptionKeyId?: string;
  keyLastFour?: string;
  status?: string;
  createdAt?: string;
  updatedAt?: string;
  disabledAt?: string;
}
