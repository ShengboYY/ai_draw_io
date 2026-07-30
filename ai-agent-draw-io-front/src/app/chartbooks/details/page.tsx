'use client';

import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { agentApi } from '@/api/agent';
import { API_CONFIG } from '@/config/api-config';
import { createChartbookClient } from '@/api/chartbook';
import { createMaterialClient } from '@/api/material';
import { MaterialUploader, type MaterialUploaderHandle } from '@/features/materials/MaterialUploader';
import { createMaterialCapabilitiesClient } from '@/api/material-capabilities';
import { materialAccessMessage, materialCapabilityMessage } from '@/features/materials/library-view';
import { filesPanelStatusLabel } from '@/features/files/files-panel-model';
import { ChartbookPickerDialog } from '@/features/chartbooks/ChartbookPickerDialog';
import { ChartbookFileList } from '@/features/chartbooks/ChartbookFileList';
import { ChartbookRenameDialog } from '@/features/chartbooks/ChartbookRenameDialog';
import { DiagramRenameDialog } from '@/features/chartbooks/DiagramRenameDialog';
import { DiagramMoveDialog, MY_DIAGRAMS_DESTINATION } from '@/features/chartbooks/DiagramMoveDialog';
import {
  ChevronRightIcon,
  DiagramActionMenu,
  MoveIcon,
  PencilIcon,
  TrashIcon,
} from '@/features/diagrams/DiagramActionMenu';
import { DiagramDeleteDialog } from '@/features/diagrams/DiagramDeleteDialog';
import { WorkspaceHeader } from '@/features/workspace/WorkspaceHeader';
import { useWorkspaceIdentity } from '@/features/workspace/use-workspace-identity';
import { categoryDisplayLabel } from '@/app/diagram-library';
import { isDiagramActionMenuTarget } from '@/app/home-menu-click-away';
import type { DiagramSummaryResponseDTO } from '@/types/api';
import type { Chartbook, MaterialCapabilities, MaterialCatalogCard } from '@/features/materials/material-types';

type ChartbookTab = 'diagrams' | 'files';

const formatUpdatedAt = (value?: string) => {
  if (!value) return 'No updates yet';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return 'No updates yet';
  return new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short', year: 'numeric' }).format(date);
};

const diagramTitle = (diagram: DiagramSummaryResponseDTO) => diagram.title || 'Untitled Diagram';

const iconProps = {
  viewBox: '0 0 24 24',
  className: 'h-[18px] w-[18px]',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.7,
  strokeLinecap: 'round',
  strokeLinejoin: 'round',
  'aria-hidden': true,
} as const;

// A downward arrow into a tray matches the conventional import action.
const ImportIcon = () => (
  <svg {...iconProps}><path d="M12 4v10m0 0 3.5-3.5M12 14l-3.5-3.5" /><path d="M4 15v3.5A1.5 1.5 0 0 0 5.5 20h13a1.5 1.5 0 0 0 1.5-1.5V15" /></svg>
);
const UploadIcon = () => (
  <svg {...iconProps}><path d="M12 15V4m0 0 3.5 3.5M12 4 8.5 7.5" /><path d="M4 15v3.5A1.5 1.5 0 0 0 5.5 20h13a1.5 1.5 0 0 0 1.5-1.5V15" /></svg>
);
const LibraryIcon = () => (
  <svg {...iconProps}><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" /><path d="M6.5 3H20v18H6.5A2.5 2.5 0 0 1 4 18.5v-13A2.5 2.5 0 0 1 6.5 3Z" /></svg>
);
// Icon-only affordances share one hit area and rely on their label for meaning.
const ICON_BUTTON_CLASS = 'flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-stone-200 bg-white text-zinc-600 shadow-sm transition hover:bg-stone-50 hover:text-zinc-900 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-zinc-700/20 disabled:opacity-40';
// Keep list preview frames aligned with the landscape Draw.io canvas shape.
const DRAWIO_CANVAS_PREVIEW_ASPECT_CLASS = 'aspect-[16/9] sm:aspect-[4/3]';

function ChartbookDetailsContent() {
  const chartbookId = useSearchParams().get('chartbookId') || '';
  const router = useRouter();
  const identity = useWorkspaceIdentity();
  const { ownerId } = identity;
  const chartbookClient = useMemo(() => createChartbookClient({ baseUrl: API_CONFIG.BASE_URL }), []);
  const materialClient = useMemo(() => createMaterialClient({ baseUrl: API_CONFIG.BASE_URL }), []);
  const capabilitiesClient = useMemo(() => createMaterialCapabilitiesClient({ baseUrl: API_CONFIG.BASE_URL }), []);

  const [capabilities, setCapabilities] = useState<MaterialCapabilities | null>(null);
  const [chartbook, setChartbook] = useState<Chartbook | null>(null);
  const [files, setFiles] = useState<MaterialCatalogCard[]>([]);
  const [fileSizes, setFileSizes] = useState<Record<string, number>>({});
  const [workspaceDiagrams, setWorkspaceDiagrams] = useState<DiagramSummaryResponseDTO[]>([]);
  const [activeTab, setActiveTab] = useState<ChartbookTab>('diagrams');
  const [openMenuId, setOpenMenuId] = useState<string | null>(null);
  const [isAddingDiagrams, setIsAddingDiagrams] = useState(false);
  const [isAddingLibraryFiles, setIsAddingLibraryFiles] = useState(false);
  const [libraryFiles, setLibraryFiles] = useState<MaterialCatalogCard[]>([]);
  const [pendingUploads, setPendingUploads] = useState<{ uploadId: string; fileName: string; state: string }[]>([]);
  const [isRenaming, setIsRenaming] = useState(false);
  const [renamingDiagram, setRenamingDiagram] = useState<DiagramSummaryResponseDTO | null>(null);
  const [movingDiagram, setMovingDiagram] = useState<DiagramSummaryResponseDTO | null>(null);
  const [deletingDiagram, setDeletingDiagram] = useState<DiagramSummaryResponseDTO | null>(null);
  const [moveTargets, setMoveTargets] = useState<Chartbook[]>([]);
  const [moveTargetsError, setMoveTargetsError] = useState<string | null>(null);
  const [isLoadingMoveTargets, setIsLoadingMoveTargets] = useState(false);
  const [isDiagramActionPending, setIsDiagramActionPending] = useState(false);
  const uploaderRef = useRef<MaterialUploaderHandle>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  const load = useCallback(async () => {
    try {
      const currentCapabilities = await capabilitiesClient.get();
      setCapabilities(currentCapabilities);
      if (currentCapabilities.catalog !== 'AVAILABLE') return;
      const [currentChartbook, filePage] = await Promise.all([
        chartbookClient.details(chartbookId),
        materialClient.listScope('CHARTBOOK', chartbookId, { lifecycleState: 'ACTIVE', limit: 100 }),
      ]);
      setChartbook(currentChartbook);
      setFiles(filePage.items);
      setMessage(null);
      // Sizes only live on the version records, so read them per file for the list column.
      const sizes = await Promise.all(filePage.items.slice(0, 50).map(async file => {
        try {
          const details = await materialClient.details(file.materialId);
          const latest = details.versions.find(version => version.versionId === file.latestVersionId)
            || [...details.versions].sort((a, b) => b.versionNo - a.versionNo)[0];
          return [file.materialId, latest?.byteSize] as const;
        } catch {
          return [file.materialId, undefined] as const;
        }
      }));
      setFileSizes(Object.fromEntries(sizes.filter(([, size]) => size !== undefined) as [string, number][]));
    } catch {
      setMessage(materialAccessMessage('HTTP_ERROR'));
    } finally {
      setIsLoading(false);
    }
  }, [capabilitiesClient, chartbookClient, chartbookId, materialClient]);

  useEffect(() => {
    const timeoutId = window.setTimeout(() => { void load(); }, 0);
    return () => window.clearTimeout(timeoutId);
  }, [load]);

  useEffect(() => {
    if (!ownerId) return;
    let cancelled = false;
    agentApi.listDiagrams(ownerId)
      .then(res => {
        if (!cancelled) setWorkspaceDiagrams(res.data || []);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [ownerId]);

  useEffect(() => {
    if (!openMenuId) return;
    const closeMenuOnOutsidePointerDown = (event: PointerEvent) => {
      if (!isDiagramActionMenuTarget(event.target)) setOpenMenuId(null);
    };
    const closeMenuOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpenMenuId(null);
    };
    document.addEventListener('pointerdown', closeMenuOnOutsidePointerDown);
    document.addEventListener('keydown', closeMenuOnEscape);
    return () => {
      document.removeEventListener('pointerdown', closeMenuOnOutsidePointerDown);
      document.removeEventListener('keydown', closeMenuOnEscape);
    };
  }, [openMenuId]);

  const chartbookDiagramIds = useMemo(() => chartbook?.diagramIds || [], [chartbook]);
  // Order the chartbook's diagrams the way the workspace lists them, newest first.
  const diagrams = useMemo(() => {
    const inChartbook = new Set(chartbookDiagramIds);
    const known = workspaceDiagrams.filter(diagram => inChartbook.has(diagram.diagramId));
    const knownIds = new Set(known.map(diagram => diagram.diagramId));
    // Ids the workspace list has not returned still deserve a row, so the chartbook stays honest.
    const unknown = chartbookDiagramIds
      .filter(id => !knownIds.has(id))
      .map(id => ({ diagramId: id } as DiagramSummaryResponseDTO));
    return [...known, ...unknown];
  }, [chartbookDiagramIds, workspaceDiagrams]);

  const addableDiagrams = useMemo(() => {
    const inChartbook = new Set(chartbookDiagramIds);
    return workspaceDiagrams
      .filter(diagram => !inChartbook.has(diagram.diagramId))
      .map(diagram => ({
        id: diagram.diagramId,
        title: diagramTitle(diagram),
        caption: categoryDisplayLabel(diagram),
      }));
  }, [chartbookDiagramIds, workspaceDiagrams]);

  const addableLibraryFiles = useMemo(() => {
    const shared = new Set(files.map(file => file.materialId));
    return libraryFiles
      .filter(file => !shared.has(file.materialId))
      .map(file => ({ id: file.materialId, title: file.displayName, caption: file.kind }));
  }, [files, libraryFiles]);

  const goBack = () => {
    // Return to whichever surface opened this chartbook, falling back to the workspace.
    if (window.history.length > 1) router.back();
    else router.push('/diagrams#chartbooks');
  };

  const rename = async (name: string) => {
    setIsRenaming(false);
    try {
      setChartbook(await chartbookClient.rename(chartbookId, name));
    } catch {
      setMessage(materialAccessMessage('HTTP_ERROR'));
    }
  };

  const addDiagrams = async (diagramIds: string[]) => {
    setIsAddingDiagrams(false);
    try {
      let updated = chartbook;
      for (const diagramId of diagramIds) {
        updated = await chartbookClient.assignDiagram(diagramId, chartbookId);
      }
      setChartbook(updated);
    } catch {
      setMessage(materialAccessMessage('HTTP_ERROR'));
    }
  };

  const openLibraryPicker = async () => {
    setIsAddingLibraryFiles(true);
    try {
      const page = await materialClient.list({ lifecycleState: 'ACTIVE', limit: 100 });
      setLibraryFiles(page.items);
    } catch {
      setMessage(materialAccessMessage('HTTP_ERROR'));
    }
  };

  const addLibraryFiles = async (materialIds: string[]) => {
    setIsAddingLibraryFiles(false);
    try {
      for (const materialId of materialIds) {
        await chartbookClient.addFile(chartbookId, materialId, globalThis.crypto.randomUUID());
      }
      await load();
    } catch {
      setMessage(materialAccessMessage('HTTP_ERROR'));
    }
  };

  const openRenameDiagram = (diagram: DiagramSummaryResponseDTO) => {
    setOpenMenuId(null);
    setRenamingDiagram(diagram);
  };

  const renameDiagram = async (name: string) => {
    if (!renamingDiagram || !ownerId) return;
    setIsDiagramActionPending(true);
    try {
      const response = await agentApi.renameDiagram(ownerId, renamingDiagram.diagramId, name);
      const updated = response.data;
      // The chartbook rows are derived from this workspace list, so update the shared source.
      setWorkspaceDiagrams(previous => {
        const renamed = { ...renamingDiagram, ...updated, title: updated?.title || name };
        const isKnown = previous.some(diagram => diagram.diagramId === renamingDiagram.diagramId);
        return isKnown
          ? previous.map(diagram => diagram.diagramId === renamingDiagram.diagramId ? renamed : diagram)
          : [...previous, renamed];
      });
      setRenamingDiagram(null);
    } catch {
      setMessage('Unable to rename that diagram. Please try again.');
    } finally {
      setIsDiagramActionPending(false);
    }
  };

  const openMoveDiagram = async (diagram: DiagramSummaryResponseDTO) => {
    setOpenMenuId(null);
    setMovingDiagram(diagram);
    setMoveTargets([]);
    setMoveTargetsError(null);
    setIsLoadingMoveTargets(true);
    try {
      const available = await chartbookClient.list();
      setMoveTargets(available.filter(item => item.chartbookId !== chartbookId));
    } catch {
      // Moving back to My diagrams still works when destination discovery fails.
      setMoveTargetsError('Other chartbooks could not be loaded. You can still move this diagram to My diagrams.');
    } finally {
      setIsLoadingMoveTargets(false);
    }
  };

  const moveDiagram = async (destinationId: string) => {
    if (!movingDiagram) return;
    setIsDiagramActionPending(true);
    try {
      if (destinationId === MY_DIAGRAMS_DESTINATION) {
        await chartbookClient.removeDiagram(movingDiagram.diagramId);
      } else {
        await chartbookClient.assignDiagram(movingDiagram.diagramId, destinationId);
      }
      // Assignment is exclusive, so either destination removes the card from this view.
      setChartbook(previous => previous
        ? { ...previous, diagramIds: previous.diagramIds.filter(id => id !== movingDiagram.diagramId) }
        : previous);
      setMovingDiagram(null);
    } catch {
      setMessage('Unable to move that diagram. Please try again.');
    } finally {
      setIsDiagramActionPending(false);
    }
  };

  const openDeleteDialog = (diagram: DiagramSummaryResponseDTO) => {
    setOpenMenuId(null);
    setDeletingDiagram(diagram);
  };

  const deleteDiagram = async () => {
    if (!deletingDiagram) return;
    if (!ownerId) {
      setMessage('Unable to delete that diagram. Please try again.');
      setDeletingDiagram(null);
      return;
    }

    setIsDiagramActionPending(true);
    try {
      const response = await agentApi.deleteDiagram(ownerId, deletingDiagram.diagramId);
      if (!response.data) {
        setMessage('Diagram was not deleted.');
        setDeletingDiagram(null);
        return;
      }
      // Deletion removes the diagram itself, so clear it from both sources backing this view.
      setWorkspaceDiagrams(previous => previous.filter(item => item.diagramId !== deletingDiagram.diagramId));
      setChartbook(previous => previous
        ? { ...previous, diagramIds: previous.diagramIds.filter(id => id !== deletingDiagram.diagramId) }
        : previous);
      setDeletingDiagram(null);
    } catch {
      setMessage('Unable to delete that diagram. Please try again.');
      setDeletingDiagram(null);
    } finally {
      setIsDiagramActionPending(false);
    }
  };

  const removeFile = async (file: MaterialCatalogCard) => {
    if (!window.confirm(`Remove "${file.displayName}" from this chartbook?`)) return;
    try {
      await chartbookClient.removeFile(chartbookId, file.materialId, globalThis.crypto.randomUUID());
      setFiles(previous => previous.filter(item => item.materialId !== file.materialId));
    } catch {
      setMessage(materialAccessMessage('HTTP_ERROR'));
    }
  };

  const capabilityMessage = capabilities && materialCapabilityMessage(capabilities);
  const tabs: { id: ChartbookTab; label: string; count: number }[] = [
    { id: 'diagrams', label: 'Diagrams', count: diagrams.length },
    { id: 'files', label: 'Shared files', count: files.length },
  ];

  return (
    <main className="app-page text-zinc-800">
      {/* Same workspace chrome as My diagrams, minus the search box. */}
      <WorkspaceHeader identity={identity} onMenuOpen={() => setOpenMenuId(null)} />

      <div className="flex w-full flex-col px-4 py-5 sm:px-6 sm:py-7 lg:px-8">
        {capabilityMessage && (
          <div className="mb-6 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">{capabilityMessage}</div>
        )}
        {message && (
          <div className="mb-6 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{message}</div>
        )}

        <section className="flex-1">
          <button
            type="button"
            onClick={goBack}
            className="text-sm font-medium text-zinc-500 transition hover:text-zinc-700"
          >
            ← Back
          </button>

          <div className="mt-1 flex flex-wrap items-center gap-3">
            <h1 className="text-2xl font-bold tracking-tight text-zinc-900 sm:text-4xl">
              {chartbook?.name || (isLoading ? 'Chartbook' : 'Chartbook unavailable')}
            </h1>
            {chartbook && (
              <button
                type="button"
                onClick={() => setIsRenaming(true)}
                aria-label="Rename chartbook"
                title="Rename"
                className="flex h-8 w-8 items-center justify-center rounded-lg text-zinc-400 transition hover:bg-stone-100 hover:text-zinc-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-zinc-700/20"
              >
                <PencilIcon />
              </button>
            )}
          </div>

          <div className="mt-7 flex flex-col items-stretch gap-3 border-b border-stone-200 pb-4 sm:flex-row sm:items-center sm:justify-between sm:gap-4">
            <div className="flex max-w-full items-center gap-2 overflow-x-auto pb-1" role="tablist" aria-label="Chartbook contents">
              {tabs.map(tab => {
                const isActive = activeTab === tab.id;
                return (
                  <button
                    key={tab.id}
                    type="button"
                    role="tab"
                    aria-selected={isActive}
                    onClick={() => setActiveTab(tab.id)}
                    className={`shrink-0 rounded-lg px-3.5 py-1.5 text-sm font-semibold transition ${
                      isActive ? 'bg-zinc-800 text-white shadow-sm' : 'text-zinc-500 hover:bg-stone-100 hover:text-zinc-700'
                    }`}
                  >
                    {tab.label}
                    {!isLoading && (
                      <span className={`ml-1.5 text-xs font-medium ${isActive ? 'text-zinc-300' : 'text-zinc-400'}`}>{tab.count}</span>
                    )}
                  </button>
                );
              })}
            </div>

            {chartbook && (
              <div className="flex shrink-0 items-center gap-2 self-start sm:self-auto">
                {activeTab === 'diagrams' ? (
                  <button
                    type="button"
                    onClick={() => setIsAddingDiagrams(true)}
                    aria-label="Import from My diagrams"
                    title="Import from My diagrams"
                    className={ICON_BUTTON_CLASS}
                  >
                    <ImportIcon />
                  </button>
                ) : (
                  <>
                    <button
                      type="button"
                      onClick={() => uploaderRef.current?.openPicker()}
                      disabled={capabilities?.upload !== 'AVAILABLE'}
                      aria-label="Upload files from this device"
                      title="Upload from this device"
                      className={ICON_BUTTON_CLASS}
                    >
                      <UploadIcon />
                    </button>
                    <button
                      type="button"
                      onClick={() => void openLibraryPicker()}
                      aria-label="Add files from the library"
                      title="Add from library"
                      className={ICON_BUTTON_CLASS}
                    >
                      <LibraryIcon />
                    </button>
                  </>
                )}
              </div>
            )}
          </div>

          {activeTab === 'diagrams' ? (
            <>
              <p className="mt-4 text-sm text-zinc-500">
                {isLoading
                  ? 'Loading this chartbook...'
                  : `${diagrams.length} ${diagrams.length === 1 ? 'diagram' : 'diagrams'}`}
              </p>
              <div className="mt-5 grid gap-x-5 gap-y-6 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
                {/* Creating from inside a chartbook files the new diagram here on its first save. */}
                <article className="group relative min-w-0">
                  <button
                    type="button"
                    onClick={() => router.push(`/drawio?new=1&chartbookId=${encodeURIComponent(chartbookId)}`)}
                    className="block w-full cursor-pointer text-left focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-zinc-700/20"
                    aria-label="Create new diagram"
                  >
                    <div className="relative overflow-hidden rounded-xl border-2 border-dashed border-stone-300 bg-stone-50 text-center transition group-hover:border-zinc-400 group-hover:bg-stone-100">
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

                {diagrams.map(diagram => (
                  <article key={diagram.diagramId} className="group relative min-w-0">
                    <button
                      type="button"
                      onClick={() => router.push(`/drawio?diagramId=${encodeURIComponent(diagram.diagramId)}`)}
                      className="block w-full cursor-pointer overflow-hidden rounded-xl border border-stone-200 bg-white text-left shadow-md transition hover:border-stone-300 hover:shadow-lg focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-zinc-700/20"
                      aria-label={`Open ${diagramTitle(diagram)}`}
                    >
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
                        ) : null}
                      </div>
                    </button>

                    <div className="mt-3 flex min-w-0 items-start justify-between gap-2">
                      <button
                        type="button"
                        onClick={() => router.push(`/drawio?diagramId=${encodeURIComponent(diagram.diagramId)}`)}
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
                            onSelect: () => openRenameDiagram(diagram),
                          },
                          {
                            label: 'Move',
                            icon: <MoveIcon />,
                            trailingIcon: <ChevronRightIcon />,
                            onSelect: () => void openMoveDiagram(diagram),
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
                    <div className="mt-1 min-w-0 text-xs text-zinc-500">
                      <span className="block truncate font-mono">{formatUpdatedAt(diagram.updatedAt)}</span>
                    </div>
                  </article>
                ))}
              </div>
            </>
          ) : (
            <>
              <p className="mt-4 text-sm text-zinc-500">
                {isLoading ? 'Loading files...' : `${files.length} ${files.length === 1 ? 'file' : 'files'}`}
              </p>
              {/* The picker is driven by the toolbar icon, so the uploader itself stays chrome-free. */}
              {capabilities?.upload === 'AVAILABLE' && (
                <MaterialUploader
                  ref={uploaderRef}
                  client={materialClient}
                  target={{ scopeType: 'CHARTBOOK', scopeId: chartbookId, retentionClass: 'RETAINED' }}
                  acceptedMimeTypes={capabilities.acceptedMimeTypes}
                  variant="compact"
                  showTrigger={false}
                  onUploadInitiated={upload => setPendingUploads(previous => [...previous, { ...upload, state: 'UPLOADING' }])}
                  onUploadStatus={upload => setPendingUploads(previous => previous.map(item => (
                    item.uploadId === upload.uploadId ? { ...item, state: upload.state } : item
                  )))}
                  onReady={() => {
                    setPendingUploads(previous => previous.filter(item => filesPanelStatusLabel(item.state) !== 'Ready'));
                    void load();
                  }}
                />
              )}
              <div className="mt-5">
                {pendingUploads.length > 0 && (
                  <ul className="mb-3 space-y-2">
                    {pendingUploads.map(upload => (
                      <li key={upload.uploadId} className="flex items-center justify-between gap-3 rounded-xl border border-dashed border-stone-300 bg-stone-50 px-4 py-3 text-sm">
                        <span className="min-w-0 truncate font-medium text-zinc-700">{upload.fileName}</span>
                        <span className="shrink-0 text-xs text-zinc-500">{filesPanelStatusLabel(upload.state)}</span>
                      </li>
                    ))}
                  </ul>
                )}
                {files.length > 0 ? (
                  <ChartbookFileList files={files} byteSizes={fileSizes} onRemove={file => void removeFile(file)} />
                ) : (
                  !isLoading && pendingUploads.length === 0 && (
                    <div className="rounded-xl border border-dashed border-stone-300 bg-stone-50 px-6 py-16 text-center">
                      <p className="text-sm font-medium text-zinc-600">No shared files yet.</p>
                      <p className="mt-1 text-sm text-zinc-400">Upload from this device or add an item from your library.</p>
                    </div>
                  )
                )}
              </div>
            </>
          )}
        </section>
      </div>

      {isRenaming && chartbook && (
        <ChartbookRenameDialog
          currentName={chartbook.name}
          onCancel={() => setIsRenaming(false)}
          onConfirm={name => void rename(name)}
        />
      )}

      {renamingDiagram && (
        <DiagramRenameDialog
          currentName={diagramTitle(renamingDiagram)}
          isSaving={isDiagramActionPending}
          onCancel={() => setRenamingDiagram(null)}
          onConfirm={name => void renameDiagram(name)}
        />
      )}

      {deletingDiagram && (
        <DiagramDeleteDialog
          diagramTitle={diagramTitle(deletingDiagram)}
          isDeleting={isDiagramActionPending}
          onCancel={() => setDeletingDiagram(null)}
          onConfirm={() => void deleteDiagram()}
        />
      )}

      {movingDiagram && chartbook && (
        <DiagramMoveDialog
          chartbooks={moveTargets}
          isLoading={isLoadingMoveTargets}
          isMoving={isDiagramActionPending}
          loadError={moveTargetsError}
          onCancel={() => setMovingDiagram(null)}
          onConfirm={destinationId => void moveDiagram(destinationId)}
        />
      )}

      {isAddingDiagrams && (
        <ChartbookPickerDialog
          heading="Import from My diagrams"
          hint="Diagrams move here from wherever they live now."
          confirmLabel="Move in"
          emptyMessage="Every diagram is already in this chartbook."
          options={addableDiagrams}
          onCancel={() => setIsAddingDiagrams(false)}
          onConfirm={diagramIds => void addDiagrams(diagramIds)}
        />
      )}

      {isAddingLibraryFiles && (
        <ChartbookPickerDialog
          heading="Add from library"
          hint="Library items stay in your library and are shared with this chartbook."
          confirmLabel="Add"
          emptyMessage="Every library item is already shared here."
          options={addableLibraryFiles}
          onCancel={() => setIsAddingLibraryFiles(false)}
          onConfirm={materialIds => void addLibraryFiles(materialIds)}
        />
      )}
    </main>
  );
}

export default function ChartbookDetailsPage() {
  return (
    <Suspense fallback={<main className="min-h-screen bg-stone-50" />}>
      <ChartbookDetailsContent />
    </Suspense>
  );
}
