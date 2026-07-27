'use client';

import { useCallback, useEffect, useState } from 'react';
import { agentApi } from '@/api/agent';
import { clearUserInfo, getUserInfo, setUserInfo as persistUserInfo, type UserInfo } from '@/utils/cookie';
import { rememberAnonymousWorkspaceHint } from '@/utils/workspace-identity';
import type { CurrentAccountResponseDTO } from '@/types/api';

export const displayNameFromUser = (value?: string | null) => {
  if (!value) return 'Anonymous';
  const cleanValue = value.trim();
  if (!cleanValue) return 'Anonymous';
  return cleanValue.includes('@') ? cleanValue.split('@')[0] : cleanValue;
};

export const initialsFromUser = (value?: string | null) => {
  const displayName = displayNameFromUser(value);
  const initials = displayName
    .split(/[\s._-]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map(part => part[0])
    .join('');
  return initials.toUpperCase() || 'A';
};

export type WorkspaceIdentity = {
  userInfo: UserInfo | null;
  ownerId: string;
  currentAccount: CurrentAccountResponseDTO | null;
  isSignedInWorkspace: boolean;
  userDisplayName: string;
  userInitials: string;
  /** Set when no workspace could be resolved at all, so callers can stop their own loading. */
  error: string;
  logout: () => Promise<void>;
};

/**
 * Resolves who owns the current workspace, shared by every signed-in workspace surface.
 * The server session is authoritative; the legacy cookie is only a UI label fallback.
 */
export const useWorkspaceIdentity = (): WorkspaceIdentity => {
  const [userInfo, setUserInfo] = useState<UserInfo | null>(null);
  const [ownerId, setOwnerId] = useState('');
  const [currentAccount, setCurrentAccount] = useState<CurrentAccountResponseDTO | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;
    const resolveInitialIdentity = async () => {
      const browserUserInfo = getUserInfo();
      setUserInfo(browserUserInfo);

      try {
        const res = await agentApi.me();
        const account = res.data;
        if (!cancelled && account?.status === 'SUCCESS' && account.userId) {
          const displayUser = account.email || browserUserInfo?.user || account.userId;
          setUserInfo({ user: displayUser, ts: Date.now() });
          if (account.email) persistUserInfo(account.email);
          setOwnerId(account.userId);
          return;
        }
      } catch {
        // Continue below and ask the server for an anonymous capability.
      }

      if (!cancelled) {
        try {
          const anonymous = await agentApi.ensureAnonymousWorkspace();
          const anonymousOwnerId = anonymous.data?.ownerId || '';
          rememberAnonymousWorkspaceHint(window.localStorage, anonymousOwnerId);
          setOwnerId(anonymousOwnerId);
        } catch {
          setError('Failed to initialize the anonymous workspace.');
        }
      }
    };

    void resolveInitialIdentity();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!ownerId) return;

    let cancelled = false;
    agentApi.currentAccount(ownerId)
      .then(res => {
        if (!cancelled) setCurrentAccount(res.data || null);
      })
      .catch(() => {
        if (!cancelled) setCurrentAccount(null);
      });

    return () => {
      cancelled = true;
    };
  }, [ownerId]);

  const logout = useCallback(async () => {
    setError('');
    try {
      await agentApi.logout();
    } catch {
      // Best-effort logout keeps local UI usable if the server session is already gone.
    }
    clearUserInfo();
    setUserInfo(null);
    setCurrentAccount(null);
    setOwnerId('');
    try {
      const anonymous = await agentApi.ensureAnonymousWorkspace();
      const anonymousOwnerId = anonymous.data?.ownerId || '';
      rememberAnonymousWorkspaceHint(window.localStorage, anonymousOwnerId);
      setOwnerId(anonymousOwnerId);
    } catch {
      setError('Failed to initialize the anonymous workspace.');
    }
  }, []);

  return {
    userInfo,
    ownerId,
    currentAccount,
    isSignedInWorkspace: Boolean(
      currentAccount?.authenticated || currentAccount?.ownerType === 'USER' || (ownerId && !ownerId.startsWith('anon_')),
    ),
    userDisplayName: displayNameFromUser(userInfo?.user),
    userInitials: initialsFromUser(userInfo?.user),
    error,
    logout,
  };
};
