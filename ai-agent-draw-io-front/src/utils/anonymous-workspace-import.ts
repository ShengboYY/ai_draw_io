import type { LoginStatus } from '@/types/api';

type StorageLike = {
  getItem: (key: string) => string | null;
  setItem: (key: string, value: string) => void;
  removeItem: (key: string) => void;
};

type PromptInput = {
  loginStatus: LoginStatus | null;
  storage?: StorageLike | null;
  targetUserId?: string | null;
};

type PromptDecision = {
  shouldPrompt: boolean;
  anonymousWorkspaceId: string;
};

export type AnonymousWorkspaceImportNotice = {
  importedCount: number;
  message: string;
};

const ANONYMOUS_WORKSPACE_PATTERN = /^anon_[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const ANONYMOUS_WORKSPACE_KEY = 'ai_draw_io_anonymous_owner_id';
const IMPORT_DECLINED_KEY_PREFIX = 'ai_draw_io_anonymous_import_declined';
const IMPORT_RESULT_KEY = 'ai_draw_io_anonymous_import_result';

const normalizeTargetUserId = (targetUserId: string | null | undefined) => targetUserId?.trim() || 'unknown-user';

const importDeclinedKey = (anonymousWorkspaceId: string, targetUserId: string | null | undefined) => (
  `${IMPORT_DECLINED_KEY_PREFIX}:${normalizeTargetUserId(targetUserId)}:${anonymousWorkspaceId}`
);

export const normalizeImportableAnonymousWorkspaceId = (rawId: string | null | undefined) => {
  const value = rawId?.trim().toLowerCase() || '';
  return ANONYMOUS_WORKSPACE_PATTERN.test(value) ? value : '';
};

export const shouldPromptAnonymousWorkspaceImport = ({
  loginStatus,
  storage,
  targetUserId,
}: PromptInput): PromptDecision => {
  if (loginStatus !== 'SUCCESS') {
    return { shouldPrompt: false, anonymousWorkspaceId: '' };
  }

  const anonymousWorkspaceId = normalizeImportableAnonymousWorkspaceId(
    storage?.getItem(ANONYMOUS_WORKSPACE_KEY),
  );
  if (!anonymousWorkspaceId) {
    return { shouldPrompt: false, anonymousWorkspaceId: '' };
  }

  // Remember a user's explicit "No" so signing in again does not nag them.
  const declined = storage?.getItem(importDeclinedKey(anonymousWorkspaceId, targetUserId)) === '1';
  return {
    shouldPrompt: !declined,
    anonymousWorkspaceId,
  };
};

export const rememberAnonymousWorkspaceImportDeclined = (
  storage: StorageLike | null | undefined,
  anonymousWorkspaceId: string,
  targetUserId: string | null | undefined,
) => {
  const normalizedWorkspaceId = normalizeImportableAnonymousWorkspaceId(anonymousWorkspaceId);
  if (!normalizedWorkspaceId) return;
  storage?.setItem(importDeclinedKey(normalizedWorkspaceId, targetUserId), '1');
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

const buildAnonymousWorkspaceImportNotice = (
  importedCount: number | null | undefined,
): AnonymousWorkspaceImportNotice | null => {
  const normalizedImportedCount = typeof importedCount === 'number' && Number.isInteger(importedCount)
    ? importedCount
    : 0;
  if (normalizedImportedCount <= 0) return null;
  const itemLabel = normalizedImportedCount === 1 ? 'work' : 'works';
  return {
    importedCount: normalizedImportedCount,
    message: `Imported ${normalizedImportedCount} ${itemLabel} from this browser.`,
  };
};

export const rememberAnonymousWorkspaceImportResult = (
  storage: StorageLike | null | undefined,
  importedCount: number | null | undefined,
) => {
  const notice = buildAnonymousWorkspaceImportNotice(importedCount);
  if (!notice) {
    storage?.removeItem(IMPORT_RESULT_KEY);
    return;
  }

  // Session storage carries the import result from login to the library page.
  storage?.setItem(IMPORT_RESULT_KEY, JSON.stringify({ importedCount: notice.importedCount }));
};

export const readAnonymousWorkspaceImportNotice = (
  storage: StorageLike | null | undefined,
): AnonymousWorkspaceImportNotice | null => {
  const rawValue = storage?.getItem(IMPORT_RESULT_KEY);
  if (!rawValue) return null;

  try {
    const parsed = JSON.parse(rawValue) as { importedCount?: unknown };
    return typeof parsed.importedCount === 'number'
      ? buildAnonymousWorkspaceImportNotice(parsed.importedCount)
      : null;
  } catch {
    return null;
  }
};

export const clearAnonymousWorkspaceImportNotice = (
  storage: StorageLike | null | undefined,
) => {
  storage?.removeItem(IMPORT_RESULT_KEY);
};
