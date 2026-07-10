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
  thumbnailUrl?: string;
  version?: number;
  updatedAt?: string;
}

export interface DiagramCanvasStateResponseDTO {
  diagramId: string;
  userId?: string;
  title?: string;
  diagramType?: string;
  thumbnailUrl?: string;
  currentXml?: string;
  contentHash?: string;
  saveStatus?: 'CREATED' | 'UPDATED' | 'NOOP' | string;
  summary?: string;
  version?: number;
  updatedAt?: string;
}

export interface UpdateDiagramTitleRequestDTO {
  userId?: string;
  title: string;
}

export interface UpdateDiagramThumbnailRequestDTO {
  userId?: string;
  thumbnailDataUrl: string;
}

export interface SaveDiagramCanvasStateRequestDTO {
  userId?: string;
  expectedVersion?: number;
  canvasXml: string;
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
  /** Client-generated id used to correlate browser request, backend logs, and stream metadata. */
  requestId?: string;
  /** Server-owned run id; clients only read it from responses. */
  runId?: string;
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
  /** Recent visible chat turns for intent routing context only. */
  conversationMessages?: DiagramConversationMessageDTO[];
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
    requestId?: string;
    runId?: string;
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

export interface ProviderPresetDTO {
  id: string;
  displayName: string;
  baseUrl: string;
  completionsPath: string;
  models: string[];
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

// ── Admin telemetry / trace visualization ──────────────────────────────────

export interface AdminUsageDimensionDTO {
  provider?: string;
  model?: string;
  credentialSource?: string;
  llmCallCount?: number;
  successfulCallCount?: number;
  failedCallCount?: number;
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  unknownTokenCallCount?: number;
  averageLatencyMs?: number;
}

export interface AdminUsageDashboardDTO {
  requestCount?: number;
  successfulRequestCount?: number;
  failedRequestCount?: number;
  runningRequestCount?: number;
  requestSuccessRate?: number;
  requestFailureRate?: number;
  llmCallCount?: number;
  successfulLlmCallCount?: number;
  failedLlmCallCount?: number;
  toolCallCount?: number;
  successfulToolCallCount?: number;
  failedToolCallCount?: number;
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  unknownTokenLlmCallCount?: number;
  averageRunLatencyMs?: number;
  maxRunLatencyMs?: number;
  groups?: AdminUsageDimensionDTO[];
}

export interface AdminRunMetadataDTO {
  id: string;
  requestId?: string;
  diagramId?: string;
  userId?: string;
  agentId?: string;
  sessionId?: string;
  requestType?: string;
  credentialSource?: string;
  modelCredentialId?: string;
  status?: string;
  errorClass?: string;
  startedAt?: string;
  completedAt?: string;
  latencyMs?: number;
  stepCount?: number;
  llmCallCount?: number;
  toolCallCount?: number;
  traceEventCount?: number;
  knownTotalTokens?: number;
}

export interface AdminRunStepDTO {
  id: string;
  runId?: string;
  parentId?: string;
  userId?: string;
  phase?: string;
  status?: string;
  errorClass?: string;
  startedAt?: string;
  completedAt?: string;
  latencyMs?: number;
}

export interface AdminLlmCallDTO {
  id: string;
  runId?: string;
  parentId?: string;
  userId?: string;
  phase?: string;
  provider?: string;
  model?: string;
  credentialSource?: string;
  modelCredentialId?: string;
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  status?: string;
  errorClass?: string;
  startedAt?: string;
  completedAt?: string;
  latencyMs?: number;
}

export interface AdminToolCallDTO {
  id: string;
  runId?: string;
  parentId?: string;
  userId?: string;
  phase?: string;
  toolName?: string;
  status?: string;
  errorClass?: string;
  startedAt?: string;
  completedAt?: string;
  latencyMs?: number;
}

export interface AdminTraceEventDTO {
  id: string;
  runId?: string;
  parentId?: string;
  requestId?: string;
  userId?: string;
  sequenceNo?: number;
  eventType?: string;
  phase?: string;
  status?: string;
  metadataJson?: string;
  occurredAt?: string;
}

export type AdminTimelineSource = 'trace_event' | 'step' | 'llm_call' | 'tool_call';

export interface AdminRunTimelineEventDTO {
  id: string;
  source?: AdminTimelineSource;
  runId?: string;
  parentId?: string;
  requestId?: string;
  userId?: string;
  sequenceNo?: number;
  eventType?: string;
  phase?: string;
  status?: string;
  detail?: string;
  metadataJson?: string;
  occurredAt?: string;
  latencyMs?: number;
}

export interface AdminRunDetailDTO {
  run: AdminRunMetadataDTO;
  steps?: AdminRunStepDTO[];
  llmCalls?: AdminLlmCallDTO[];
  toolCalls?: AdminToolCallDTO[];
  traceEvents?: AdminTraceEventDTO[];
  timeline?: AdminRunTimelineEventDTO[];
}

export type AdminDiagramTraceKind = 'RUN' | 'STEP' | 'LLM' | 'TOOL' | 'EVENT' | 'DIAGRAM' | 'QUALITY';

export interface AdminDiagramEffectDTO {
  diagramId?: string;
  beforeVersion?: number;
  afterVersion?: number;
  beforeHash?: string;
  afterHash?: string;
  xmlChanged?: boolean;
  thumbnailChanged?: boolean;
  renderStatus?: string;
  thumbnailUrl?: string;
}

export interface AdminDiagramTraceSpanDTO {
  id: string;
  parentId?: string;
  kind?: AdminDiagramTraceKind;
  name?: string;
  runId?: string;
  requestId?: string;
  userId?: string;
  sequenceNo?: number;
  eventType?: string;
  phase?: string;
  status?: string;
  startedAt?: string;
  completedAt?: string;
  latencyMs?: number;
  provider?: string;
  model?: string;
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  estimatedCost?: number;
  toolName?: string;
  metadataJson?: string;
  errorClass?: string;
  diagramEffect?: AdminDiagramEffectDTO;
}

export interface AdminDiagramTraceSummaryDTO {
  status?: string;
  outcome?: string;
  runId?: string;
  requestId?: string;
  userId?: string;
  sessionId?: string;
  diagramId?: string;
  agentId?: string;
  requestType?: string;
  latencyMs?: number;
  llmCallCount?: number;
  toolCallCount?: number;
  eventCount?: number;
  spanCount?: number;
  totalTokens?: number;
  estimatedCost?: number;
}

export interface AdminDiagramSnapshotDTO {
  id?: string;
  runId?: string;
  spanId?: string;
  diagramId?: string;
  version?: number;
  canvasHash?: string;
  thumbnailUrl?: string;
  summary?: string;
  createdAt?: string;
}

export interface AdminDiagramFindingDTO {
  severity?: 'INFO' | 'WARNING' | 'ERROR' | string;
  code?: string;
  title?: string;
  description?: string;
  spanId?: string;
  diagramId?: string;
  suggestion?: string;
}

export interface AdminPayloadAvailabilityDTO {
  onDemand?: boolean;
  status?: string;
  note?: string;
}

export interface AdminDiagramTraceDTO {
  run: AdminRunMetadataDTO;
  summary?: AdminDiagramTraceSummaryDTO;
  spans?: AdminDiagramTraceSpanDTO[];
  snapshots?: AdminDiagramSnapshotDTO[];
  findings?: AdminDiagramFindingDTO[];
  payloadAvailability?: AdminPayloadAvailabilityDTO;
}

export interface AdminDebugTraceCaptureDTO {
  id: string;
  controlId?: string;
  userId?: string;
  runId?: string;
  spanId?: string;
  eventType?: string;
  payloadKind?: string;
  contentType?: string;
  content?: string;
  contentSha256?: string;
  originalLength?: number;
  truncated?: boolean;
  contentExpiresAt?: string;
  contentDeletedAt?: string;
  createdAt?: string;
}

export interface AdminDebugTraceControlDTO {
  id: string;
  scopeUserId?: string;
  scopeRunId?: string;
  scopeStartsAt?: string;
  scopeEndsAt?: string;
  enabled?: boolean;
  createdAt?: string;
}

export interface AdminDebugTraceControlRequestDTO {
  userId?: string;
  runId?: string;
  startsAt?: string;
  endsAt?: string;
}
