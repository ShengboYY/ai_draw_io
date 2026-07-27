export const ANONYMOUS_WORKSPACE_KEY = 'ai_draw_io_anonymous_owner_id';

export type WorkspaceIdentity = {
  kind: 'anonymous';
  ownerId: string;
};

type StorageLike = {
  getItem: (key: string) => string | null;
  setItem: (key: string, value: string) => void;
};

const ANONYMOUS_WORKSPACE_PATTERN = /^anon_[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

const normalizeAnonymousOwnerId = (rawId: string) => {
  const ownerId = rawId.trim().toLowerCase();
  return ANONYMOUS_WORKSPACE_PATTERN.test(ownerId) ? ownerId : '';
};

/** Reads a display/import hint only. The HttpOnly cookie remains the authority for every API call. */
export const resolveWorkspaceIdentity = ({
  storage,
}: { storage?: StorageLike | null } = {}): WorkspaceIdentity | null => {
  const ownerId = normalizeAnonymousOwnerId(storage?.getItem(ANONYMOUS_WORKSPACE_KEY) || '');
  return ownerId ? { kind: 'anonymous', ownerId } : null;
};

export const rememberAnonymousWorkspaceHint = (
  storage: StorageLike | null,
  serverOwnerId: string,
) => {
  const ownerId = normalizeAnonymousOwnerId(serverOwnerId || '');
  if (!ownerId) return false;
  try {
    storage?.setItem(ANONYMOUS_WORKSPACE_KEY, ownerId);
  } catch {
    // Private browsing may reject storage; the capability cookie still identifies the request.
  }
  return true;
};

export const getWorkspaceIdentity = () => resolveWorkspaceIdentity({
  storage: typeof window === 'undefined' ? null : window.localStorage,
});
