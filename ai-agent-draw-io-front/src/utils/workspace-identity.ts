export const ANONYMOUS_WORKSPACE_KEY = 'ai_draw_io_anonymous_owner_id';

export type WorkspaceIdentity = {
  kind: 'authenticated' | 'anonymous';
  ownerId: string;
};

type StorageLike = {
  getItem: (key: string) => string | null;
  setItem: (key: string, value: string) => void;
};

type ResolveWorkspaceIdentityInput = {
  loginUser?: string | null;
  storage?: StorageLike | null;
  generateId?: () => string;
};

const ANONYMOUS_WORKSPACE_PATTERN = /^anon_[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

const defaultGenerateId = () => {
  const cryptoId = globalThis.crypto?.randomUUID?.();
  if (cryptoId) return cryptoId;
  const bytes = new Uint8Array(16);
  globalThis.crypto?.getRandomValues?.(bytes);
  if (bytes.some(byte => byte !== 0)) {
    // Build a UUID v4 from cryptographically secure random bytes.
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0'));
    return `${hex.slice(0, 4).join('')}-${hex.slice(4, 6).join('')}-${hex.slice(6, 8).join('')}-${hex.slice(8, 10).join('')}-${hex.slice(10).join('')}`;
  }
  throw new Error('Secure random workspace id is unavailable.');
};

const normalizeAnonymousOwnerId = (rawId: string) => {
  const cleanId = rawId.trim().toLowerCase();
  if (!cleanId) return '';
  const ownerId = cleanId.startsWith('anon_') ? cleanId : `anon_${cleanId}`;
  return ANONYMOUS_WORKSPACE_PATTERN.test(ownerId) ? ownerId : '';
};

export const resolveWorkspaceIdentity = ({
  storage,
  generateId = defaultGenerateId,
}: ResolveWorkspaceIdentityInput = {}): WorkspaceIdentity => {
  // There is no real auth system yet, so login cookie names are not authority.
  const storedOwnerId = normalizeAnonymousOwnerId(storage?.getItem(ANONYMOUS_WORKSPACE_KEY) || '');
  if (storedOwnerId) {
    return {
      kind: 'anonymous',
      ownerId: storedOwnerId,
    };
  }

  const ownerId = normalizeAnonymousOwnerId(generateId()) || normalizeAnonymousOwnerId(defaultGenerateId());
  try {
    storage?.setItem(ANONYMOUS_WORKSPACE_KEY, ownerId);
  } catch {
    // Storage can be unavailable in private modes; the generated owner still works for this page session.
  }

  return {
    kind: 'anonymous',
    ownerId,
  };
};

export const getWorkspaceIdentity = (loginUser?: string | null) => resolveWorkspaceIdentity({
  loginUser,
  storage: typeof window === 'undefined' ? null : window.localStorage,
});
