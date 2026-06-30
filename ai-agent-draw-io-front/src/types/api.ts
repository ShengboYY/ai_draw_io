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
