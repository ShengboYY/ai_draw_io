import type { LoginStatus } from '@/types/api';

type StorageLike = {
  getItem: (key: string) => string | null;
  removeItem: (key: string) => void;
};

type PromptInput = {
  loginStatus: LoginStatus | null;
  storage?: StorageLike | null;
};

type PromptDecision = {
  shouldPrompt: boolean;
  anonymousWorkspaceId: string;
};

const ANONYMOUS_WORKSPACE_PATTERN = /^anon_[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const ANONYMOUS_WORKSPACE_KEY = 'ai_draw_io_anonymous_owner_id';

export const normalizeImportableAnonymousWorkspaceId = (rawId: string | null | undefined) => {
  const value = rawId?.trim().toLowerCase() || '';
  return ANONYMOUS_WORKSPACE_PATTERN.test(value) ? value : '';
};

export const shouldPromptAnonymousWorkspaceImport = ({
  loginStatus,
  storage,
}: PromptInput): PromptDecision => {
  if (loginStatus !== 'SUCCESS') {
    return { shouldPrompt: false, anonymousWorkspaceId: '' };
  }

  const anonymousWorkspaceId = normalizeImportableAnonymousWorkspaceId(
    storage?.getItem(ANONYMOUS_WORKSPACE_KEY),
  );
  return {
    shouldPrompt: Boolean(anonymousWorkspaceId),
    anonymousWorkspaceId,
  };
};

export const clearImportedAnonymousWorkspace = (
  storage: StorageLike | null | undefined,
  importedWorkspaceId: string,
) => {
  const storedWorkspaceId = normalizeImportableAnonymousWorkspaceId(
    storage?.getItem(ANONYMOUS_WORKSPACE_KEY),
  );
  // Only clear the exact imported source so a newer anonymous workspace is not removed accidentally.
  if (storedWorkspaceId && storedWorkspaceId === importedWorkspaceId) {
    storage?.removeItem(ANONYMOUS_WORKSPACE_KEY);
  }
};
