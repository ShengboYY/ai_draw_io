import { createCsrfHeadersProvider, type CsrfHeadersProvider } from './csrf.ts';

export type AutoMemory = {
  memoryId: string;
  scopeType: 'USER' | 'CHARTBOOK';
  scopeKey: string;
  memoryType: 'PREFERENCE' | 'FEEDBACK' | 'PROJECT' | 'REFERENCE';
  semanticKey: string;
  title: string;
  canonicalText: string;
  status: 'OBSERVED' | 'ACTIVE' | 'DISABLED';
  confidence: number;
  evidenceCount: number;
  explicit: boolean;
  version: number;
  updatedAt: string;
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
  const scopePath = (scopeType: AutoMemory['scopeType'], chartbookId?: string) => {
    if (scopeType === 'USER') return '/memory';
    if (!chartbookId) throw new Error('chartbookId is required for Chartbook Memory');
    return `/chartbooks/${encodeURIComponent(chartbookId)}/memory`;
  };
  const itemPath = (memory: AutoMemory, chartbookId?: string, suffix = '') =>
    `${scopePath(memory.scopeType, chartbookId)}/${encodeURIComponent(memory.memoryId)}${suffix}`;
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
  const mutation = (method: 'PATCH' | 'POST' | 'DELETE', memory: AutoMemory, body?: unknown): RequestInit => ({
    method,
    headers: {
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      'If-Match': String(memory.version),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });

  return {
    listUser: () => request<AutoMemory[]>(
      `${scopePath('USER')}?includeObserved=true&includeDisabled=true`),
    listChartbook: (chartbookId: string) => request<AutoMemory[]>(
      `${scopePath('CHARTBOOK', chartbookId)}?includeObserved=true&includeDisabled=true`),
    edit: (memory: AutoMemory, canonicalText: string, chartbookId?: string) => request<AutoMemory>(
      itemPath(memory, chartbookId),
      mutation('PATCH', memory, { canonicalText }),
      true),
    disable: (memory: AutoMemory, chartbookId?: string) => request<AutoMemory>(
      itemPath(memory, chartbookId, '/disable'),
      mutation('POST', memory),
      true),
    activate: (memory: AutoMemory, chartbookId?: string) => request<AutoMemory>(
      itemPath(memory, chartbookId, '/activate'),
      mutation('POST', memory),
      true),
    remove: (memory: AutoMemory, chartbookId?: string) => request<void>(
      itemPath(memory, chartbookId),
      mutation('DELETE', memory),
      true),
  };
};
