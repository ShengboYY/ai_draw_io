import type { CurrentAccountResponseDTO } from '@/types/api';

const isAnonymousOwnerId = (ownerId?: string | null) => Boolean(ownerId?.startsWith('anon_'));

export const workspaceLabelFromAccount = (
  account: CurrentAccountResponseDTO | null,
  fallbackOwnerId?: string | null,
) => {
  // The backend account response is authoritative once it has loaded; the local owner id is a startup fallback.
  if (account?.authenticated || account?.ownerType === 'USER') {
    return 'Signed-in workspace';
  }
  if (account?.ownerType === 'ANONYMOUS' || account?.accountStatus === 'ANONYMOUS' || isAnonymousOwnerId(fallbackOwnerId)) {
    return 'Local workspace';
  }
  return 'Signed-in workspace';
};
