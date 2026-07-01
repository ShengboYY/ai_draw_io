export interface Response<T> {
    code: string;
    info: string;
    data: T;
}

export interface AiAgentConfigResponseDTO {
    agentId: string;
    agentName: string;
    agentDesc: string;
}

export interface CreateSessionRequestDTO {
    agentId: string;
    userId: string;
}

export interface CreateSessionResponseDTO {
    sessionId: string;
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
