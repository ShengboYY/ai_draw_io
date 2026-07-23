export type CsrfHeadersProvider = () => Promise<Record<string, string>>;

type FetchImplementation = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;
type CsrfTokenResponse = { code: string; data?: { headerName?: string; token?: string } };

const readCookie = (name: string): string | null => {
  if (typeof document === 'undefined') return null;
  const prefix = `${name}=`;
  const cookie = document.cookie.split('; ').find(part => part.startsWith(prefix));
  return cookie ? decodeURIComponent(cookie.slice(prefix.length)) : null;
};

/** Reuse one browser CSRF lookup across parallel write operations. */
export const createCsrfHeadersProvider = (
  baseUrl: string,
  fetchImplementation: FetchImplementation = globalThis.fetch.bind(globalThis),
): CsrfHeadersProvider => {
  let headersPromise: Promise<Record<string, string>> | null = null;

  const load = async (): Promise<Record<string, string>> => {
    const cookieToken = readCookie('XSRF-TOKEN');
    if (cookieToken) return { 'X-XSRF-TOKEN': cookieToken };

    const response = await fetchImplementation(`${baseUrl}/auth/csrf`, {
      method: 'GET',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
    });
    if (!response.ok) return {};
    const envelope = await response.json() as CsrfTokenResponse;
    const token = envelope.code === '0000' ? envelope.data?.token : undefined;
    return token ? { [envelope.data?.headerName || 'X-XSRF-TOKEN']: token } : {};
  };

  return () => {
    headersPromise = headersPromise || load();
    return headersPromise;
  };
};
