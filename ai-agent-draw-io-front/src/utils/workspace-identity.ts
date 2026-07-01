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

const defaultGenerateId = () => {
  const cryptoId = globalThis.crypto?.randomUUID?.();
  if (cryptoId) return cryptoId;
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
};

const normalizeAnonymousOwnerId = (rawId: string) => {
  const cleanId = rawId.trim() || defaultGenerateId();
  return cleanId.startsWith('anon_') ? cleanId : `anon_${cleanId}`;
};

export const resolveWorkspaceIdentity = ({
  loginUser,
  storage,
  generateId = defaultGenerateId,
}: ResolveWorkspaceIdentityInput = {}): WorkspaceIdentity => {
  const authenticatedUser = loginUser?.trim();
  if (authenticatedUser) {
    return {
      kind: 'authenticated',
      ownerId: authenticatedUser,
    };
  }

  const storedOwnerId = storage?.getItem(ANONYMOUS_WORKSPACE_KEY)?.trim();
  if (storedOwnerId) {
    return {
      kind: 'anonymous',
      ownerId: storedOwnerId,
    };
  }

  const ownerId = normalizeAnonymousOwnerId(generateId());
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
