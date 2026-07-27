'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { agentApi } from '@/api/agent';
import {
  clearAnonymousWorkspaceImportNotice,
  readAnonymousWorkspaceImportNotice,
  type AnonymousWorkspaceImportNotice,
} from '@/utils/anonymous-workspace-import';
import { DiagramSummaryResponseDTO } from '@/types/api';
import { isDiagramActionMenuTarget, isSortMenuTarget } from '../home-menu-click-away';
import {
  applyDiagramLibraryView,
  categoryDisplayLabel,
  CHARTBOOKS_TAB,
  countByFilter,
  diagramFilterForTab,
  DIAGRAM_FILTERS,
  DIAGRAM_SORTS,
  WORKSPACE_TABS,
  type DiagramFilter,
  type DiagramSortMode,
  type WorkspaceTab,
} from '../diagram-library';
import { ChartbookFolderCard, ChartbookFolderGrid, DIAGRAM_DRAG_MIME } from '@/features/chartbooks/ChartbookFolderGrid';
import { ChartbookCreateDialog } from '@/features/chartbooks/ChartbookCreateDialog';
import { applyChartbookShelfView } from '@/features/chartbooks/chartbook-shelf';
import { useChartbookShelf } from '@/features/chartbooks/use-chartbook-shelf';
import { createMaterialClient } from '@/api/material';
import { WorkspaceHeader } from '@/features/workspace/WorkspaceHeader';
import { useWorkspaceIdentity } from '@/features/workspace/use-workspace-identity';
import { DiagramActionMenu, PencilIcon, TrashIcon } from '@/features/diagrams/DiagramActionMenu';
import { DiagramDeleteDialog } from '@/features/diagrams/DiagramDeleteDialog';
import { API_CONFIG } from '@/config/api-config';
import type { Chartbook } from '@/features/materials/material-types';

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

const diagramTitle = (diagram: DiagramSummaryResponseDTO) => diagram.title || 'Untitled Diagram';
// Keep list preview frames aligned with the landscape Draw.io canvas shape.
const DRAWIO_CANVAS_PREVIEW_ASPECT_CLASS = 'aspect-[16/9] sm:aspect-[4/3]';

export default function Home() {
  const router = useRouter();
  const identity = useWorkspaceIdentity();
  const { ownerId } = identity;
  const [diagrams, setDiagrams] = useState<DiagramSummaryResponseDTO[]>([]);
  const [isLoadingDiagrams, setIsLoadingDiagrams] = useState(true);
  const [diagramError, setDiagramError] = useState('');
  const [openMenuId, setOpenMenuId] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [activeTab, setActiveTab] = useState<WorkspaceTab>('all');
  const [sortMode, setSortMode] = useState<DiagramSortMode>('recent');
  const [isSortMenuOpen, setIsSortMenuOpen] = useState(false);
  const [deletingDiagram, setDeletingDiagram] = useState<DiagramSummaryResponseDTO | null>(null);
  const [isDeletingDiagram, setIsDeletingDiagram] = useState(false);
  const [importNotice, setImportNotice] = useState<AnonymousWorkspaceImportNotice | null>(null);
  const [draggingDiagramId, setDraggingDiagramId] = useState<string | null>(null);
  const [dropTargetDiagramId, setDropTargetDiagramId] = useState<string | null>(null);
  // One naming dialog serves both "new chartbook" and "group these two diagrams".
  const [chartbookRequest, setChartbookRequest] = useState<
    { mode: 'create' } | { mode: 'group'; sourceId: string; targetId: string; suggestedName: string } | null
  >(null);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const chartbookShelf = useChartbookShelf();

  const errorMessage = diagramError || identity.error;
  const isLoading = isLoadingDiagrams && !identity.error;
  const activeFilter = diagramFilterForTab(activeTab);
  const isChartbooksTab = activeTab === CHARTBOOKS_TAB;
  const visibleDiagrams = useMemo(
    () => applyDiagramLibraryView(diagrams, { query: searchQuery, filter: activeFilter, sort: sortMode }),
    [diagrams, searchQuery, activeFilter, sortMode],
  );
  const visibleChartbooks = useMemo(
    () => applyChartbookShelfView(chartbookShelf.chartbooks, searchQuery),
    [chartbookShelf.chartbooks, searchQuery],
  );
  const filterCounts = useMemo(
    () => Object.fromEntries(DIAGRAM_FILTERS.map(({ id }) => [id, countByFilter(diagrams, id)])) as Record<DiagramFilter, number>,
    [diagrams],
  );
  const tabCounts: Record<WorkspaceTab, number> = { ...filterCounts, [CHARTBOOKS_TAB]: chartbookShelf.chartbooks.length };
  const materialClient = useMemo(() => createMaterialClient({ baseUrl: API_CONFIG.BASE_URL }), []);
  // Diagrams offered in the create dialog; a dragged pair is already implied by the gesture.
  const chartbookDiagramOptions = useMemo(() => {
    if (!chartbookRequest) return undefined;
    const implied = new Set(chartbookRequest.mode === 'group' ? [chartbookRequest.sourceId, chartbookRequest.targetId] : []);
    return diagrams
      .filter(diagram => !implied.has(diagram.diagramId))
      .map(diagram => ({
        id: diagram.diagramId,
        title: diagramTitle(diagram),
        caption: categoryDisplayLabel(diagram),
      }));
  }, [chartbookRequest, diagrams]);
  const activeSortLabel = DIAGRAM_SORTS.find(sort => sort.id === sortMode)?.label ?? 'recent';
  const hasSearch = searchQuery.trim().length > 0;

  const groupedDiagramIds = useMemo(
    () => new Set(chartbookShelf.chartbooks.flatMap(chartbook => chartbook.diagramIds)),
    [chartbookShelf.chartbooks],
  );
  // Browsing "All" reads as a folder view, so diagrams that live in a chartbook show inside it.
  // Search stays global — nothing becomes unreachable just because it was filed away.
  const isFolderView = activeTab === 'all' && !hasSearch;
  const gridDiagrams = isFolderView
    ? visibleDiagrams.filter(diagram => !groupedDiagramIds.has(diagram.diagramId))
    : visibleDiagrams;
  // Dragging one card onto another only makes sense where the resulting folder is visible.
  const canGroupByDrag = isFolderView;

  useEffect(() => {
    if (!ownerId) return;

    let cancelled = false;
    agentApi.listDiagrams(ownerId)
      .then(res => {
        if (!cancelled) setDiagrams(res.data || []);
      })
      .catch(() => {
        if (!cancelled) setDiagramError('Failed to load diagrams.');
      })
      .finally(() => {
        if (!cancelled) setIsLoadingDiagrams(false);
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
    // Deep links such as /diagrams#chartbooks open the folder tab straight away.
    const syncTabFromHash = () => {
      if (window.location.hash === `#${CHARTBOOKS_TAB}`) setActiveTab(CHARTBOOKS_TAB);
    };
    syncTabFromHash();
    window.addEventListener('hashchange', syncTabFromHash);
    return () => window.removeEventListener('hashchange', syncTabFromHash);
  }, []);

  useEffect(() => {
    if (!openMenuId && !isSortMenuOpen) return;

    const closeMenuOnOutsidePointerDown = (event: PointerEvent) => {
      // Keep menu actions clickable while allowing the rest of the page to dismiss open menus.
      if (!isDiagramActionMenuTarget(event.target)) setOpenMenuId(null);
      if (!isSortMenuTarget(event.target)) setIsSortMenuOpen(false);
    };

    document.addEventListener('pointerdown', closeMenuOnOutsidePointerDown);
    return () => {
      document.removeEventListener('pointerdown', closeMenuOnOutsidePointerDown);
    };
  }, [openMenuId, isSortMenuOpen]);

  const openDiagram = (diagramId: string) => {
    setOpenMenuId(null);
    router.push(`/drawio?diagramId=${encodeURIComponent(diagramId)}`);
  };

  const startNewDiagram = () => {
    setOpenMenuId(null);
    router.push('/drawio?new=1');
  };

  const closeCardMenus = () => {
    setOpenMenuId(null);
    setIsSortMenuOpen(false);
  };

  const chooseSort = (mode: DiagramSortMode) => {
    setSortMode(mode);
    setIsSortMenuOpen(false);
  };

  const chooseTab = (tab: WorkspaceTab) => {
    setActiveTab(tab);
    setOpenMenuId(null);
    // Keep the chartbooks tab linkable without paying for a route change.
    window.history.replaceState(
      null,
      '',
      tab === CHARTBOOKS_TAB ? `${window.location.pathname}#${CHARTBOOKS_TAB}` : window.location.pathname,
    );
  };

  const dismissImportNotice = () => {
    clearAnonymousWorkspaceImportNotice(typeof window === 'undefined' ? null : window.sessionStorage);
    setImportNotice(null);
  };

  const startDiagramDrag = (event: React.DragEvent, diagram: DiagramSummaryResponseDTO) => {
    setOpenMenuId(null);
    setDraggingDiagramId(diagram.diagramId);
    event.dataTransfer.effectAllowed = 'move';
    event.dataTransfer.setData(DIAGRAM_DRAG_MIME, diagram.diagramId);
  };

  const endDiagramDrag = () => {
    setDraggingDiagramId(null);
    setDropTargetDiagramId(null);
  };

  // Dropping one diagram onto another asks for a folder name before anything is created.
  const groupDiagramsIntoChartbook = (sourceId: string, target: DiagramSummaryResponseDTO) => {
    endDiagramDrag();
    if (!sourceId || sourceId === target.diagramId) return;
    setChartbookRequest({ mode: 'group', sourceId, targetId: target.diagramId, suggestedName: diagramTitle(target) });
  };

  // The dialog owns creation so its optional upload step can target a real chartbook.
  const ensureChartbookForRequest = async (name: string) => {
    const request = chartbookRequest;
    if (!request) return null;
    setDiagramError('');
    const created = request.mode === 'group'
      ? await chartbookShelf.groupDiagrams(name, [request.targetId, request.sourceId])
      : await chartbookShelf.create(name);
    if (!created) setDiagramError('Failed to create the chartbook.');
    return created;
  };

  const finishChartbookRequest = async (chartbook: Chartbook, diagramIds: string[]) => {
    setChartbookRequest(null);
    for (const diagramId of diagramIds) {
      if (!await chartbookShelf.addDiagram(chartbook.chartbookId, diagramId)) {
        setDiagramError('Some diagrams could not be moved into the chartbook.');
      }
    }
  };

  const moveDiagramIntoChartbook = async (chartbook: Chartbook, diagramId: string) => {
    endDiagramDrag();
    setDiagramError('');
    if (!await chartbookShelf.addDiagram(chartbook.chartbookId, diagramId)) {
      setDiagramError('Failed to move the diagram into the chartbook.');
    }
  };

  const renameDiagram = async (diagram: DiagramSummaryResponseDTO) => {
    setOpenMenuId(null);
    const nextTitle = window.prompt('Rename diagram', diagramTitle(diagram));
    if (nextTitle === null) return;

    const title = nextTitle.trim();
    if (!title) return;

    setDiagramError('');
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
      setDiagramError('Failed to rename diagram.');
    }
  };

  const openDeleteDialog = (diagram: DiagramSummaryResponseDTO) => {
    setOpenMenuId(null);
    setDeletingDiagram(diagram);
  };

  const deleteDiagram = async () => {
    if (!deletingDiagram) return;
    setDiagramError('');
    setIsDeletingDiagram(true);
    try {
      const res = await agentApi.deleteDiagram(ownerId, deletingDiagram.diagramId);
      if (!res.data) {
        setDiagramError('Diagram was not deleted.');
        setDeletingDiagram(null);
        return;
      }
      setDiagrams(prev => prev.filter(item => item.diagramId !== deletingDiagram.diagramId));
      setDeletingDiagram(null);
    } catch {
      setDiagramError('Failed to delete diagram.');
      setDeletingDiagram(null);
    } finally {
      setIsDeletingDiagram(false);
    }
  };

  return (
    <main className="app-page text-zinc-800">
      <WorkspaceHeader
        identity={identity}
        onChartbooks={() => chooseTab(CHARTBOOKS_TAB)}
        onMenuOpen={closeCardMenus}
        search={(
          <>
            {/* Client-side search over the fully-loaded workspace list — instant, no round trips. */}
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
              placeholder={isChartbooksTab ? 'Search chartbooks' : 'Search diagrams'}
              aria-label={isChartbooksTab ? 'Search chartbooks' : 'Search diagrams'}
              className="h-11 w-full rounded-xl border border-stone-200 bg-stone-50 pl-10 pr-14 text-sm text-zinc-800 placeholder:text-zinc-400 transition focus:border-zinc-400 focus:bg-white focus:outline-none focus:ring-2 focus:ring-zinc-700/10"
            />
            <kbd className="pointer-events-none absolute right-3 hidden items-center rounded-md border border-stone-200 bg-white px-1.5 py-0.5 font-mono text-xs text-zinc-400 sm:inline-flex">
              ⌘K
            </kbd>
          </>
        )}
      />

      {/* The diagram workspace fills wide screens; responsive gutters keep content off the viewport edge. */}
      <div className="flex w-full flex-col px-4 py-5 sm:px-6 sm:py-7 lg:px-8">
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
            {/* Workspace tabs — diagram-kind filters plus the chartbook folders view. */}
            <div className="flex max-w-full items-center gap-2 overflow-x-auto pb-1" role="tablist" aria-label="Filter workspace by category">
              {WORKSPACE_TABS.map(tab => {
                const isActive = activeTab === tab.id;
                const countReady = tab.id === CHARTBOOKS_TAB ? !chartbookShelf.isLoading : !isLoading;
                return (
                  <button
                    key={tab.id}
                    type="button"
                    role="tab"
                    aria-selected={isActive}
                    onClick={() => chooseTab(tab.id)}
                    className={`shrink-0 rounded-lg px-3.5 py-1.5 text-sm font-semibold transition ${
                      isActive
                        ? 'bg-zinc-800 text-white shadow-sm'
                        : 'text-zinc-500 hover:bg-stone-100 hover:text-zinc-700'
                    }`}
                  >
                    {tab.label}
                    {countReady && (
                      <span className={`ml-1.5 text-xs font-medium ${isActive ? 'text-zinc-300' : 'text-zinc-400'}`}>
                        {tabCounts[tab.id] ?? 0}
                      </span>
                    )}
                  </button>
                );
              })}
            </div>

            {/* Sort control — reorders the loaded list without a server round trip. */}
            <div className={`relative ${isChartbooksTab ? 'hidden' : ''}`} data-sort-menu>
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
            {isChartbooksTab
              ? chartbookShelf.isLoading
                ? 'Loading your chartbooks...'
                : hasSearch
                  ? `${visibleChartbooks.length} ${visibleChartbooks.length === 1 ? 'result' : 'results'} for “${searchQuery.trim()}”`
                  : `${chartbookShelf.chartbooks.length} ${chartbookShelf.chartbooks.length === 1 ? 'chartbook' : 'chartbooks'}`
              : isLoading
                ? 'Loading your diagrams...'
                : hasSearch
                  ? `${visibleDiagrams.length} ${visibleDiagrams.length === 1 ? 'result' : 'results'} for “${searchQuery.trim()}”`
                  : `${diagrams.length} saved ${diagrams.length === 1 ? 'diagram' : 'diagrams'}`}
          </p>
          {canGroupByDrag && gridDiagrams.length > 1 && (
            <p className="mt-1 text-xs text-zinc-400">Drag a diagram onto another to file both into a new chartbook.</p>
          )}

          {/* Chartbook capability and error notices only matter while the folder tab is open. */}
          {isChartbooksTab && chartbookShelf.capabilityMessage && (
            <div className="mt-4 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">
              {chartbookShelf.capabilityMessage}
            </div>
          )}
          {isChartbooksTab && chartbookShelf.message && (
            <div className="mt-4 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">
              {chartbookShelf.message}
            </div>
          )}

          <div className="mt-5">
          {isChartbooksTab ? (
            chartbookShelf.isAvailable || chartbookShelf.isLoading ? (
              <ChartbookFolderGrid
                chartbooks={visibleChartbooks}
                // Hide the create tile while searching so results read as a clean set.
                onCreate={chartbookShelf.isAvailable && !hasSearch ? () => setChartbookRequest({ mode: 'create' }) : undefined}
                onArchive={chartbook => void chartbookShelf.archive(chartbook)}
              />
            ) : (
              <div className="rounded-xl border border-dashed border-stone-300 bg-stone-50 px-6 py-16 text-center">
                <p className="text-sm font-medium text-zinc-600">
                  {chartbookShelf.requiresSignIn ? 'Sign in to use chartbooks.' : 'Chartbooks are unavailable right now.'}
                </p>
                <p className="mt-1 text-sm text-zinc-400">
                  Chartbooks let diagrams and shared library items stay together by topic.
                </p>
              </div>
            )
          ) : (
            <>
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
              {/* Folders share the workspace grid, so a chartbook reads as a tile next to its diagrams. */}
              {isFolderView && visibleChartbooks.map(chartbook => (
                <ChartbookFolderCard
                  key={chartbook.chartbookId}
                  chartbook={chartbook}
                  onDropDiagram={(folder, diagramId) => void moveDiagramIntoChartbook(folder, diagramId)}
                />
              ))}
              {gridDiagrams.map(diagram => {
                const isDropTarget = dropTargetDiagramId === diagram.diagramId;
                return (
                <article
                  key={diagram.diagramId}
                  className={`group relative min-w-0 ${draggingDiagramId === diagram.diagramId ? 'opacity-50' : ''}`}
                  draggable={canGroupByDrag}
                  onDragStart={event => startDiagramDrag(event, diagram)}
                  onDragEnd={endDiagramDrag}
                  onDragOver={event => {
                    if (!canGroupByDrag || !draggingDiagramId || draggingDiagramId === diagram.diagramId) return;
                    event.preventDefault();
                    event.dataTransfer.dropEffect = 'move';
                    setDropTargetDiagramId(diagram.diagramId);
                  }}
                  onDragLeave={() => setDropTargetDiagramId(current => (current === diagram.diagramId ? null : current))}
                  onDrop={event => {
                    if (!canGroupByDrag) return;
                    event.preventDefault();
                    groupDiagramsIntoChartbook(event.dataTransfer.getData(DIAGRAM_DRAG_MIME), diagram);
                  }}
                >
                  {isDropTarget && (
                    <span className="pointer-events-none absolute inset-0 z-10 flex items-center justify-center rounded-xl bg-white/75 text-center text-sm font-semibold text-zinc-800">
                      Drop to create a chartbook
                    </span>
                  )}
                  <button
                    type="button"
                    onClick={() => openDiagram(diagram.diagramId)}
                    className={`block w-full cursor-pointer overflow-hidden rounded-xl border bg-white text-left shadow-md transition focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-zinc-700/20 ${
                      isDropTarget
                        ? 'border-zinc-800 ring-2 ring-zinc-800/20'
                        : 'border-stone-200 hover:border-stone-300 hover:shadow-lg'
                    }`}
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
                    <DiagramActionMenu
                      diagramTitle={diagramTitle(diagram)}
                      isOpen={openMenuId === diagram.diagramId}
                      onToggle={() => setOpenMenuId(previous => previous === diagram.diagramId ? null : diagram.diagramId)}
                      actions={[
                        {
                          label: 'Rename',
                          icon: <PencilIcon />,
                          onSelect: () => void renameDiagram(diagram),
                        },
                        {
                          label: 'Delete',
                          icon: <TrashIcon />,
                          tone: 'danger',
                          onSelect: () => openDeleteDialog(diagram),
                        },
                      ]}
                    />
                  </div>
                  {/* Keep card metadata focused on the user-facing updated date. */}
                  <div className="mt-1 min-w-0 text-xs text-zinc-500">
                    <span className="block truncate font-mono">{formatUpdatedAt(diagram.updatedAt)}</span>
                  </div>
                </article>
                );
              })}
              {gridDiagrams.length === 0 && !(isFolderView && visibleChartbooks.length > 0) && (
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
            </>
          )}
          </div>
        </section>
      </div>

      {deletingDiagram && (
        <DiagramDeleteDialog
          diagramTitle={diagramTitle(deletingDiagram)}
          isDeleting={isDeletingDiagram}
          onCancel={() => setDeletingDiagram(null)}
          onConfirm={() => void deleteDiagram()}
        />
      )}

      {chartbookRequest && (
        <ChartbookCreateDialog
          hint={chartbookRequest.mode === 'group'
            ? 'Both diagrams move into this chartbook.'
            : 'Keep diagrams and shared files together by topic.'}
          defaultName={chartbookRequest.mode === 'group' ? chartbookRequest.suggestedName : ''}
          diagramOptions={chartbookDiagramOptions}
          uploadTools={chartbookShelf.capabilities?.upload === 'AVAILABLE'
            ? { client: materialClient, acceptedMimeTypes: chartbookShelf.capabilities.acceptedMimeTypes }
            : undefined}
          ensureChartbook={ensureChartbookForRequest}
          onCancel={() => setChartbookRequest(null)}
          onFinish={(chartbook, diagramIds) => void finishChartbookRequest(chartbook, diagramIds)}
        />
      )}
    </main>
  );
}
