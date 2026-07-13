import { API_CONFIG } from '@/config/api-config';
import {
    Response,
    ApiErrorCode,
    AiAgentConfigResponseDTO,
    CreateSessionResponseDTO,
    ChatRequestDTO,
    ChatResponseDTO,
    CurrentAccountResponseDTO,
    DiagramCanvasStateResponseDTO,
    DiagramSummaryResponseDTO,
    DiagramConversationMessageDTO,
    ImportAnonymousWorkspaceRequestDTO,
    ImportAnonymousWorkspaceResponseDTO,
    SaveDiagramCanvasStateRequestDTO,
    SaveDiagramMessagesRequestDTO,
    UpdateDiagramThumbnailRequestDTO,
    UpdateDiagramTitleRequestDTO,
    LoginRequestDTO,
    LoginResponseDTO,
    PasswordResetConfirmRequestDTO,
    PasswordResetConfirmResponseDTO,
    PasswordResetRequestDTO,
    RegisterAccountRequestDTO,
    RegisterAccountResponseDTO,
    ResendVerificationRequestDTO,
    VerifyEmailResponseDTO,
    CreateModelCredentialRequestDTO,
    ModelCredentialResponseDTO,
    ProviderPresetDTO,
    AdminUsageDashboardDTO,
    AdminRunMetadataDTO,
    AdminRunDetailDTO,
    AdminDiagramTraceDTO,
    AdminDebugTraceCaptureDTO,
    AdminDebugTraceControlDTO,
    AdminDebugTraceControlRequestDTO,
    EvalCaseCandidateDTO,
    SemanticMinerRunDTO,
    VisualMinerResultDTO,
    EvalDraftPreparationDTO,
    EvalCaseWorkingCopyDTO,
    EvalCaseValidationResultDTO,
    EvalCaseDryRunResultDTO,
    PublishedEvalCaseDTO,
    EvalDatasetDTO,
    EvalDatasetVersionDTO,
    EvalDatasetMemberDTO,
    EvalDatasetCoverageDTO,
    EvaluationProfileDTO,
    EvalRunSummaryDTO,
    EvalEpisodeViewDTO,
    EvalEpisodeDetailDTO,
    EvalEpisodeArtifactDTO,
    EvalGateDecisionDTO,
    EvalLiveRunReportDTO,
    EvalCanaryAssessmentDTO,
    EvalCaseHealthDTO,
} from '@/types/api';

export class ApiResponseError extends Error {
    readonly code: ApiErrorCode;
    readonly info: string;
    readonly data?: unknown;

    constructor(code: ApiErrorCode, info: string, data?: unknown) {
        super(info);
        this.name = 'ApiResponseError';
        this.code = code;
        this.info = info;
        this.data = data;
    }
}

const handleResponse = async <T>(response: globalThis.Response): Promise<Response<T>> => {
    if (!response.ok) {
        const errorText = await response.text();
        throw new Error(`HTTP error! status: ${response.status}, message: ${errorText}`);
    }
    const data = await response.json();
    if (data.code !== "0000") {
        throw new ApiResponseError(data.code, data.info || 'Unknown API error', data.data);
    }
    return data;
};

const workspaceHeaders = (userId: string, requestId?: string) => ({
    'Content-Type': 'application/json',
    'X-Workspace-Id': userId,
    ...(requestId && { 'X-Request-Id': requestId }),
});

type HeaderMap = Record<string, string>;

interface CsrfTokenResponseDTO {
    headerName?: string;
    token?: string;
}

let csrfHeaderPromise: Promise<HeaderMap> | null = null;

const readCookie = (name: string): string | null => {
    if (typeof document === 'undefined') return null;
    const prefix = `${name}=`;
    const cookie = document.cookie.split('; ').find(part => part.startsWith(prefix));
    return cookie ? decodeURIComponent(cookie.slice(prefix.length)) : null;
};

const loadCsrfHeaders = async (): Promise<HeaderMap> => {
    const cookieToken = readCookie('XSRF-TOKEN');
    if (cookieToken) return { 'X-XSRF-TOKEN': cookieToken };

    const response = await fetch(`${API_CONFIG.BASE_URL}/auth/csrf`, {
        method: 'GET',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
    });
    const tokenResponse = await handleResponse<CsrfTokenResponseDTO>(response);
    const token = tokenResponse.data?.token;
    if (!token) return {};
    return { [tokenResponse.data.headerName || 'X-XSRF-TOKEN']: token };
};

const csrfHeaders = async (headers: HeaderMap): Promise<HeaderMap> => {
    // Reuse one in-flight request so parallel UI actions do not stampede /auth/csrf.
    csrfHeaderPromise = csrfHeaderPromise || loadCsrfHeaders();
    return { ...headers, ...(await csrfHeaderPromise) };
};

// Types for streaming drawio events
export interface DrawioNodeChunk {
    type: 'drawio_node';
    id: string;
    label: string;
    xml: string;
}

export interface DrawioEdgeChunk {
    type: 'drawio_edge';
    id: string;
    label: string;
    source: string;
    target: string;
    xml: string;
}

export interface DrawioPreviewChunk {
    type: 'drawio_preview';
    content: string;
}

export interface DrawioDoneChunk {
    type: 'drawio_done';
    content: string;
    diagramId?: string;
    version?: number;
    contentHash?: string;
    saveStatus?: 'CREATED' | 'UPDATED' | 'NOOP' | string;
    // "local": merge into the live canvas without remounting; "full"/absent: clean reload.
    mode?: 'local' | 'full';
}

export interface DrawioLegacyChunk {
    type: 'drawio';
    content: string;
}

export interface StatusChunk {
    type: 'status';
    content: string;
}

export interface ErrorChunk {
    type: 'error';
    code?: ApiErrorCode;
    content: string;
}

export interface UserChunk {
    type: 'user';
    content: string;
}

export interface DoneChunk {
    type: 'done';
}

export interface TokenChunk {
    type: 'token';
    content: string;
}

export interface MetaChunk {
    type: 'meta';
    requestId?: string;
    runId?: string;
}

export interface ReviewResultChunk {
    type: 'review_result';
    approved: boolean;
    content: string;
}

export interface ValidationResultChunk {
    type: 'validation_result';
    valid?: boolean;
    severity?: 'info' | 'warning' | 'error' | 'critical';
    content?: string;
    issues?: Array<{ severity?: string; message?: string; target?: string } | string>;
}

export interface VersionConflictChunk {
    type: 'version_conflict';
    content?: string;
    diagramId?: string;
    expectedVersion?: number;
    currentVersion?: number;
    currentContentHash?: string;
}

export type StreamChunk = DrawioPreviewChunk | DrawioNodeChunk | DrawioEdgeChunk | DrawioDoneChunk | DrawioLegacyChunk | StatusChunk | ErrorChunk | UserChunk | DoneChunk | TokenChunk | MetaChunk | ReviewResultChunk | ValidationResultChunk | VersionConflictChunk;

export interface StreamEvent {
    phase: 'analyzing' | 'drawing' | 'reviewing' | 'revising' | 'thinking' | 'error' | 'done' | 'generating';
    chunk: StreamChunk;
}

export type StreamEventCallback = (event: StreamEvent) => void;

export const agentApi = {
    /**
     * Query AI Agent Config List
     * Path: /api/v1/query_ai_agent_config_list
     */
    queryAiAgentConfigList: async (): Promise<Response<AiAgentConfigResponseDTO[]>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/query_ai_agent_config_list`, {
            method: 'GET',
            headers: {
                'Content-Type': 'application/json',
            },
        });
        return handleResponse<AiAgentConfigResponseDTO[]>(response);
    },

    /**
     * Selectable skill catalog for the "/" picker (built-in + public + the user's private).
     * Path: /api/v1/skills/catalog
     */
    getSkillCatalog: async (userId: string): Promise<Response<Array<{ name: string; description?: string; category?: string }>>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/skills/catalog`, {
            method: 'GET',
            headers: workspaceHeaders(userId),
            credentials: 'include',
        });
        return handleResponse<Array<{ name: string; description?: string; category?: string }>>(response);
    },

    currentAccount: async (userId: string): Promise<Response<CurrentAccountResponseDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/account/me`, {
            method: 'GET',
            headers: workspaceHeaders(userId),
            credentials: 'include',
        });
        return handleResponse<CurrentAccountResponseDTO>(response);
    },

    // ── Admin telemetry / trace visualization (session cookie must belong to an admin) ──
    adminUsage: async (): Promise<Response<AdminUsageDashboardDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/usage`, {
            method: 'GET',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'include',
        });
        return handleResponse<AdminUsageDashboardDTO>(response);
    },

    adminListRuns: async (params?: {
        status?: string;
        userId?: string;
        agentId?: string;
        limit?: number;
        offset?: number;
    }): Promise<Response<AdminRunMetadataDTO[]>> => {
        const query = new URLSearchParams();
        if (params?.status) query.set('status', params.status);
        if (params?.userId) query.set('userId', params.userId);
        if (params?.agentId) query.set('agentId', params.agentId);
        if (params?.limit != null) query.set('limit', String(params.limit));
        if (params?.offset != null) query.set('offset', String(params.offset));
        const qs = query.toString();
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/runs${qs ? `?${qs}` : ''}`, {
            method: 'GET',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'include',
        });
        return handleResponse<AdminRunMetadataDTO[]>(response);
    },

    adminRunDetail: async (runId: string): Promise<Response<AdminRunDetailDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/runs/${encodeURIComponent(runId)}`, {
            method: 'GET',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'include',
        });
        return handleResponse<AdminRunDetailDTO>(response);
    },

    adminDiagramTrace: async (runId: string): Promise<Response<AdminDiagramTraceDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/runs/${encodeURIComponent(runId)}/diagram-trace`, {
            method: 'GET',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'include',
        });
        return handleResponse<AdminDiagramTraceDTO>(response);
    },

    adminRunDiagram: async (runId: string): Promise<Response<DiagramCanvasStateResponseDTO | null>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/runs/${encodeURIComponent(runId)}/diagram`, {
            method: 'GET',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'include',
        });
        return handleResponse<DiagramCanvasStateResponseDTO | null>(response);
    },

    adminCreateEvalCandidate: async (runId: string): Promise<Response<EvalCaseCandidateDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/runs/${encodeURIComponent(runId)}/eval-candidates`, {
            method: 'POST',
            headers: await csrfHeaders({ 'Content-Type': 'application/json' }),
            credentials: 'include',
        });
        return handleResponse<EvalCaseCandidateDTO>(response);
    },

    adminListEvalCandidates: async (params?: {
        status?: string;
        risk?: string;
        limit?: number;
        offset?: number;
    }): Promise<Response<EvalCaseCandidateDTO[]>> => {
        const query = new URLSearchParams();
        if (params?.status) query.set('status', params.status);
        if (params?.risk) query.set('risk', params.risk);
        if (params?.limit != null) query.set('limit', String(params.limit));
        if (params?.offset != null) query.set('offset', String(params.offset));
        const qs = query.toString();
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-candidates${qs ? `?${qs}` : ''}`, {
            method: 'GET',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'include',
        });
        return handleResponse<EvalCaseCandidateDTO[]>(response);
    },

    adminStartSemanticMinerRun: async (payload: {
        samplingPolicy: 'TARGETED' | 'RANDOM' | 'MIXED';
        limit: number;
        purposeConfirmed: boolean;
    }): Promise<Response<SemanticMinerRunDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/semantic-miner-runs`, {
            method: 'POST',
            headers: await csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify(payload),
            credentials: 'include',
        });
        return handleResponse<SemanticMinerRunDTO>(response);
    },

    adminListSemanticMinerRuns: async (limit = 10): Promise<Response<SemanticMinerRunDTO[]>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/semantic-miner-runs?limit=${limit}`, {
            method: 'GET', headers: { 'Content-Type': 'application/json' }, credentials: 'include',
        });
        return handleResponse<SemanticMinerRunDTO[]>(response);
    },

    adminAnalyzeVisualRun: async (
        sourceRunId: string,
        purposeConfirmed: boolean,
    ): Promise<Response<VisualMinerResultDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/visual-miner/analyze-run`, {
            method: 'POST', headers: await csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({ sourceRunId, purposeConfirmed }), credentials: 'include',
        });
        return handleResponse<VisualMinerResultDTO>(response);
    },

    adminTransitionEvalCandidate: async (
        candidateId: string,
        payload: { status: string; reason?: string },
    ): Promise<Response<EvalCaseCandidateDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-candidates/${encodeURIComponent(candidateId)}/status`, {
            method: 'POST',
            headers: await csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify(payload),
            credentials: 'include',
        });
        return handleResponse<EvalCaseCandidateDTO>(response);
    },

    adminPrepareEvalDraft: async (
        candidateId: string,
        purposeConfirmed: boolean,
    ): Promise<Response<EvalDraftPreparationDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-candidates/${encodeURIComponent(candidateId)}/draft`, {
            method: 'POST',
            headers: await csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({ purposeConfirmed }),
            credentials: 'include',
        });
        return handleResponse<EvalDraftPreparationDTO>(response);
    },

    adminListEvalCaseWorkingCopies: async (status?: string): Promise<Response<EvalCaseWorkingCopyDTO[]>> => {
        const query = status ? `?status=${encodeURIComponent(status)}` : '';
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-case-working-copies${query}`, {
            method: 'GET', headers: { 'Content-Type': 'application/json' }, credentials: 'include',
        });
        return handleResponse<EvalCaseWorkingCopyDTO[]>(response);
    },

    adminGetEvalCaseWorkingCopy: async (id: string): Promise<Response<EvalCaseWorkingCopyDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-case-working-copies/${encodeURIComponent(id)}`, {
            method: 'GET', headers: { 'Content-Type': 'application/json' }, credentials: 'include',
        });
        return handleResponse<EvalCaseWorkingCopyDTO>(response);
    },

    adminImportEvalCaseYaml: async (yaml: string): Promise<Response<EvalCaseWorkingCopyDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-case-working-copies`, {
            method: 'POST', headers: await csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({ sourceType: 'IMPORTED', yaml }), credentials: 'include',
        });
        return handleResponse<EvalCaseWorkingCopyDTO>(response);
    },

    adminCreateEvalCaseWorkingCopyFromDraft: async (
        candidateId: string,
        caseId: string,
        caseVersion: string,
    ): Promise<Response<EvalCaseWorkingCopyDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-case-working-copies`, {
            method: 'POST', headers: await csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({ sourceType: 'TRACE_DRAFT', candidateId, caseId, caseVersion }),
            credentials: 'include',
        });
        return handleResponse<EvalCaseWorkingCopyDTO>(response);
    },

    adminUpdateEvalCaseWorkingCopy: async (id: string, revision: number, definition: Record<string, unknown>): Promise<Response<EvalCaseWorkingCopyDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-case-working-copies/${encodeURIComponent(id)}`, {
            method: 'PUT', headers: await csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({ expectedRevision: revision, definition }), credentials: 'include',
        });
        return handleResponse<EvalCaseWorkingCopyDTO>(response);
    },

    adminValidateEvalCase: async (id: string): Promise<Response<EvalCaseValidationResultDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-case-working-copies/${encodeURIComponent(id)}/validate`, {
            method: 'POST', headers: await csrfHeaders({ 'Content-Type': 'application/json' }), credentials: 'include',
        });
        return handleResponse<EvalCaseValidationResultDTO>(response);
    },

    adminDryRunEvalCase: async (id: string): Promise<Response<EvalCaseDryRunResultDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-case-working-copies/${encodeURIComponent(id)}/dry-runs`, {
            method: 'POST', headers: await csrfHeaders({ 'Content-Type': 'application/json' }), credentials: 'include',
        });
        return handleResponse<EvalCaseDryRunResultDTO>(response);
    },

    adminSubmitEvalCaseReview: async (id: string): Promise<Response<EvalCaseWorkingCopyDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-case-working-copies/${encodeURIComponent(id)}/submit-review`, {
            method: 'POST', headers: await csrfHeaders({ 'Content-Type': 'application/json' }), credentials: 'include',
        });
        return handleResponse<EvalCaseWorkingCopyDTO>(response);
    },

    adminDecideEvalCase: async (id: string, decision: 'approve' | 'reject', reason: string): Promise<Response<EvalCaseWorkingCopyDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-case-working-copies/${encodeURIComponent(id)}/${decision}`, {
            method: 'POST', headers: await csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({ reason }), credentials: 'include',
        });
        return handleResponse<EvalCaseWorkingCopyDTO>(response);
    },

    adminPublishEvalCase: async (id: string): Promise<Response<PublishedEvalCaseDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-case-working-copies/${encodeURIComponent(id)}/publish`, {
            method: 'POST', headers: await csrfHeaders({ 'Content-Type': 'application/json' }), credentials: 'include',
        });
        return handleResponse<PublishedEvalCaseDTO>(response);
    },

    adminListPublishedEvalCases: async (): Promise<Response<PublishedEvalCaseDTO[]>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-cases`, {
            method: 'GET', headers: { 'Content-Type': 'application/json' }, credentials: 'include',
        });
        return handleResponse<PublishedEvalCaseDTO[]>(response);
    },

    adminClonePublishedEvalCase: async (caseId: string, sourceVersion: string, newCaseId: string, newCaseVersion: string): Promise<Response<EvalCaseWorkingCopyDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-cases/${encodeURIComponent(caseId)}/clone`, {
            method: 'POST', headers: await csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({ sourceVersion, newCaseId, newCaseVersion }), credentials: 'include',
        });
        return handleResponse<EvalCaseWorkingCopyDTO>(response);
    },

    adminRetirePublishedEvalCase: async (caseId: string, caseVersion: string): Promise<Response<PublishedEvalCaseDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-cases/${encodeURIComponent(caseId)}/retire`, {
            method: 'POST', headers: await csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({ caseVersion }), credentials: 'include',
        });
        return handleResponse<PublishedEvalCaseDTO>(response);
    },

    adminListEvalDatasets: async (): Promise<Response<EvalDatasetDTO[]>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-datasets`, {
            method: 'GET', headers: { 'Content-Type': 'application/json' }, credentials: 'include',
        });
        return handleResponse<EvalDatasetDTO[]>(response);
    },

    adminCreateEvalDataset: async (name: string, datasetClass: 'DEV' | 'CORE'): Promise<Response<EvalDatasetDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-datasets`, {
            method: 'POST', headers: await csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({ name, datasetClass }), credentials: 'include',
        });
        return handleResponse<EvalDatasetDTO>(response);
    },

    adminListEvalDatasetVersions: async (id: string): Promise<Response<EvalDatasetVersionDTO[]>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-datasets/${encodeURIComponent(id)}/versions`, {
            method: 'GET', headers: { 'Content-Type': 'application/json' }, credentials: 'include',
        });
        return handleResponse<EvalDatasetVersionDTO[]>(response);
    },

    adminCreateEvalDatasetVersion: async (id: string, version: string, members: EvalDatasetMemberDTO[]): Promise<Response<EvalDatasetVersionDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-datasets/${encodeURIComponent(id)}/versions`, {
            method: 'POST', headers: await csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({ version, members }), credentials: 'include',
        });
        return handleResponse<EvalDatasetVersionDTO>(response);
    },

    adminCloneEvalDatasetVersion: async (sourceDatasetId: string, sourceVersion: string, targetDatasetId: string, targetVersion: string): Promise<Response<EvalDatasetVersionDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-datasets/${encodeURIComponent(sourceDatasetId)}/clone`, {
            method: 'POST', headers: await csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({ sourceVersion, targetDatasetId, targetVersion }), credentials: 'include',
        });
        return handleResponse<EvalDatasetVersionDTO>(response);
    },

    adminReplaceEvalDatasetMembers: async (id: string, version: string, expectedRevision: number, members: EvalDatasetMemberDTO[]): Promise<Response<EvalDatasetVersionDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-datasets/${encodeURIComponent(id)}/members`, {
            method: 'PUT', headers: await csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({ version, expectedRevision, members }), credentials: 'include',
        });
        return handleResponse<EvalDatasetVersionDTO>(response);
    },

    adminEvalDatasetAction: async (id: string, version: string, action: 'validate' | 'publish'): Promise<Response<EvalDatasetVersionDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-datasets/${encodeURIComponent(id)}/${action}`, {
            method: 'POST', headers: await csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({ version }), credentials: 'include',
        });
        return handleResponse<EvalDatasetVersionDTO>(response);
    },

    adminEvalDatasetCoverage: async (id: string, version: string): Promise<Response<EvalDatasetCoverageDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-datasets/${encodeURIComponent(id)}/coverage?version=${encodeURIComponent(version)}`, {
            method: 'GET', headers: { 'Content-Type': 'application/json' }, credentials: 'include',
        });
        return handleResponse<EvalDatasetCoverageDTO>(response);
    },

    adminListEvaluationProfiles: async (): Promise<Response<EvaluationProfileDTO[]>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/evaluation-profiles`, { method: 'GET', headers: { 'Content-Type': 'application/json' }, credentials: 'include' });
        return handleResponse<EvaluationProfileDTO[]>(response);
    },

    adminStartEvalRun: async (payload: { mode: 'MODE_B' | 'MODE_C' | 'RELEASE'; idempotencyKey: string; datasetId: string; datasetVersion: string; profileId?: string; profileVersion?: string; repetitions: number; gitSha: string; baselineRef?: string; candidateRef?: string; executionProfileHash?: string; maxEstimatedCost?: number; minimumCases?: number; maximumErrorRate?: number; minimumPairedCases?: number; regressionThreshold?: number }): Promise<Response<{ id: string }>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-runs`, {
            method: 'POST', headers: await csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify(payload), credentials: 'include',
        });
        return handleResponse<{ id: string }>(response);
    },

    adminListEvalRuns: async (): Promise<Response<EvalRunSummaryDTO[]>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-runs`, { method: 'GET', headers: { 'Content-Type': 'application/json' }, credentials: 'include' });
        return handleResponse<EvalRunSummaryDTO[]>(response);
    },

    adminGetEvalRun: async (id: string): Promise<Response<EvalRunSummaryDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-runs/${encodeURIComponent(id)}`, { method: 'GET', headers: { 'Content-Type': 'application/json' }, credentials: 'include' });
        return handleResponse<EvalRunSummaryDTO>(response);
    },

    adminListEvalEpisodes: async (runId: string, filters?: { status?: string; route?: string; risk?: string; language?: string; agent?: string }): Promise<Response<EvalEpisodeViewDTO[]>> => {
        const query = new URLSearchParams(); Object.entries(filters || {}).forEach(([key, value]) => value && query.set(key, value));
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-runs/${encodeURIComponent(runId)}/episodes?${query}`, { method: 'GET', headers: { 'Content-Type': 'application/json' }, credentials: 'include' });
        return handleResponse<EvalEpisodeViewDTO[]>(response);
    },

    adminGetEvalEpisode: async (runId: string, episodeId: string): Promise<Response<EvalEpisodeDetailDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-runs/${encodeURIComponent(runId)}/episodes/${encodeURIComponent(episodeId)}`, { method: 'GET', headers: { 'Content-Type': 'application/json' }, credentials: 'include' });
        return handleResponse<EvalEpisodeDetailDTO>(response);
    },

    adminGetEvalEpisodeArtifact: async (runId: string, episodeId: string): Promise<Response<EvalEpisodeArtifactDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-runs/${encodeURIComponent(runId)}/episodes/${encodeURIComponent(episodeId)}/artifact`, { method: 'GET', headers: { 'Content-Type': 'application/json' }, credentials: 'include' });
        return handleResponse<EvalEpisodeArtifactDTO>(response);
    },

    adminEvalRunAction: async (id: string, action: 'cancel' | 'retry-errors'): Promise<Response<unknown>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-runs/${encodeURIComponent(id)}/${action}`, { method: 'POST', headers: await csrfHeaders({ 'Content-Type': 'application/json' }), credentials: 'include' });
        return handleResponse<unknown>(response);
    },

    adminGetEvalRunInsights: async (id: string): Promise<Response<EvalLiveRunReportDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-runs/${encodeURIComponent(id)}/insights`, { method: 'GET', headers: { 'Content-Type': 'application/json' }, credentials: 'include' });
        return handleResponse<EvalLiveRunReportDTO>(response);
    },

    adminEvaluateEvalGate: async (id: string): Promise<Response<EvalGateDecisionDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-runs/${encodeURIComponent(id)}/gate/evaluate`, { method: 'POST', headers: await csrfHeaders({ 'Content-Type': 'application/json' }), credentials: 'include' });
        return handleResponse<EvalGateDecisionDTO>(response);
    },

    adminOverrideEvalGate: async (id: string, reason: string): Promise<Response<EvalGateDecisionDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-runs/${encodeURIComponent(id)}/gate/override`, { method: 'POST', headers: await csrfHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify({ reason }), credentials: 'include' });
        return handleResponse<EvalGateDecisionDTO>(response);
    },

    adminListEvalCanaryAssessments: async (evalRunId: string): Promise<Response<EvalCanaryAssessmentDTO[]>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-operations/canary-assessments?evalRunId=${encodeURIComponent(evalRunId)}`, {
            method: 'GET', headers: { 'Content-Type': 'application/json' }, credentials: 'include',
        });
        return handleResponse<EvalCanaryAssessmentDTO[]>(response);
    },

    adminListEvalCaseHealth: async (status?: string): Promise<Response<EvalCaseHealthDTO[]>> => {
        const query = status ? `?status=${encodeURIComponent(status)}` : '';
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-operations/case-health${query}`, {
            method: 'GET', headers: { 'Content-Type': 'application/json' }, credentials: 'include',
        });
        return handleResponse<EvalCaseHealthDTO[]>(response);
    },

    adminRefreshEvalCaseHealth: async (): Promise<Response<EvalCaseHealthDTO[]>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/eval-operations/case-health/refresh`, {
            method: 'POST', headers: await csrfHeaders({ 'Content-Type': 'application/json' }), credentials: 'include',
        });
        return handleResponse<EvalCaseHealthDTO[]>(response);
    },

    adminRunCaptures: async (runId: string): Promise<Response<AdminDebugTraceCaptureDTO[]>> => {
        const response = await fetch(
            `${API_CONFIG.BASE_URL}/admin/debug-traces/runs/${encodeURIComponent(runId)}/captures`,
            {
                method: 'GET',
                headers: { 'Content-Type': 'application/json' },
                credentials: 'include',
            },
        );
        return handleResponse<AdminDebugTraceCaptureDTO[]>(response);
    },

    adminSpanPayloads: async (
        runId: string,
        spanId: string,
    ): Promise<Response<AdminDebugTraceCaptureDTO[]>> => {
        const response = await fetch(
            `${API_CONFIG.BASE_URL}/admin/debug-traces/runs/${encodeURIComponent(runId)}/spans/${encodeURIComponent(spanId)}/payloads`,
            {
                method: 'GET',
                headers: { 'Content-Type': 'application/json' },
                credentials: 'include',
            },
        );
        return handleResponse<AdminDebugTraceCaptureDTO[]>(response);
    },

    adminEnableCapture: async (
        payload: AdminDebugTraceControlRequestDTO,
    ): Promise<Response<AdminDebugTraceControlDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/admin/debug-traces/controls`, {
            method: 'POST',
            headers: await csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify(payload),
            credentials: 'include',
        });
        return handleResponse<AdminDebugTraceControlDTO>(response);
    },

    listModelCredentials: async (): Promise<Response<ModelCredentialResponseDTO[]>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/model-credentials`, {
            method: 'GET',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'include',
        });
        return handleResponse<ModelCredentialResponseDTO[]>(response);
    },

    listProviderPresets: async (): Promise<Response<ProviderPresetDTO[]>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/model-credentials/providers`, {
            method: 'GET',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'include',
        });
        return handleResponse<ProviderPresetDTO[]>(response);
    },

    createModelCredential: async (
        payload: CreateModelCredentialRequestDTO,
    ): Promise<Response<ModelCredentialResponseDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/model-credentials`, {
            method: 'POST',
            headers: await csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify(payload),
            credentials: 'include',
        });
        return handleResponse<ModelCredentialResponseDTO>(response);
    },

    deleteModelCredential: async (credentialId: string): Promise<Response<null>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/model-credentials/${encodeURIComponent(credentialId)}`, {
            method: 'DELETE',
            headers: await csrfHeaders({ 'Content-Type': 'application/json' }),
            credentials: 'include',
        });
        return handleResponse<null>(response);
    },

    registerAccount: async (payload: RegisterAccountRequestDTO): Promise<Response<RegisterAccountResponseDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/auth/register`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload),
        });
        return handleResponse<RegisterAccountResponseDTO>(response);
    },

    verifyEmail: async (token: string): Promise<Response<VerifyEmailResponseDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/auth/verify-email?token=${encodeURIComponent(token)}`, {
            method: 'GET',
            headers: { 'Content-Type': 'application/json' },
        });
        return handleResponse<VerifyEmailResponseDTO>(response);
    },

    resendVerification: async (payload: ResendVerificationRequestDTO): Promise<Response<null>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/auth/resend-verification`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload),
        });
        return handleResponse<null>(response);
    },

    requestPasswordReset: async (payload: PasswordResetRequestDTO): Promise<Response<null>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/auth/password-reset/request`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload),
        });
        return handleResponse<null>(response);
    },

    confirmPasswordReset: async (
        payload: PasswordResetConfirmRequestDTO,
    ): Promise<Response<PasswordResetConfirmResponseDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/auth/password-reset/confirm`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload),
            credentials: 'include',
        });
        return handleResponse<PasswordResetConfirmResponseDTO>(response);
    },

    /**
     * Log in with email + password. On SUCCESS the response sets a session cookie; every
     * follow-up API call needs {@code credentials: 'include'} so the browser sends it back.
     */
    login: async (payload: LoginRequestDTO): Promise<Response<LoginResponseDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/auth/login`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload),
            credentials: 'include',
        });
        return handleResponse<LoginResponseDTO>(response);
    },

    logout: async (): Promise<Response<null>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/auth/logout`, {
            method: 'POST',
            headers: await csrfHeaders({ 'Content-Type': 'application/json' }),
            credentials: 'include',
        });
        return handleResponse<null>(response);
    },

    me: async (): Promise<Response<LoginResponseDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/auth/me`, {
            method: 'GET',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'include',
        });
        return handleResponse<LoginResponseDTO>(response);
    },

    /**
     * Create Session
     * Path: /api/v1/create_session
     */
    createSession: async (agentId: string, userId: string): Promise<Response<CreateSessionResponseDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/create_session`, {
            method: 'POST',
            headers: await csrfHeaders(workspaceHeaders(userId)),
            body: JSON.stringify({ agentId }),
            credentials: 'include',
        });
        return handleResponse<CreateSessionResponseDTO>(response);
    },

    listDiagrams: async (userId: string): Promise<Response<DiagramSummaryResponseDTO[]>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/diagrams`, {
            method: 'GET',
            headers: workspaceHeaders(userId),
            credentials: 'include',
        });
        return handleResponse<DiagramSummaryResponseDTO[]>(response);
    },

    importAnonymousWorkspace: async (
        payload: ImportAnonymousWorkspaceRequestDTO,
    ): Promise<Response<ImportAnonymousWorkspaceResponseDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/workspaces/anonymous/import`, {
            method: 'POST',
            headers: await csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify(payload),
            credentials: 'include',
        });
        return handleResponse<ImportAnonymousWorkspaceResponseDTO>(response);
    },

    getDiagram: async (userId: string, diagramId: string): Promise<Response<DiagramCanvasStateResponseDTO | null>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/diagrams/${encodeURIComponent(diagramId)}`, {
            method: 'GET',
            headers: workspaceHeaders(userId),
            credentials: 'include',
        });
        return handleResponse<DiagramCanvasStateResponseDTO | null>(response);
    },

    renameDiagram: async (userId: string, diagramId: string, title: string): Promise<Response<DiagramSummaryResponseDTO | null>> => {
        const body: UpdateDiagramTitleRequestDTO = { title };
        const response = await fetch(`${API_CONFIG.BASE_URL}/diagrams/${encodeURIComponent(diagramId)}/title`, {
            method: 'PATCH',
            headers: await csrfHeaders(workspaceHeaders(userId)),
            body: JSON.stringify(body),
            credentials: 'include',
        });
        return handleResponse<DiagramSummaryResponseDTO | null>(response);
    },

    updateDiagramThumbnail: async (
        userId: string,
        diagramId: string,
        thumbnailDataUrl: string
    ): Promise<Response<DiagramSummaryResponseDTO | null>> => {
        const body: UpdateDiagramThumbnailRequestDTO = { thumbnailDataUrl };
        const response = await fetch(`${API_CONFIG.BASE_URL}/diagrams/${encodeURIComponent(diagramId)}/thumbnail`, {
            method: 'PATCH',
            headers: await csrfHeaders(workspaceHeaders(userId)),
            body: JSON.stringify(body),
            credentials: 'include',
        });
        return handleResponse<DiagramSummaryResponseDTO | null>(response);
    },

    saveDiagramCanvasState: async (
        userId: string,
        diagramId: string,
        canvasXml: string,
        expectedVersion?: number,
        options?: { keepalive?: boolean }
    ): Promise<Response<DiagramCanvasStateResponseDTO | null>> => {
        const body: SaveDiagramCanvasStateRequestDTO = {
            canvasXml,
            ...(Number.isFinite(expectedVersion) && { expectedVersion }),
        };
        const response = await fetch(`${API_CONFIG.BASE_URL}/diagrams/${encodeURIComponent(diagramId)}/canvas`, {
            method: 'PATCH',
            headers: await csrfHeaders(workspaceHeaders(userId)),
            body: JSON.stringify(body),
            credentials: 'include',
            // keepalive lets the browser finish the save after the page is gone; note browsers
            // reject keepalive bodies over ~64KB, so callers must treat this as best effort.
            ...(options?.keepalive && { keepalive: true }),
        });
        return handleResponse<DiagramCanvasStateResponseDTO | null>(response);
    },

    deleteDiagram: async (userId: string, diagramId: string): Promise<Response<boolean>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/diagrams/${encodeURIComponent(diagramId)}`, {
            method: 'DELETE',
            headers: await csrfHeaders(workspaceHeaders(userId)),
            credentials: 'include',
        });
        return handleResponse<boolean>(response);
    },

    listDiagramMessages: async (userId: string, diagramId: string): Promise<Response<DiagramConversationMessageDTO[]>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/diagrams/${encodeURIComponent(diagramId)}/messages`, {
            method: 'GET',
            headers: workspaceHeaders(userId),
            credentials: 'include',
        });
        return handleResponse<DiagramConversationMessageDTO[]>(response);
    },

    saveDiagramMessages: async (
        userId: string,
        diagramId: string,
        sessionId: string | undefined,
        messages: DiagramConversationMessageDTO[]
    ): Promise<Response<boolean>> => {
        const body: SaveDiagramMessagesRequestDTO = { sessionId, messages };
        const response = await fetch(`${API_CONFIG.BASE_URL}/diagrams/${encodeURIComponent(diagramId)}/messages`, {
            method: 'POST',
            headers: await csrfHeaders(workspaceHeaders(userId)),
            body: JSON.stringify(body),
            credentials: 'include',
        });
        return handleResponse<boolean>(response);
    },

    /**
     * Chat (blocking)
     * Path: /api/v1/chat
     */
    chat: async (data: ChatRequestDTO): Promise<Response<ChatResponseDTO>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/chat`, {
            method: 'POST',
            headers: await csrfHeaders(workspaceHeaders(data.userId, data.requestId)),
            body: JSON.stringify(data),
            credentials: 'include',
        });
        return handleResponse<ChatResponseDTO>(response);
    },

    /**
     * Chat Stream (SSE-like streaming)
     * Path: /api/v1/chat_stream
     * Receives structured drawio events: nodes, edges, and final XML
     * Uses ReadableStream to process server-sent events line by line
     */
    chatStream: async (
        data: ChatRequestDTO,
        onEvent: StreamEventCallback,
        onError: (error: Error) => void,
        onComplete: () => void
    ): Promise<AbortController> => {
        const controller = new AbortController();

        try {
            const response = await fetch(`${API_CONFIG.BASE_URL}/chat_stream`, {
                method: 'POST',
                headers: await csrfHeaders(workspaceHeaders(data.userId, data.requestId)),
                body: JSON.stringify(data),
                signal: controller.signal,
                credentials: 'include',
            });

            if (!response.ok) {
                const errorText = await response.text();
                onError(new Error(`HTTP error! status: ${response.status}, message: ${errorText}`));
                return controller;
            }

            const reader = response.body!.getReader();
            const decoder = new TextDecoder();
            let buffer = '';

            const processStream = async () => {
                try {
                    while (true) {
                        const { done, value } = await reader.read();
                        if (done) break;

                        buffer += decoder.decode(value, { stream: true });

                        // Process complete lines
                        const lines = buffer.split('\n');
                        buffer = lines.pop() || ''; // Keep incomplete line in buffer

                        for (const line of lines) {
                            const trimmed = line.trim();
                            if (!trimmed) continue;

                            try {
                                const event: StreamEvent = JSON.parse(trimmed);
                                onEvent(event);
                            } catch (parseErr) {
                                // If JSON parse fails, treat as raw status text
                                console.warn('Failed to parse stream event:', trimmed, parseErr);
                            }
                        }
                    }

                    // Process any remaining buffer
                    if (buffer.trim()) {
                        try {
                            const event: StreamEvent = JSON.parse(buffer.trim());
                            onEvent(event);
                        } catch {}
                    }

                    // Only call onComplete if we didn't abort
                    if (!controller.signal.aborted) {
                        onComplete();
                    }
                } catch (err: unknown) {
                    if (err instanceof DOMException && err.name === 'AbortError') {
                        // User cancelled, no error, but call complete to cleanup UI state
                        onComplete();
                        return;
                    }
                    onError(err instanceof Error ? err : new Error(String(err)));
                }
            };

            processStream();
        } catch (err: unknown) {
            if (!(err instanceof DOMException && err.name === 'AbortError')) {
                onError(err instanceof Error ? err : new Error(String(err)));
            }
        }

        return controller;
    }
};
