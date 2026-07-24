import type { Chartbook, MaterialCatalogDetails } from '../features/materials/material-types';
import { createCsrfHeadersProvider, type CsrfHeadersProvider } from './csrf.ts';

type ApiEnvelope<T> = { code: string; info?: string; data?: T };
type FetchImplementation = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

export class ChartbookApiError extends Error {
  readonly code: string;

  constructor(code: string, message: string) {
    super(message);
    this.name = 'ChartbookApiError';
    this.code = code;
  }
}

type ChartbookClientOptions = {
  baseUrl: string;
  fetch?: FetchImplementation;
  csrfHeaders?: CsrfHeadersProvider;
};

const request = async <T>(
  baseUrl: string,
  fetchImplementation: FetchImplementation,
  csrfHeaders: () => Promise<Record<string, string>>,
  path: string,
  init: RequestInit = {},
  write = false,
  allowEmptyData = false,
): Promise<T> => {
  const response = await fetchImplementation(`${baseUrl}${path}`, {
    ...init,
    headers: { ...(init.headers || {}), ...(write ? await csrfHeaders() : {}) },
    credentials: 'include',
  });
  if (!response.ok) throw new ChartbookApiError('HTTP_ERROR', `Chartbook request failed with ${response.status}`);
  const envelope = await response.json() as ApiEnvelope<T>;
  if (envelope.code !== '0000' || (envelope.data === undefined && !allowEmptyData)) {
    throw new ChartbookApiError(envelope.code || 'UNKNOWN', envelope.info || 'Chartbook request failed');
  }
  return envelope.data as T;
};

export const createChartbookClient = (options: ChartbookClientOptions) => {
  const fetchImplementation = options.fetch || globalThis.fetch.bind(globalThis);
  const csrfHeaders = options.csrfHeaders || createCsrfHeadersProvider(options.baseUrl, fetchImplementation);
  const { baseUrl } = options;
  /** Keep JSON header construction coupled to its serialized request body. */
  const body = (value: unknown): [Record<string, string>, string] => [
    { 'Content-Type': 'application/json' },
    JSON.stringify(value),
  ];
  return {
    create: (name: string, idempotencyKey: string) => {
      const [headers, payload] = body({ name });
      return request<Chartbook>(baseUrl, fetchImplementation, csrfHeaders, '/chartbooks', {
        method: 'POST', headers: { ...headers, 'Idempotency-Key': idempotencyKey }, body: payload,
      }, true);
    },
    list: () => request<Chartbook[]>(baseUrl, fetchImplementation, csrfHeaders, '/chartbooks', { method: 'GET' }),
    forDiagram: (diagramId: string) => request<Chartbook | null>(
      baseUrl, fetchImplementation, csrfHeaders,
      `/diagrams/${encodeURIComponent(diagramId)}/chartbook`, { method: 'GET' }),
    details: (chartbookId: string) => request<Chartbook>(baseUrl, fetchImplementation, csrfHeaders,
      `/chartbooks/${encodeURIComponent(chartbookId)}`, { method: 'GET' }),
    rename: (chartbookId: string, name: string) => {
      const [headers, payload] = body({ name });
      return request<Chartbook>(baseUrl, fetchImplementation, csrfHeaders,
        `/chartbooks/${encodeURIComponent(chartbookId)}`, { method: 'PATCH', headers, body: payload }, true);
    },
    archive: (chartbookId: string) => request<void>(baseUrl, fetchImplementation, csrfHeaders,
      `/chartbooks/${encodeURIComponent(chartbookId)}`, { method: 'DELETE' }, true, true),
    addFile: (chartbookId: string, materialId: string, idempotencyKey: string) =>
      request<MaterialCatalogDetails>(baseUrl, fetchImplementation, csrfHeaders,
        `/chartbooks/${encodeURIComponent(chartbookId)}/files/${encodeURIComponent(materialId)}`, {
          method: 'POST',
          headers: { 'Idempotency-Key': idempotencyKey },
        }, true),
    removeFile: (chartbookId: string, materialId: string, idempotencyKey: string) =>
      request<MaterialCatalogDetails>(baseUrl, fetchImplementation, csrfHeaders,
        `/chartbooks/${encodeURIComponent(chartbookId)}/files/${encodeURIComponent(materialId)}`, {
          method: 'DELETE',
          headers: { 'Idempotency-Key': idempotencyKey },
        }, true),
    // Retained for one compatibility window while existing Library pages use the legacy contract.
    addMaterial: (chartbookId: string, materialId: string) => {
      const [headers, payload] = body({ materialId });
      return request<Chartbook>(baseUrl, fetchImplementation, csrfHeaders,
        `/chartbooks/${encodeURIComponent(chartbookId)}/materials`, { method: 'POST', headers, body: payload }, true);
    },
    removeMaterial: (chartbookId: string, materialId: string) => request<Chartbook>(
      baseUrl, fetchImplementation, csrfHeaders,
      `/chartbooks/${encodeURIComponent(chartbookId)}/materials/${encodeURIComponent(materialId)}`, { method: 'DELETE' }, true),
    assignDiagram: (diagramId: string, chartbookId: string) => {
      const [headers, payload] = body({ chartbookId });
      return request<Chartbook>(baseUrl, fetchImplementation, csrfHeaders,
        `/diagrams/${encodeURIComponent(diagramId)}/chartbook`, { method: 'PUT', headers, body: payload }, true);
    },
    removeDiagram: (diagramId: string) => request<void>(baseUrl, fetchImplementation, csrfHeaders,
      `/diagrams/${encodeURIComponent(diagramId)}/chartbook`, { method: 'DELETE' }, true, true),
  };
};
