export interface Response<T> {
    code: string;
    info: string;
    data: T;
}

export type EvaluationTarget = 'FULL_AGENT' | 'INTENT_ROUTER' | 'DRAWING_QUALITY' | 'VISUAL_REVIEW';
export type EvaluationTargetMigrationStatus = 'INFERRED' | 'AMBIGUOUS' | 'CONFIRMED';

export interface EvaluationProfileDTO {
  profileId: string;
  version: string;
  target: EvaluationTarget;
  runnerAdapter: string;
  mode: 'MODE_B' | 'MODE_C' | 'RELEASE';
  repetitions: number;
  gateEligible: boolean;
  configJson: string;
}

export interface EvalCaseWorkingCopyDTO {
  id: string;
  caseId: string;
  caseVersion: string;
  sourceType: 'MANUAL' | 'IMPORTED' | 'TRACE_DRAFT' | 'WORKING_COPY_CLONE';
  candidateId?: string;
  status: string;
  ownerUserId: string;
  revision: number;
  definition: Record<string, unknown>;
  evaluationTarget?: EvaluationTarget;
  targetMigrationStatus?: EvaluationTargetMigrationStatus;
  createdAt?: string;
  updatedAt?: string;
}

export interface EvalCaseValidationResultDTO {
  passed: boolean;
  evidence: string[];
  workingCopy: EvalCaseWorkingCopyDTO;
}

export interface EvalCaseDryRunResultDTO {
  result: { status: string; passed: boolean; latencyMs?: number; graders?: Array<{ graderName: string; graderVersion?: string; passed: boolean; evidence?: string[] }> };
  workingCopy: EvalCaseWorkingCopyDTO;
  initialCanvasXml?: string;
  finalCanvasXml?: string;
  trace?: { routing?: { routeType?: string }; toolCalls?: Array<{ name?: string; status?: string }> };
}

export interface PublishedEvalCaseDTO {
  caseId: string;
  caseVersion: string;
  contentHash: string;
  evaluationTarget?: EvaluationTarget;
  targetMigrationStatus?: EvaluationTargetMigrationStatus;
  approvedBy: string;
  publishedAt?: string;
  retiredAt?: string;
}

export interface EvalDatasetDTO {
  id: string;
  name: string;
  datasetClass: 'DEV' | 'CORE' | 'SEQUESTERED';
  evaluationTarget?: EvaluationTarget;
  ownerUserId: string;
  createdAt?: string;
}

export interface EvalDatasetMemberDTO { caseId: string; caseVersion: string }

export interface EvalDatasetVersionDTO {
  datasetId: string;
  version: string;
  datasetClass: 'DEV' | 'CORE' | 'SEQUESTERED';
  evaluationTarget?: EvaluationTarget;
  status: 'DRAFT' | 'VALIDATED' | 'PUBLISHED' | 'RETIRED';
  contentHash?: string;
  revision: number;
  members: EvalDatasetMemberDTO[];
  publishedBy?: string;
  publishedAt?: string;
}

export interface EvalDatasetCoverageDTO {
  caseCount: number;
  routes: Record<string, number>;
  risks: Record<string, number>;
  languages: Record<string, number>;
  diagramTypes: Record<string, number>;
  agents: Record<string, number>;
}

export interface EvalRunSummaryDTO {
  id: string; mode: 'MODE_B' | 'MODE_C' | 'RELEASE'; datasetId: string; datasetVersion: string;
  evaluationTarget?: EvaluationTarget;
  profileId?: string; profileVersion?: string; profileConfigHash?: string;
  status: string; gitSha: string; executionProfileHash?: string; repetitions: number;
  totalEpisodes: number; completedEpisodes: number; passCount: number; failCount: number;
  errorCount: number; unavailableCount: number; progress: number; totalLatencyMs: number;
  estimatedCost: number; baselineRef?: string; candidateRef?: string;
  createdAt?: string; startedAt?: string; completedAt?: string;
}

export interface EvalTargetReportDTO {
  runId: string; target: EvaluationTarget; totalEpisodes: number; eligibleEpisodes: number;
  excludedErrors: number; excludedUnavailable: number;
  latency: { sampleCount: number; medianMs?: number; maxMs?: number; p95Ms?: number;
    p95Availability: 'AVAILABLE' | 'COUNT_ONLY' | 'UNAVAILABLE'; p95Reason?: string };
  fullAgent?: { availability: 'AVAILABLE' | 'COUNT_ONLY' | 'UNAVAILABLE'; unavailableReason?: string;
    tsrAtOne?: number; ciLower?: number; ciUpper?: number; errorRate?: number; estimatedCost: number;
    funnel: Array<{ stage: string; inputCount: number; eligibleCount: number; passedCount: number; unavailableCount: number; passRate?: number;
      availability: 'AVAILABLE' | 'COUNT_ONLY' | 'UNAVAILABLE'; failedEpisodeIds: string[]; unavailableEpisodeIds: string[] }> };
  router?: { accuracy?: number; classifiedCount: number; invalidCount: number; evidenceUnavailableCount: number; macroF1?: number;
    macroF1Availability: 'AVAILABLE' | 'COUNT_ONLY' | 'UNAVAILABLE'; macroF1Reason?: string;
    repeatStability?: number; stabilityAvailability: 'AVAILABLE' | 'COUNT_ONLY' | 'UNAVAILABLE'; stabilityReason?: string;
    confusionMatrix: Array<{ expectedRoute: string; actualRoute: string; count: number; episodeIds: string[] }>;
    perRoute: Array<{ route: string; support: number; precision?: number; recall?: number; f1?: number }> };
  drawing?: { layers: Array<{ graderName: string; eligibleCount: number; passedCount: number; failedCount: number;
      unavailableCount: number; notRequiredCount: number; passRate?: number; availability: 'AVAILABLE' | 'COUNT_ONLY' | 'UNAVAILABLE';
      failedEpisodeIds: string[]; unavailableEpisodeIds: string[] }>;
    issueSeverities: Array<{ severity: string; count: number; episodeIds: string[] }>;
    evidence: Array<{ episodeId: string; caseId: string; artifactRef?: string; beforeAvailable: boolean;
      afterAvailable: boolean; canvasChanged?: boolean }>;
    judgeAvailableCount: number; judgeUnavailableCount: number; judgeNotRequiredCount: number };
}

export interface EvalGraderRecordDTO {
  episodeId: string; graderName: string; graderVersion: string; status: string;
  severity: string; score?: number; evidenceJson: string;
}

export interface EvalJudgeRecordDTO {
  episodeId: string; judgeVersion?: string; calibrationVersion?: string;
  status: string; scoreJson: string; evidenceJson: string;
}

export interface EvalEpisodeViewDTO {
  id: string; caseId: string; caseVersion: string; repetition: number; attempt: number;
  status: 'PASS' | 'FAIL' | 'ERROR' | 'UNAVAILABLE'; route: string; risk: string;
  language: string; diagramType: string; agent: string; latencyMs: number; estimatedCost: number;
  errorClass?: string; errorMessage?: string; blockingReason?: string; graders: EvalGraderRecordDTO[];
  judge?: EvalJudgeRecordDTO;
}

export interface EvalEpisodeDetailDTO { episode: EvalEpisodeViewDTO; input: Record<string, unknown>; expected: unknown }
export interface EvalEpisodeArtifactDTO {
  trace?: { routing?: { routeType?: string; diagramType?: string; skillName?: string }; steps?: Array<{ phase?: string; agentId?: string; status?: string }>; toolCalls?: Array<{ name?: string; status?: string }> };
  initialCanvasXml?: string; finalCanvasXml?: string;
  initialCanvasImageDataUrl?: string; finalCanvasImageDataUrl?: string; semanticDiff: string[];
}

export interface EvalStatisticalReportDTO {
  decision: 'READY' | 'NO_DECISION'; totalSamples: number; eligibleSamples: number; eligibleCases: number;
  tsrAtOne: number; ciLower: number; ciUpper: number; errorRate: number; graderAvailability: number;
  totalLatencyMs: number; inputTokens: number; outputTokens: number; estimatedCost: number;
  perCaseSuccessProbability: Record<string, number>;
}

export interface EvalComparisonDTO {
  decision: 'READY' | 'NO_DECISION'; blocked: boolean; delta: number; ciLower: number; ciUpper: number; pairedCases: number;
}

export interface EvalGateDecisionDTO {
  evalRunId: string; gateVersion: string; outcome: 'PASS' | 'BLOCK' | 'NO_DECISION'; reasonsJson: string;
  decidedAt: string; overrideApproved: boolean; overrideReason?: string; overriddenBy?: string; overriddenAt?: string;
}

export interface EvalLiveRunReportDTO {
  statistics: EvalStatisticalReportDTO; comparison: EvalComparisonDTO;
  readiness: { providerCredentialReady: boolean; judgeCalibrationApproved: boolean; calibrationVersion?: string;
    judgeVersion?: string; visualJudgeCalibrationApproved: boolean; visualCalibrationVersion?: string;
    visualJudgeVersion?: string; sequesteredCaseCount: number; minimumSequesteredCases: number };
  gate?: EvalGateDecisionDTO;
}

export interface EvalCompositeTargetDecisionDTO {
  target: EvaluationTarget;
  evalRunId?: string;
  required: boolean;
  outcome: 'PASS' | 'BLOCK' | 'NO_DECISION';
  overrideApproved: boolean;
  hardFailure: boolean;
  reasons: string[];
}

export interface EvalCompositeGateDecisionDTO {
  outcome: 'PASS' | 'BLOCK' | 'NO_DECISION';
  /** Stable CI contract: 0=PASS, 1=BLOCK, 2=NO_DECISION. */
  exitCode: 0 | 1 | 2;
  targets: Partial<Record<EvaluationTarget, EvalCompositeTargetDecisionDTO>>;
  reasons: string[];
  warnings: string[];
}

export interface EvalCanaryAssessmentDTO {
  id: string;
  evalRunId: string;
  deploymentRef: string;
  policyVersion: string;
  outcome: 'CONTINUE' | 'HALT_RECOMMENDED' | 'NO_DECISION';
  reasons: string[];
  baselineRequests: number;
  canaryRequests: number;
  canaryFailures: number;
  criticalFindings: number;
  infrastructureErrors: number;
  p95LatencyMs: number;
  averageCost: number;
  createdBy: string;
  createdAt: string;
}

export interface EvalCaseHealthDTO {
  caseId: string;
  caseVersion: string;
  baselineReproduced?: boolean;
  healthStatus: 'HEALTHY' | 'FLAKY' | 'ALWAYS_PASS_REVIEW' | 'UNSCORABLE' | 'STALE_REVIEW' | 'BROKEN_BASELINE';
  summary: string;
  updatedAt: string;
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

export interface AnonymousWorkspaceResponseDTO {
  ownerId: string;
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
  expectedContentHash?: string;
  visualRepairSourceRunId?: string;
  visualRepairRunId?: string;
  visualRepairRound?: number;
  canvasXml: string;
}

export interface DiagramConversationMessageDTO {
  clientMessageId: string;
  sessionId?: string;
  role: 'user' | 'agent';
  content: string;
  createdAt?: string;
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
}

export interface SaveDiagramMessagesRequestDTO {
  userId?: string;
  sessionId?: string;
  messages: DiagramConversationMessageDTO[];
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
  /** Stable assistant message id for atomic evidence-answer persistence. */
  responseMessageId?: string;
  /** Server-owned run id; clients only read it from responses. */
  runId?: string;
  /** Stable diagram id used by the backend CanvasStateStore. */
  diagramId?: string;
  /** Optimistic-lock version expected by this mutation request. */
  expectedVersion?: number;
  /** Optimistic-lock hash expected by this mutation request. */
  expectedContentHash?: string;
  /** Current Draw.io XML, kept out of message so routers can avoid full-canvas prompt noise. */
  canvasXml?: string;
  /** Legacy drawer-only summary; Intent Router V2 never receives it. */
  canvasSummary?: string;
  /** Opaque ids for uploads attached to this message; the server revalidates each one. */
  attachmentUploadIds?: string[];
  sourceMode?: 'NONE' | 'AUTO' | 'EXPLICIT' | 'EXPLICIT_ONLY';
  /** Optional image-conversion preference; omitted when the Router should decide. */
  sourceUseOverride?: 'DIRECT' | 'DIRECT_AND_RETRIEVAL';
  /** Explicit bounded answers to issues from a prior direct-image attempt. */
  directClarifications?: Array<{
    reasonCode: string;
    resolution: 'ACCEPT_OBSERVED' | 'FORWARD' | 'REVERSE' | 'BIDIRECTIONAL' | 'UNDIRECTED';
  }>;
  directConfirmationSourceVersionId?: string;
  selectedVersionIds?: string[];
  selectedCellIds?: string[];
  selectionCanvasVersion?: number;
  selectionContentHash?: string;
  /** Real draw.io PNG used by review_only; never persisted as chat content. */
  canvasImageDataUrl?: string;
  canvasImageRendererVersion?: 'drawio-embed-png-v1';
  canvasSnapshot?: {
    valid?: boolean;
    nodeCount?: number;
    edgeCount?: number;
    bounds?: { x?: number; y?: number; width?: number; height?: number };
    labels?: string[];
  };
  clientHints?: {
    maxDeterministicRepairRounds?: number;
    /** Legacy request field accepted temporarily by the backend. */
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
  maxDeterministicRepairRounds?: number;
  /** Legacy request field accepted temporarily by the backend. */
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

export type CanvasVisualReviewStage = 'POST_MUTATION' | 'POST_REPAIR' | 'VERIFY_ONLY';

export interface CanvasVisualReviewEvidenceDTO {
  role: 'PAGE_OVERVIEW' | 'DETAIL_TILE';
  pageId?: string;
  pageName?: string;
  tileIndex?: number;
  tileCount?: number;
  dataUrl: string;
}

export interface CanvasVisualReviewRequestDTO {
  userId: string;
  agentId: string;
  sessionId: string;
  requestId?: string;
  sourceRunId: string;
  parentRunId: string;
  visualRepairRound: number;
  diagramId: string;
  expectedVersion: number;
  beforeContentHash?: string;
  shadow?: boolean;
  expectedContentHash: string;
  originalUserTask: string;
  diagramType?: string;
  stage: CanvasVisualReviewStage;
  beforeImageDataUrl?: string;
  afterImageDataUrl: string;
  afterImagePageId?: string;
  afterImagePageName?: string;
  totalPageCount?: number;
  truncatedPageCount?: number;
  additionalAfterImages?: CanvasVisualReviewEvidenceDTO[];
  rendererVersion: 'drawio-embed-png-v1';
  modelCredentialId?: string;
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
  admin?: boolean;
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
  pricingVersion?: string;
  inputPricePerMillionUsd?: number;
  outputPricePerMillionUsd?: number;
  estimatedCostUsd?: number;
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
  providerRequestId?: string;
  providerResponseId?: string;
  ttftMs?: number;
  attemptCount?: number;
  retryCount?: number;
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
  changedCellCount?: number;
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
  providerRequestId?: string;
  providerResponseId?: string;
  ttftMs?: number;
  attemptCount?: number;
  retryCount?: number;
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
  changedCellCount?: number;
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

export interface EvalCaseCandidateDTO {
  id: string;
  sourceRunId: string;
  sourceSpanId?: string;
  sourcePhase?: string;
  sourceAgentId?: string;
  failureFamily: string;
  ruleId: string;
  evidenceSummary: string;
  risk: string;
  discoveredAt?: string;
  policyVersion: string;
  status: string;
  createdBy: string;
  detectionSource?: 'MANUAL' | 'RULE_DETECTED' | 'MODEL_DETECTED';
  modelVersion?: string;
  modelConfidence?: number;
  modelEvidence?: string[];
}

export interface SemanticMinerRunDTO {
  id: string;
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'COMPLETED_WITH_ERRORS' | 'UNAVAILABLE';
  samplingPolicy: 'TARGETED' | 'RANDOM' | 'MIXED';
  requestedLimit: number;
  sampledCount: number;
  analyzedCount: number;
  candidateCount: number;
  errorCount: number;
  estimatedCostUsd: number;
  modelVersion: string;
  sanitizerVersion: string;
  availabilityReason?: string;
  createdBy: string;
  createdAt?: string;
  startedAt?: string;
  completedAt?: string;
}

export interface VisualMinerResultDTO {
  status: 'UNAVAILABLE' | 'NO_FINDING' | 'MERGED' | 'CANDIDATE_CREATED';
  reason?: string; candidateId?: string; confidence: number; estimatedCostUsd: number;
}

export interface TraceAnalysisJobDTO {
  id: string;
  scope: 'SINGLE_TRACE' | 'SAMPLE_BATCH';
  analyzerType: 'DETERMINISTIC' | 'LLM' | 'VLM';
  analyzerVersion: string;
  analyzerConfigHash: string;
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'PARTIAL' | 'FAILED' | 'CANCELLED';
  totalItems: number;
  succeededItems: number;
  failedItems: number;
  reservedCost: number;
  actualCost: number;
  createdAt?: string;
  startedAt?: string;
  completedAt?: string;
}

export interface TraceAnalysisItemDTO {
  id: string;
  jobId: string;
  sourceRunId: string;
  analyzerType: 'DETERMINISTIC' | 'LLM' | 'VLM';
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';
  outcomeStatus?: 'NO_FINDING' | 'CANDIDATE_CREATED' | 'MERGED' | 'UNAVAILABLE';
  attempt: number;
  candidateId?: string;
  latencyMs?: number;
  estimatedCost: number;
  errorClass?: string;
  errorMessage?: string;
}

export interface TraceAnalysisJobViewDTO {
  job: TraceAnalysisJobDTO;
  items: TraceAnalysisItemDTO[];
}

export interface TraceFindingViewDTO {
  candidateId: string;
  sourceRunId: string;
  routeType?: string;
  sourceAgentId?: string;
  sourceLatencyMs?: number;
  analyzerType: 'DETERMINISTIC' | 'LLM' | 'VLM';
  analyzerVersion?: string;
  failureFamily: string;
  risk: string;
  confidence?: number;
  analysisSummary: string;
  analysisEvidence: string[];
  evidenceRefs: string[];
  recommendation: {
    suspectedLayer: string;
    evidence: string;
    expectedImpact: string;
    suggestedExperiment: string;
    relatedFindingsCount: number;
    confidence?: number;
  };
  candidateStatus: string;
  statusGroup: string;
  discoveredAt?: string;
  reviewedBy?: string;
  reviewedAt?: string;
}

export interface EvalCasePromotionResultDTO {
  status: 'CREATED' | 'EXISTING_DRAFT' | 'ALREADY_PUBLISHED';
  candidateId: string;
  workingCopyId: string;
  caseId: string;
  caseVersion: string;
}

export interface EvalCaseDraftDTO {
  id: string;
  failureSummary: string;
  suspectedFailureFamily: string;
  userTurns: string[];
  initialFixtureHint: string;
  expectedRoute: string;
  suggestedAssertions: string[];
  confidence: string;
  needsHumanReview: boolean;
  sanitizerVersion: string;
  modelVersion: string;
}

export interface EvalDraftPreparationDTO {
  status: string;
  draft?: EvalCaseDraftDTO;
  sanitizerEvidence: string[];
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
