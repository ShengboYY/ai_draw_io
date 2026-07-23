import type {
  BrowserPostPolicy,
  InitiateMaterialUploadRequest,
  MaterialCatalogPage,
  MaterialUploadStatus,
} from '../features/materials/material-types';
import { createCsrfHeadersProvider, type CsrfHeadersProvider } from './csrf.ts';

type ApiEnvelope<T> = { code: string; info?: string; data?: T };
type FetchImplementation = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

export class MaterialApiError extends Error {
  readonly code: string;

  constructor(code: string, message: string) {
    super(message);
    this.name = 'MaterialApiError';
    this.code = code;
  }
}

type MaterialClientOptions = {
  baseUrl: string;
  fetch?: FetchImplementation;
  csrfHeaders?: CsrfHeadersProvider;
  sleep?: (milliseconds: number) => Promise<void>;
};

const terminalUploadStates = new Set(['READY', 'PARTIAL_READY', 'FAILED', 'REJECTED', 'CANCELLED']);

export const createMaterialClient = (options: MaterialClientOptions) => {
  const fetchImplementation = options.fetch || globalThis.fetch.bind(globalThis);
  const csrfHeaders = options.csrfHeaders || createCsrfHeadersProvider(options.baseUrl, fetchImplementation);
  const sleep = options.sleep || (milliseconds => new Promise<void>(resolve => setTimeout(resolve, milliseconds)));
  const { baseUrl } = options;
  const request = async <T>(path: string, init: RequestInit = {}, write = false): Promise<T> => {
    const headers = {
      ...(init.headers || {}),
      ...(write ? await csrfHeaders() : {}),
    };
    const response = await fetchImplementation(`${baseUrl}${path}`, {
      ...init,
      headers,
      credentials: 'include',
    });
    if (!response.ok) throw new MaterialApiError('HTTP_ERROR', `Material request failed with ${response.status}`);
    const envelope = await response.json() as ApiEnvelope<T>;
    if (envelope.code !== '0000' || envelope.data === undefined) {
      throw new MaterialApiError(envelope.code || 'UNKNOWN', envelope.info || 'Material request failed');
    }
    return envelope.data;
  };

  return {
    initiate: (body: InitiateMaterialUploadRequest, idempotencyKey: string) => request<MaterialUploadStatus>(
      '/material-uploads', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
        body: JSON.stringify(body),
      }, true),

    uploadBytes: async (policy: BrowserPostPolicy | undefined, file: Blob) => {
      if (!policy) throw new MaterialApiError('UPLOAD_POLICY_MISSING', 'Upload policy is unavailable');
      const form = new FormData();
      Object.entries(policy.fields).forEach(([key, value]) => form.append(key, value));
      form.append('file', file);
      const response = await fetchImplementation(policy.url, { method: 'POST', body: form });
      if (!response.ok) throw new MaterialApiError('UPLOAD_BYTES_FAILED', 'File upload failed');
    },

    complete: (uploadId: string) => request<MaterialUploadStatus>(
      `/material-uploads/${encodeURIComponent(uploadId)}/complete`, { method: 'POST' }, true),

    status: (uploadId: string) => request<MaterialUploadStatus>(
      `/material-uploads/${encodeURIComponent(uploadId)}`),

    pollStatus: async (uploadId: string, onStatus?: (status: MaterialUploadStatus) => void) => {
      let delay = 250;
      while (true) {
        const status = await request<MaterialUploadStatus>(`/material-uploads/${encodeURIComponent(uploadId)}`);
        onStatus?.(status);
        if (terminalUploadStates.has(status.state.trim().toUpperCase())) return status;
        await sleep(delay);
        delay = Math.min(delay * 2, 4_000);
      }
    },

    list: (query?: { query?: string; lifecycleState?: string; limit?: number; offset?: number }) => {
      const parameters = new URLSearchParams();
      if (query?.query) parameters.set('query', query.query);
      if (query?.lifecycleState) parameters.set('lifecycleState', query.lifecycleState);
      if (query?.limit !== undefined) parameters.set('limit', String(query.limit));
      if (query?.offset !== undefined) parameters.set('offset', String(query.offset));
      const suffix = parameters.size ? `?${parameters}` : '';
      return request<MaterialCatalogPage>(`/materials${suffix}`);
    },
  };
};
