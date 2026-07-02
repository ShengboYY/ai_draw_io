interface CurrentAccountLike {
  ownerType?: string;
  authenticated?: boolean;
}

export const workspaceLabelFromAccount = (
  account: CurrentAccountLike | null | undefined,
  fallbackOwnerId: string,
) => {
  if (account) {
    return account.authenticated || account.ownerType === 'USER'
      ? 'Signed-in workspace'
      : 'Local workspace';
  }

  // Keep the current anonymous UX stable while the backend status request is loading.
  return fallbackOwnerId.startsWith('anon_') ? 'Local workspace' : 'Signed-in workspace';
};
