import type { MaterialCapabilities } from '../features/materials/material-types';

type FetchImplementation = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

export class MaterialCapabilitiesApiError extends Error {
  readonly code: string;

  constructor(code: string, message: string) {
    super(message);
    this.name = 'MaterialCapabilitiesApiError';
    this.code = code;
  }
}

type MaterialCapabilitiesClientOptions = {
  baseUrl: string;
  fetch?: FetchImplementation;
};

export const createMaterialCapabilitiesClient = ({
  baseUrl,
  fetch: fetchImplementation = globalThis.fetch.bind(globalThis),
}: MaterialCapabilitiesClientOptions) => ({
  get: async (): Promise<MaterialCapabilities> => {
    const response = await fetchImplementation(`${baseUrl}/material-capabilities`, { credentials: 'include' });
    if (!response.ok) throw new MaterialCapabilitiesApiError(
      'HTTP_ERROR', `Material capability request failed with ${response.status}`);
    const envelope = await response.json() as { code: string; info?: string; data?: MaterialCapabilities };
    if (envelope.code !== '0000' || !envelope.data) {
      throw new MaterialCapabilitiesApiError(envelope.code || 'UNKNOWN', envelope.info || 'Material capability request failed');
    }
    return envelope.data;
  },
});
