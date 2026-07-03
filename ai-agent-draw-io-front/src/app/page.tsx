'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { agentApi } from '@/api/agent';
import { clearUserInfo, getUserInfo, setUserInfo as persistUserInfo, type UserInfo } from '@/utils/cookie';
import { getWorkspaceIdentity } from '@/utils/workspace-identity';
import { CurrentAccountResponseDTO, DiagramSummaryResponseDTO } from '@/types/api';
import { isAccountMenuTarget, isDiagramActionMenuTarget } from './home-menu-click-away';

const formatUpdatedAt = (value?: string) => {
  if (!value) return 'No updates yet';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return 'No updates yet';
  return new Intl.DateTimeFormat(undefined, {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  }).format(date);
};

const displayNameFromUser = (value?: string | null) => {
  if (!value) return 'Anonymous';
  const cleanValue = value.trim();
  if (!cleanValue) return 'Anonymous';
  return cleanValue.includes('@') ? cleanValue.split('@')[0] : cleanValue;
};

const initialsFromUser = (value?: string | null) => {
  const displayName = displayNameFromUser(value);
  const initials = displayName
    .split(/[\s._-]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map(part => part[0])
    .join('');
  return initials.toUpperCase() || 'A';
};

const diagramTitle = (diagram: DiagramSummaryResponseDTO) => diagram.title || 'Untitled Diagram';
// Keep list preview frames aligned with the landscape Draw.io canvas shape.
const DRAWIO_CANVAS_PREVIEW_ASPECT_CLASS = 'aspect-[4/3]';

export default function Home() {
  const router = useRouter();
  const [userInfo, setUserInfo] = useState<UserInfo | null>(null);
  const [ownerId, setOwnerId] = useState('');
  const [currentAccount, setCurrentAccount] = useState<CurrentAccountResponseDTO | null>(null);
  const [diagrams, setDiagrams] = useState<DiagramSummaryResponseDTO[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [errorMessage, setErrorMessage] = useState('');
  const [openMenuId, setOpenMenuId] = useState<string | null>(null);
  const [isAccountMenuOpen, setIsAccountMenuOpen] = useState(false);

  const userDisplayName = displayNameFromUser(userInfo?.user);
  const userInitials = initialsFromUser(userInfo?.user);
  const isSignedInWorkspace = Boolean(
    currentAccount?.authenticated || currentAccount?.ownerType === 'USER' || (ownerId && !ownerId.startsWith('anon_')),
  );

  useEffect(() => {
    let cancelled = false;
    const resolveInitialIdentity = async () => {
      // The server session is authoritative; the legacy cookie is only a UI label fallback.
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
        // Fall back to the browser-local anonymous workspace when the backend is unavailable.
      }

      if (!cancelled) {
        setOwnerId(getWorkspaceIdentity(browserUserInfo?.user).ownerId);
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

    agentApi.listDiagrams(ownerId)
      .then(res => {
        if (!cancelled) setDiagrams(res.data || []);
      })
      .catch(() => {
        if (!cancelled) setErrorMessage('Failed to load diagrams.');
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [ownerId]);

  useEffect(() => {
    if (!openMenuId && !isAccountMenuOpen) return;

    const closeMenuOnOutsidePointerDown = (event: PointerEvent) => {
      // Keep menu actions clickable while allowing the rest of the page to dismiss open menus.
      if (!isDiagramActionMenuTarget(event.target)) setOpenMenuId(null);
      if (!isAccountMenuTarget(event.target)) setIsAccountMenuOpen(false);
    };

    document.addEventListener('pointerdown', closeMenuOnOutsidePointerDown);
    return () => {
      document.removeEventListener('pointerdown', closeMenuOnOutsidePointerDown);
    };
  }, [openMenuId, isAccountMenuOpen]);

  const openDiagram = (diagramId: string) => {
    setOpenMenuId(null);
    setIsAccountMenuOpen(false);
    router.push(`/drawio?diagramId=${encodeURIComponent(diagramId)}`);
  };

  const startNewDiagram = () => {
    setOpenMenuId(null);
    setIsAccountMenuOpen(false);
    router.push('/drawio?new=1');
  };

  const openAccountMenu = () => {
    // Keep closing on outside pointerdown so users can move from the trigger into the detached menu.
    setOpenMenuId(null);
    setIsAccountMenuOpen(true);
  };

  const logout = async () => {
    setOpenMenuId(null);
    setIsAccountMenuOpen(false);
    setErrorMessage('');
    try {
      await agentApi.logout();
    } catch {
      // Best-effort logout keeps local UI usable if the server session is already gone.
    }
    clearUserInfo();
    setUserInfo(null);
    setCurrentAccount(null);
    setIsLoading(true);
    setOwnerId(getWorkspaceIdentity(null).ownerId);
  };

  const renameDiagram = async (diagram: DiagramSummaryResponseDTO) => {
    setOpenMenuId(null);
    const nextTitle = window.prompt('Rename diagram', diagramTitle(diagram));
    if (nextTitle === null) return;

    const title = nextTitle.trim();
    if (!title) return;

    setErrorMessage('');
    try {
      const res = await agentApi.renameDiagram(ownerId, diagram.diagramId, title);
      const updated = res.data;
      setDiagrams(prev => prev.map(item => (
        item.diagramId === diagram.diagramId
          ? {
              ...item,
              title: updated?.title || title,
              diagramType: updated?.diagramType || item.diagramType,
              version: updated?.version || item.version,
              updatedAt: updated?.updatedAt || item.updatedAt,
            }
          : item
      )));
    } catch {
      setErrorMessage('Failed to rename diagram.');
    }
  };

  const deleteDiagram = async (diagram: DiagramSummaryResponseDTO) => {
    setOpenMenuId(null);
    if (!window.confirm(`Delete "${diagramTitle(diagram)}"?`)) return;

    setErrorMessage('');
    try {
      const res = await agentApi.deleteDiagram(ownerId, diagram.diagramId);
      if (!res.data) {
        setErrorMessage('Diagram was not deleted.');
        return;
      }
      setDiagrams(prev => prev.filter(item => item.diagramId !== diagram.diagramId));
    } catch {
      setErrorMessage('Failed to delete diagram.');
    }
  };

  return (
    <main className="app-page text-zinc-800">
      <header className="sticky top-0 z-20 border-b border-stone-200 bg-white/95 backdrop-blur">
        <div className="flex h-16 items-center justify-between gap-4 px-4 sm:px-6">
          <h1 className="shrink-0 text-xl font-medium tracking-normal text-zinc-800 sm:text-2xl">My diagrams</h1>
          <div className="flex min-w-0 items-center gap-3">
            <button
              type="button"
              onClick={startNewDiagram}
              className="theme-btn flex h-10 w-10 items-center justify-center rounded-lg px-0 text-sm font-medium transition sm:w-auto sm:px-4"
              aria-label="New diagram"
            >
              <span className="text-lg leading-none sm:hidden">+</span>
              <span className="hidden whitespace-nowrap sm:inline">New diagram</span>
            </button>
            {!isSignedInWorkspace && (
              <Link
                href="/login"
                className="theme-btn-secondary hidden h-10 items-center rounded-lg px-3 text-sm font-medium transition sm:inline-flex"
                title="Admin accounts use the same sign-in page."
              >
                Sign in
              </Link>
            )}
            <div
              className="relative flex min-w-0 items-center gap-2"
              data-account-menu
              onMouseEnter={openAccountMenu}
            >
              {isSignedInWorkspace ? (
                <>
                  <button
                    type="button"
                    onClick={openAccountMenu}
                    aria-label={`${userDisplayName} account menu`}
                    aria-haspopup="menu"
                    aria-expanded={isAccountMenuOpen}
                    className="flex min-w-0 items-center gap-2 rounded-lg transition hover:bg-stone-100 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-zinc-700/20"
                    title={userDisplayName}
                  >
                    <span className="hidden min-w-0 text-right sm:block">
                      <span className="block truncate text-sm font-medium text-zinc-700">{userDisplayName}</span>
                    </span>
                    <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-zinc-700 text-sm font-semibold text-white shadow-sm transition">
                      {userInitials}
                    </span>
                  </button>
                  {isAccountMenuOpen && (
                    <div
                      role="menu"
                      className="absolute right-0 top-12 z-30 w-36 overflow-hidden rounded-lg border border-stone-200 bg-white py-1 text-sm shadow-lg"
                    >
                      <button
                        type="button"
                        onClick={logout}
                        role="menuitem"
                        className="block w-full px-3 py-2 text-left text-zinc-700 transition hover:bg-stone-50"
                      >
                        Sign out
                      </button>
                    </div>
                  )}
                </>
              ) : (
                <Link
                  href="/login"
                  aria-label="Sign in"
                  className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-zinc-700 text-sm font-semibold text-white shadow-sm transition hover:bg-zinc-600"
                  title="Sign in"
                >
                  {userInitials}
                </Link>
              )}
            </div>
          </div>
        </div>
      </header>

      <div className="mx-auto flex w-full max-w-7xl flex-col px-4 py-7 sm:px-6 lg:px-8">
        {errorMessage && (
          <div className="mb-6 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">
            {errorMessage}
          </div>
        )}

        <section className="flex-1">
          <div className="mb-5 flex items-end justify-between gap-4">
            <div>
              <h2 className="whitespace-nowrap text-base font-medium tracking-normal text-zinc-700">Recent diagrams</h2>
              <p className="mt-1 whitespace-nowrap text-sm text-zinc-500">
                {isLoading ? 'Loading your diagrams...' : `${diagrams.length} saved ${diagrams.length === 1 ? 'diagram' : 'diagrams'}`}
              </p>
            </div>
          </div>

          {isLoading ? (
            <div className="grid gap-x-5 gap-y-7 sm:grid-cols-2 lg:grid-cols-4 xl:grid-cols-5">
              {Array.from({ length: 5 }).map((_, index) => (
                <div key={index} className="animate-pulse">
                  <div className={`${DRAWIO_CANVAS_PREVIEW_ASPECT_CLASS} rounded-lg border border-stone-200 bg-white shadow-sm`} />
                  <div className="mt-3 h-4 w-3/4 rounded-full bg-zinc-200" />
                  <div className="mt-2 h-3 w-1/2 rounded-full bg-stone-100" />
                </div>
              ))}
            </div>
          ) : diagrams.length === 0 ? (
            <div className="rounded-lg border border-dashed border-stone-300 bg-white px-5 py-8">
              <h2 className="text-base font-medium tracking-normal text-zinc-800">No diagrams yet</h2>
              <p className="mt-2 text-sm text-zinc-500">Create your first diagram and it will appear here.</p>
              <button
                type="button"
                onClick={startNewDiagram}
                className="theme-btn mt-4 h-10 rounded-lg px-4 text-sm font-medium transition"
              >
                New diagram
              </button>
            </div>
          ) : (
            <div className="grid gap-x-5 gap-y-7 sm:grid-cols-2 lg:grid-cols-4 xl:grid-cols-5">
              {diagrams.map(diagram => (
                <article
                  key={diagram.diagramId}
                  className="group relative min-w-0"
                >
                  <button
                    type="button"
                    onClick={() => openDiagram(diagram.diagramId)}
                    className="block w-full overflow-hidden rounded-lg border border-stone-200 bg-white text-left shadow-sm transition hover:border-stone-300 hover:shadow-md focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-zinc-700/20"
                    aria-label={`Open ${diagramTitle(diagram)}`}
                  >
                    <div className={`${DRAWIO_CANVAS_PREVIEW_ASPECT_CLASS} bg-white`}>
                      {diagram.thumbnailUrl ? (
                        // Data URL thumbnails are generated by the canvas, so Next image optimization is unnecessary.
                        // eslint-disable-next-line @next/next/no-img-element
                        <img
                          src={diagram.thumbnailUrl}
                          alt={`${diagramTitle(diagram)} thumbnail`}
                          className="h-full w-full object-contain p-3"
                        />
                      ) : (
                        // Show a document-like preview until every saved diagram has a thumbnail.
                        <div className="flex h-full flex-col justify-center p-6">
                          <div className="mx-auto mb-6 h-12 w-16 rounded border border-stone-200 bg-stone-50" />
                          <div className="space-y-2">
                            <div className="h-2 w-3/4 rounded-full bg-zinc-200" />
                            <div className="h-2 w-full rounded-full bg-stone-100" />
                            <div className="h-2 w-5/6 rounded-full bg-stone-100" />
                          </div>
                          <div className="mt-6 grid grid-cols-2 gap-2">
                            <div className="h-9 rounded border border-stone-100 bg-stone-50" />
                            <div className="h-9 rounded border border-stone-100 bg-stone-50" />
                          </div>
                        </div>
                      )}
                    </div>
                  </button>

                  <div className="mt-3 flex min-w-0 items-start justify-between gap-2">
                    <button
                      type="button"
                      onClick={() => openDiagram(diagram.diagramId)}
                      className="line-clamp-2 min-w-0 text-left text-sm font-medium leading-5 tracking-normal text-zinc-800 hover:text-zinc-600"
                    >
                      {diagramTitle(diagram)}
                    </button>
                    <div className="relative shrink-0" data-diagram-action-menu>
                      <button
                        type="button"
                        onClick={() => setOpenMenuId(prev => prev === diagram.diagramId ? null : diagram.diagramId)}
                        className="flex h-8 w-8 items-center justify-center rounded-lg text-sm font-semibold text-zinc-500 transition hover:bg-stone-100 hover:text-zinc-700"
                        aria-label={`More actions for ${diagramTitle(diagram)}`}
                      >
                        ...
                      </button>
                      {openMenuId === diagram.diagramId && (
                        <div className="absolute right-0 top-9 z-10 w-32 overflow-hidden rounded-lg border border-stone-200 bg-white py-1 text-sm shadow-lg">
                          <button
                            type="button"
                            onClick={() => renameDiagram(diagram)}
                            className="block w-full px-3 py-2 text-left text-zinc-700 transition hover:bg-stone-50"
                          >
                            Rename
                          </button>
                          <button
                            type="button"
                            onClick={() => deleteDiagram(diagram)}
                            className="block w-full px-3 py-2 text-left text-rose-700 transition hover:bg-rose-50"
                          >
                            Delete
                          </button>
                        </div>
                      )}
                    </div>
                  </div>
                  <div className="mt-1 flex min-w-0 items-center gap-2 text-xs text-zinc-500">
                    <span className="truncate">{diagram.diagramType || 'basic'}</span>
                    <span className="text-zinc-300">/</span>
                    <span className="truncate">{formatUpdatedAt(diagram.updatedAt)}</span>
                    <span className="ml-auto shrink-0 text-zinc-400">v{diagram.version || 1}</span>
                  </div>
                </article>
              ))}
            </div>
          )}
        </section>
      </div>
    </main>
  );
}
