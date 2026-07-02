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
    SaveDiagramMessagesRequestDTO,
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
} from '@/types/api';

export class ApiResponseError extends Error {
    readonly code: ApiErrorCode;
    readonly info: string;

    constructor(code: ApiErrorCode, info: string) {
        super(info);
        this.name = 'ApiResponseError';
        this.code = code;
        this.info = info;
    }
}

const handleResponse = async <T>(response: globalThis.Response): Promise<Response<T>> => {
    if (!response.ok) {
        const errorText = await response.text();
        throw new Error(`HTTP error! status: ${response.status}, message: ${errorText}`);
    }
    const data = await response.json();
    if (data.code !== "0000") {
        throw new ApiResponseError(data.code, data.info || 'Unknown API error');
    }
    return data;
};

const workspaceHeaders = (userId: string) => ({
    'Content-Type': 'application/json',
    'X-Workspace-Id': userId,
});

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
}

export type StreamChunk = DrawioPreviewChunk | DrawioNodeChunk | DrawioEdgeChunk | DrawioDoneChunk | DrawioLegacyChunk | StatusChunk | ErrorChunk | UserChunk | DoneChunk | TokenChunk | ReviewResultChunk | ValidationResultChunk | VersionConflictChunk;

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
            headers: { 'Content-Type': 'application/json' },
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
            headers: workspaceHeaders(userId),
            body: JSON.stringify({ agentId }),
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
            headers: workspaceHeaders(userId),
            body: JSON.stringify(body),
            credentials: 'include',
        });
        return handleResponse<DiagramSummaryResponseDTO | null>(response);
    },

    deleteDiagram: async (userId: string, diagramId: string): Promise<Response<boolean>> => {
        const response = await fetch(`${API_CONFIG.BASE_URL}/diagrams/${encodeURIComponent(diagramId)}`, {
            method: 'DELETE',
            headers: workspaceHeaders(userId),
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
            headers: workspaceHeaders(userId),
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
            headers: workspaceHeaders(data.userId),
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
                headers: workspaceHeaders(data.userId),
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
