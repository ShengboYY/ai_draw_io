'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import Image from 'next/image';
import { useRouter } from 'next/navigation';
import { agentApi } from '@/api/agent';
import { clearUserInfo, getUserInfo, setUserInfo as persistUserInfo, type UserInfo } from '@/utils/cookie';
import { getWorkspaceIdentity } from '@/utils/workspace-identity';
import {
  clearAnonymousWorkspaceImportNotice,
  readAnonymousWorkspaceImportNotice,
  type AnonymousWorkspaceImportNotice,
} from '@/utils/anonymous-workspace-import';
import { CurrentAccountResponseDTO, DiagramSummaryResponseDTO } from '@/types/api';
import { isAccountMenuTarget, isDiagramActionMenuTarget, isSortMenuTarget } from '../home-menu-click-away';
import {
  applyDiagramLibraryView,
  categoryDisplayLabel,
  countByFilter,
  DIAGRAM_FILTERS,
  DIAGRAM_SORTS,
  type DiagramFilter,
  type DiagramSortMode,
} from '../diagram-library';

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
const DRAWIO_CANVAS_PREVIEW_ASPECT_CLASS = 'aspect-[16/9] sm:aspect-[4/3]';

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
  const [searchQuery, setSearchQuery] = useState('');
  const [activeFilter, setActiveFilter] = useState<DiagramFilter>('all');
  const [sortMode, setSortMode] = useState<DiagramSortMode>('recent');
  const [isSortMenuOpen, setIsSortMenuOpen] = useState(false);
  const [importNotice, setImportNotice] = useState<AnonymousWorkspaceImportNotice | null>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);

  const visibleDiagrams = useMemo(
    () => applyDiagramLibraryView(diagrams, { query: searchQuery, filter: activeFilter, sort: sortMode }),
    [diagrams, searchQuery, activeFilter, sortMode],
  );
  const filterCounts = useMemo(
    () => Object.fromEntries(DIAGRAM_FILTERS.map(({ id }) => [id, countByFilter(diagrams, id)])) as Record<DiagramFilter, number>,
    [diagrams],
  );
  const activeSortLabel = DIAGRAM_SORTS.find(sort => sort.id === sortMode)?.label ?? 'recent';
  const hasSearch = searchQuery.trim().length > 0;

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
    const notice = readAnonymousWorkspaceImportNotice(
      typeof window === 'undefined' ? null : window.sessionStorage,
    );
    if (!notice) return;
    // Defer the one-time browser notice so hydration stays aligned with the server render.
    const timeoutId = window.setTimeout(() => setImportNotice(notice), 0);
    return () => window.clearTimeout(timeoutId);
  }, []);

  useEffect(() => {
    // ⌘K / Ctrl+K jumps focus to the diagram search box from anywhere on the page.
    const focusSearchOnShortcut = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        searchInputRef.current?.focus();
        searchInputRef.current?.select();
      }
    };
    window.addEventListener('keydown', focusSearchOnShortcut);
    return () => window.removeEventListener('keydown', focusSearchOnShortcut);
  }, []);

  useEffect(() => {
    if (!openMenuId && !isAccountMenuOpen && !isSortMenuOpen) return;

    const closeMenuOnOutsidePointerDown = (event: PointerEvent) => {
      // Keep menu actions clickable while allowing the rest of the page to dismiss open menus.
      if (!isDiagramActionMenuTarget(event.target)) setOpenMenuId(null);
      if (!isAccountMenuTarget(event.target)) setIsAccountMenuOpen(false);
      if (!isSortMenuTarget(event.target)) setIsSortMenuOpen(false);
    };

    document.addEventListener('pointerdown', closeMenuOnOutsidePointerDown);
    return () => {
      document.removeEventListener('pointerdown', closeMenuOnOutsidePointerDown);
    };
  }, [openMenuId, isAccountMenuOpen, isSortMenuOpen]);

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
    setIsSortMenuOpen(false);
    setIsAccountMenuOpen(true);
  };

  const chooseSort = (mode: DiagramSortMode) => {
    setSortMode(mode);
    setIsSortMenuOpen(false);
  };

  const dismissImportNotice = () => {
    clearAnonymousWorkspaceImportNotice(typeof window === 'undefined' ? null : window.sessionStorage);
    setImportNotice(null);
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
        <div className="flex h-auto flex-wrap items-center gap-3 px-4 py-3 sm:h-16 sm:flex-nowrap sm:gap-4 sm:px-6 sm:py-0">
          <Link href="/diagrams" className="order-1 flex shrink-0 items-center gap-2.5 sm:order-none" aria-label="FreeDraw home">
            <span className="relative block h-9 w-9 overflow-hidden rounded-xl shadow-sm" aria-hidden="true">
              <Image src="/brand/freedraw-logo-dark.png" alt="" fill sizes="36px" className="object-cover" priority />
            </span>
            <span className="font-display text-lg font-semibold tracking-tight text-zinc-800">FreeDraw</span>
          </Link>

          {/* Client-side search over the fully-loaded workspace list — instant, no round trips. */}
          <div className="order-3 relative mx-0 flex w-full max-w-none basis-full items-center sm:order-none sm:mx-auto sm:max-w-xl sm:basis-auto">
            <svg
              viewBox="0 0 24 24"
              className="pointer-events-none absolute left-3.5 h-4 w-4 text-zinc-400"
              fill="none"
              stroke="currentColor"
              strokeWidth={2}
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <circle cx="11" cy="11" r="7" />
              <path d="m20 20-3.5-3.5" />
            </svg>
            <input
              ref={searchInputRef}
              type="search"
              value={searchQuery}
              onChange={event => setSearchQuery(event.target.value)}
              placeholder="Search diagrams"
              aria-label="Search diagrams"
              className="h-11 w-full rounded-xl border border-stone-200 bg-stone-50 pl-10 pr-14 text-sm text-zinc-800 placeholder:text-zinc-400 transition focus:border-zinc-400 focus:bg-white focus:outline-none focus:ring-2 focus:ring-zinc-700/10"
            />
            <kbd className="pointer-events-none absolute right-3 hidden items-center rounded-md border border-stone-200 bg-white px-1.5 py-0.5 font-mono text-xs text-zinc-400 sm:inline-flex">
              ⌘K
            </kbd>
          </div>

          <div className="order-2 ml-auto flex min-w-0 shrink-0 items-center gap-3 sm:order-none sm:ml-0">
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
                      className="absolute right-0 top-12 z-30 w-48 overflow-hidden rounded-lg border border-stone-200 bg-white py-1 text-sm shadow-lg"
                    >
                      {/* Admin authorization remains server-enforced; expired sessions retain /admin as the login return target. */}
                      <Link
                        href="/admin"
                        role="menuitem"
                        onClick={() => setIsAccountMenuOpen(false)}
                        className="block w-full px-3 py-2 text-left font-medium text-zinc-700 transition hover:bg-stone-50"
                      >
                        Admin dashboard
                      </Link>
                      <div className="my-1 border-t border-stone-100" role="separator" />
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

      <div className="mx-auto flex w-full max-w-7xl flex-col px-4 py-5 sm:px-6 sm:py-7 lg:px-8">
        {errorMessage && (
          <div className="mb-6 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">
            {errorMessage}
          </div>
        )}

        <section className="flex-1">
          <h1 className="text-2xl font-bold tracking-tight text-zinc-900 sm:text-4xl">My diagrams</h1>

          {importNotice && (
            <div className="mx-auto mt-4 flex max-w-max items-center gap-3 rounded-lg bg-stone-200/80 px-4 py-2.5 font-sans text-sm font-medium text-zinc-900">
              <span>{importNotice.message}</span>
              <button
                type="button"
                onClick={dismissImportNotice}
                aria-label="Dismiss import notice"
                className="flex h-8 w-8 items-center justify-center rounded-full text-xl text-zinc-500 transition hover:bg-stone-300 hover:text-zinc-800"
              >
                ×
              </button>
            </div>
          )}

          <div className={`${importNotice ? 'mt-6' : 'mt-7'} flex flex-col items-stretch gap-3 border-b border-stone-200 pb-4 sm:flex-row sm:items-center sm:justify-between sm:gap-4`}>
            {/* Category filter tabs — instant client-side filtering by diagram kind. */}
            <div className="flex max-w-full items-center gap-2 overflow-x-auto pb-1" role="tablist" aria-label="Filter diagrams by category">
              {DIAGRAM_FILTERS.map(filter => {
                const isActive = activeFilter === filter.id;
                return (
                  <button
                    key={filter.id}
                    type="button"
                    role="tab"
                    aria-selected={isActive}
                    onClick={() => setActiveFilter(filter.id)}
                    className={`rounded-lg px-3.5 py-1.5 text-sm font-semibold transition ${
                      isActive
                        ? 'bg-zinc-800 text-white shadow-sm'
                        : 'text-zinc-500 hover:bg-stone-100 hover:text-zinc-700'
                    }`}
                  >
                    {filter.label}
                    {!isLoading && (
                      <span className={`ml-1.5 text-xs font-medium ${isActive ? 'text-zinc-300' : 'text-zinc-400'}`}>
                        {filterCounts[filter.id] ?? 0}
                      </span>
                    )}
                  </button>
                );
              })}
            </div>

            {/* Sort control — reorders the loaded list without a server round trip. */}
            <div className="relative" data-sort-menu>
              <button
                type="button"
                onClick={() => setIsSortMenuOpen(open => !open)}
                aria-haspopup="menu"
                aria-expanded={isSortMenuOpen}
                className="flex items-center gap-1.5 rounded-lg px-2 py-1.5 text-sm text-zinc-500 transition hover:text-zinc-700"
              >
                <span>sorted by <span className="font-semibold text-zinc-700">{activeSortLabel}</span></span>
                <svg viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <path d="m6 9 6 6 6-6" />
                </svg>
              </button>
              {isSortMenuOpen && (
                <div
                  role="menu"
                  className="absolute right-0 top-10 z-30 w-40 overflow-hidden rounded-lg border border-stone-200 bg-white py-1 text-sm shadow-lg"
                >
                  {DIAGRAM_SORTS.map(sort => (
                    <button
                      key={sort.id}
                      type="button"
                      role="menuitemradio"
                      aria-checked={sortMode === sort.id}
                      onClick={() => chooseSort(sort.id)}
                      className={`flex w-full items-center justify-between px-3 py-2 text-left transition hover:bg-stone-50 ${
                        sortMode === sort.id ? 'font-semibold text-zinc-800' : 'text-zinc-600'
                      }`}
                    >
                      <span>by {sort.label}</span>
                      {sortMode === sort.id && (
                        <svg viewBox="0 0 24 24" className="h-4 w-4 text-zinc-700" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                          <path d="M20 6 9 17l-5-5" />
                        </svg>
                      )}
                    </button>
                  ))}
                </div>
              )}
            </div>
          </div>

          <p className="mt-4 text-sm text-zinc-500">
            {isLoading
              ? 'Loading your diagrams...'
              : hasSearch
                ? `${visibleDiagrams.length} ${visibleDiagrams.length === 1 ? 'result' : 'results'} for “${searchQuery.trim()}”`
                : `${diagrams.length} saved ${diagrams.length === 1 ? 'diagram' : 'diagrams'}`}
          </p>

          <div className="mt-5">
          {isLoading ? (
            <div className="grid gap-x-5 gap-y-6 sm:grid-cols-2 lg:grid-cols-4 xl:grid-cols-5">
              {Array.from({ length: 5 }).map((_, index) => (
                <div key={index} className="animate-pulse">
                  <div className={`${DRAWIO_CANVAS_PREVIEW_ASPECT_CLASS} rounded-lg border border-stone-200 bg-white shadow-sm`} />
                  <div className="mt-3 h-4 w-3/4 rounded-full bg-zinc-200" />
                  <div className="mt-2 h-3 w-1/2 rounded-full bg-stone-100" />
                </div>
              ))}
            </div>
          ) : (
            <div className="grid gap-x-5 gap-y-6 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
              {/* Hide the create tile while searching so results read as a clean set. */}
              {!hasSearch && (
                <article className="group relative min-w-0">
                  <button
                    type="button"
                    onClick={startNewDiagram}
                    className="block w-full cursor-pointer text-left focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-zinc-700/20"
                    aria-label="Create new diagram"
                  >
                    <div className="relative overflow-hidden rounded-xl border-2 border-dashed border-stone-300 bg-stone-50 text-center transition group-hover:border-zinc-400 group-hover:bg-stone-100">
                      {/* Match saved-card height while keeping the create content centered over the full tile. */}
                      <div className="flex items-center justify-between px-4 pt-3 opacity-0" aria-hidden="true">
                        <span className="font-mono text-xs lowercase tracking-wide">basic</span>
                      </div>
                      <div className={DRAWIO_CANVAS_PREVIEW_ASPECT_CLASS} aria-hidden="true" />
                      <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 p-6">
                        <span className="flex h-14 w-14 items-center justify-center rounded-2xl bg-zinc-800 text-white shadow-sm transition group-hover:bg-zinc-700" aria-hidden="true">
                          <span className="relative block h-6 w-6">
                            <span className="absolute left-1/2 top-1/2 h-0.5 w-5 -translate-x-1/2 -translate-y-1/2 rounded bg-white" />
                            <span className="absolute left-1/2 top-1/2 h-5 w-0.5 -translate-x-1/2 -translate-y-1/2 rounded bg-white" />
                          </span>
                        </span>
                        <span className="text-base font-semibold leading-5 tracking-normal text-zinc-800">Create new</span>
                        <span className="text-sm text-zinc-500">Start blank or ask AI</span>
                      </div>
                    </div>
                  </button>
                </article>
              )}
              {visibleDiagrams.map(diagram => (
                <article
                  key={diagram.diagramId}
                  className="group relative min-w-0"
                >
                  <button
                    type="button"
                    onClick={() => openDiagram(diagram.diagramId)}
                    className="block w-full cursor-pointer overflow-hidden rounded-xl border border-stone-200 bg-white text-left shadow-md transition hover:border-stone-300 hover:shadow-lg focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-zinc-700/20"
                    aria-label={`Open ${diagramTitle(diagram)}`}
                  >
                    {/* Category badge uses the normalized token so old and new diagrams share labels. */}
                    <div className="flex items-center justify-between px-4 pt-3">
                      <span className="text-xs font-medium tracking-normal text-zinc-400">{categoryDisplayLabel(diagram)}</span>
                    </div>
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
                        // Missing thumbnails intentionally render as an empty white preview.
                        null
                      )}
                    </div>
                  </button>

                  <div className="mt-3 flex min-w-0 items-start justify-between gap-2">
                    <button
                      type="button"
                      onClick={() => openDiagram(diagram.diagramId)}
                      className="line-clamp-2 min-w-0 cursor-pointer text-left text-sm font-medium leading-5 tracking-normal text-zinc-800 hover:text-zinc-600"
                    >
                      {diagramTitle(diagram)}
                    </button>
                    <div className="relative shrink-0" data-diagram-action-menu>
                      <button
                        type="button"
                        onClick={() => setOpenMenuId(prev => prev === diagram.diagramId ? null : diagram.diagramId)}
                        className="flex h-8 w-8 cursor-pointer items-center justify-center rounded-lg text-sm font-semibold text-zinc-500 transition hover:bg-stone-100 hover:text-zinc-700"
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
                  {/* Keep card metadata focused on the user-facing updated date. */}
                  <div className="mt-1 min-w-0 text-xs text-zinc-500">
                    <span className="block truncate font-mono">{formatUpdatedAt(diagram.updatedAt)}</span>
                  </div>
                </article>
              ))}
              {visibleDiagrams.length === 0 && (
                <div className="col-span-full rounded-xl border border-dashed border-stone-300 bg-stone-50 px-6 py-16 text-center">
                  <p className="text-sm font-medium text-zinc-600">
                    {hasSearch
                      ? `No diagrams match “${searchQuery.trim()}”.`
                      : activeFilter === 'illustrations'
                        ? 'No illustrations yet.'
                        : 'No diagrams here yet.'}
                  </p>
                  <p className="mt-1 text-sm text-zinc-400">Try a different search or category.</p>
                </div>
              )}
            </div>
          )}
          </div>
        </section>
      </div>
    </main>
  );
}
