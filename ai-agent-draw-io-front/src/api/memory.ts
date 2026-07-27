import { createCsrfHeadersProvider, type CsrfHeadersProvider } from './csrf.ts';

export type MemoryCandidate = {
  candidateId: string;
  chartbookId: string;
  diagramId: string;
  sourceConversationId: string;
  sourceTurnId: string;
  decisionKey: string;
  applicabilityStage: string;
  scope: string;
  canonicalText: string;
  policyVersion: string;
  declarationDigest: string;
  status: string;
  version: number;
  expiresAt: string;
};

export type ConfirmedMemory = {
  memoryId: string;
  chartbookId: string;
  sourceConversationId: string;
  sourceTurnId: string;
  decisionKey: string;
  applicabilityStage: string;
  scope: string;
  canonicalText: string;
  status: 'ACTIVE' | 'DISABLED' | 'DELETED' | string;
  version: number;
};

type ApiEnvelope<T> = { code: string; info?: string; data?: T };
type FetchImplementation = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

export class MemoryApiError extends Error {
  readonly code: string;

  constructor(code: string, message: string) {
    super(message);
    this.name = 'MemoryApiError';
    this.code = code;
  }
}

type MemoryClientOptions = {
  baseUrl: string;
  fetch?: FetchImplementation;
  csrfHeaders?: CsrfHeadersProvider;
};

export const createMemoryClient = (options: MemoryClientOptions) => {
  const fetchImplementation = options.fetch || globalThis.fetch.bind(globalThis);
  const csrfHeaders = options.csrfHeaders || createCsrfHeadersProvider(options.baseUrl, fetchImplementation);
  const { baseUrl } = options;
  const path = (chartbookId: string, suffix = '') =>
    `/chartbooks/${encodeURIComponent(chartbookId)}/memory${suffix}`;
  const request = async <T>(pathValue: string, init: RequestInit = {}, write = false): Promise<T> => {
    const response = await fetchImplementation(`${baseUrl}${pathValue}`, {
      ...init,
      headers: { ...(init.headers || {}), ...(write ? await csrfHeaders() : {}) },
      credentials: 'include',
    });
    const envelope = await response.json() as ApiEnvelope<T>;
    if (!response.ok || envelope.code !== '0000') {
      throw new MemoryApiError(envelope.code || 'HTTP_ERROR', envelope.info || 'Memory request failed');
    }
    return envelope.data as T;
  };
  const json = (value: unknown): RequestInit => ({
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(value),
  });

  return {
    pending: (chartbookId: string) => request<MemoryCandidate[]>(path(chartbookId, '/candidates')),
    confirm: (chartbookId: string, candidate: MemoryCandidate) => request<ConfirmedMemory>(
      path(chartbookId, `/candidates/${encodeURIComponent(candidate.candidateId)}/confirm`),
      json({
        sourceConversationId: candidate.sourceConversationId,
        sourceTurnId: candidate.sourceTurnId,
        declarationDigest: candidate.declarationDigest,
      }), true),
    revoke: (chartbookId: string, candidate: MemoryCandidate) => request<void>(
      path(chartbookId, `/candidates/${encodeURIComponent(candidate.candidateId)}/revoke`),
      json({
        sourceConversationId: candidate.sourceConversationId,
        sourceTurnId: candidate.sourceTurnId,
        declarationDigest: candidate.declarationDigest,
      }), true),
    list: (chartbookId: string, includeDisabled = true) => request<ConfirmedMemory[]>(
      `${path(chartbookId)}?includeDisabled=${includeDisabled ? 'true' : 'false'}`),
    edit: (chartbookId: string, memory: ConfirmedMemory, canonicalText: string) => request<ConfirmedMemory>(
      path(chartbookId, `/${encodeURIComponent(memory.memoryId)}`), {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json', 'If-Match': String(memory.version) },
        body: JSON.stringify({ canonicalText }),
      }, true),
    disable: (chartbookId: string, memory: ConfirmedMemory) => request<ConfirmedMemory>(
      path(chartbookId, `/${encodeURIComponent(memory.memoryId)}/disable`), {
        method: 'POST',
        headers: { 'If-Match': String(memory.version) },
      }, true),
    remove: (chartbookId: string, memory: ConfirmedMemory) => request<void>(
      path(chartbookId, `/${encodeURIComponent(memory.memoryId)}`), {
        method: 'DELETE',
        headers: { 'If-Match': String(memory.version) },
      }, true),
  };
};
