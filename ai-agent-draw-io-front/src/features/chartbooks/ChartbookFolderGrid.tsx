'use client';

import Link from 'next/link';
import { useState } from 'react';
import { chartbookDetailsHref } from '@/utils/app-routes';
import { chartbookFolderSummary } from './chartbook-shelf';
import type { Chartbook } from '../materials/material-types';

// Diagram cards publish their id under this type so folders can accept the same drag.
export const DIAGRAM_DRAG_MIME = 'application/x-freedraw-diagram-id';
// Mirrors the diagram card preview frame so folders and diagrams line up in one grid.
const CARD_PREVIEW_ASPECT_CLASS = 'aspect-[16/9] sm:aspect-[4/3]';

const FolderPlusIcon = () => (
  <svg viewBox="0 0 24 24" className="h-6 w-6" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M3 7.5A1.5 1.5 0 0 1 4.5 6h4a1.5 1.5 0 0 1 1.06.44L10.9 7.5h8.6A1.5 1.5 0 0 1 21 9v8.5a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 17.5Z" />
    <path d="M12 11.5v5M9.5 14h5" />
  </svg>
);

/** Create tile shaped like the workspace's "Create new" diagram tile. */
export const ChartbookCreateCard = ({ onCreate }: { onCreate: () => void }) => (
  <article className="group relative min-w-0">
    <button
      type="button"
      onClick={onCreate}
      aria-label="Create new chartbook"
      className="block w-full cursor-pointer text-left focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-zinc-700/20"
    >
      <div className="relative overflow-hidden rounded-xl border-2 border-dashed border-stone-300 bg-stone-50 text-center transition group-hover:border-zinc-400 group-hover:bg-stone-100">
        {/* Match folder-card height while keeping the create content centered over the full tile. */}
        <div className="flex items-center justify-between px-4 pt-3 opacity-0" aria-hidden="true">
          <span className="text-xs">chartbook</span>
        </div>
        <div className={CARD_PREVIEW_ASPECT_CLASS} aria-hidden="true" />
        <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 p-6">
          <span className="flex h-14 w-14 items-center justify-center rounded-2xl bg-zinc-800 text-white shadow-sm transition group-hover:bg-zinc-700" aria-hidden="true">
            <FolderPlusIcon />
          </span>
          <span className="text-base font-semibold leading-5 tracking-normal text-zinc-800">New chartbook</span>
          <span className="text-sm text-zinc-500">Keep diagrams and files together</span>
        </div>
      </div>
    </button>
  </article>
);

const FolderIcon = () => (
  <svg viewBox="0 0 24 24" className="h-7 w-7" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M3 7.5A1.5 1.5 0 0 1 4.5 6h4a1.5 1.5 0 0 1 1.06.44L10.9 7.5h8.6A1.5 1.5 0 0 1 21 9v8.5a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 17.5Z" />
  </svg>
);

/**
 * Folder card for one chartbook, shaped like a diagram card so both can share the
 * workspace grid. Opens the existing chartbook page on click.
 */
export const ChartbookFolderCard = ({
  chartbook,
  onArchive,
  onDropDiagram,
}: {
  chartbook: Chartbook;
  onArchive?: (chartbook: Chartbook) => void;
  onDropDiagram?: (chartbook: Chartbook, diagramId: string) => void;
}) => {
  const [isDropTarget, setIsDropTarget] = useState(false);
  const href = chartbookDetailsHref(chartbook.chartbookId);

  const acceptsDrag = (event: React.DragEvent) =>
    Boolean(onDropDiagram) && event.dataTransfer.types.includes(DIAGRAM_DRAG_MIME);

  return (
    <article
      className="group relative min-w-0"
      onDragOver={event => {
        if (!acceptsDrag(event)) return;
        event.preventDefault();
        event.dataTransfer.dropEffect = 'move';
        setIsDropTarget(true);
      }}
      onDragLeave={() => setIsDropTarget(false)}
      onDrop={event => {
        if (!acceptsDrag(event)) return;
        event.preventDefault();
        setIsDropTarget(false);
        const diagramId = event.dataTransfer.getData(DIAGRAM_DRAG_MIME);
        if (diagramId) onDropDiagram?.(chartbook, diagramId);
      }}
    >
      <Link
        href={href}
        aria-label={`Open ${chartbook.name}`}
        className={`block w-full overflow-hidden rounded-xl border bg-white text-left shadow-md transition focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-zinc-700/20 ${
          isDropTarget
            ? 'border-zinc-800 ring-2 ring-zinc-800/20'
            : 'border-stone-200 hover:border-stone-300 hover:shadow-lg'
        }`}
      >
        <div className="flex items-center justify-between px-4 pt-3">
          <span className="text-xs font-medium tracking-normal text-zinc-400">chartbook</span>
        </div>
        <div className={`${CARD_PREVIEW_ASPECT_CLASS} flex items-center justify-center bg-white`}>
          <span className="flex h-16 w-16 items-center justify-center rounded-2xl bg-stone-100 text-zinc-500 transition group-hover:bg-stone-200" aria-hidden="true">
            <FolderIcon />
          </span>
        </div>
      </Link>

      <div className="mt-3 flex min-w-0 items-start justify-between gap-2">
        <Link
          href={href}
          className="line-clamp-2 min-w-0 text-left text-sm font-medium leading-5 tracking-normal text-zinc-800 hover:text-zinc-600"
        >
          {chartbook.name}
        </Link>
        {onArchive && (
          <button
            type="button"
            onClick={() => onArchive(chartbook)}
            aria-label={`Archive ${chartbook.name}`}
            title="Archive"
            className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-zinc-500 transition hover:bg-stone-100 hover:text-rose-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-zinc-700/20"
          >
            <svg viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M4 8h16v11a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 19Z" />
              <path d="M3 4.5h18V8H3zM10 12.5h4" />
            </svg>
          </button>
        )}
      </div>
      <div className="mt-1 min-w-0 text-xs text-zinc-500">
        <span className="block truncate">
          {isDropTarget ? 'Drop to move in' : chartbookFolderSummary(chartbook)}
        </span>
      </div>
    </article>
  );
};

/** Standalone folder grid for the Chartbooks tab and the /chartbooks route. */
export const ChartbookFolderGrid = ({
  chartbooks,
  onArchive,
  onCreate,
  onDropDiagram,
}: {
  chartbooks: Chartbook[];
  onArchive?: (chartbook: Chartbook) => void;
  /** Omit to hide the create tile, e.g. while search results are showing. */
  onCreate?: () => void;
  onDropDiagram?: (chartbook: Chartbook, diagramId: string) => void;
}) => (
  <div className="grid gap-x-5 gap-y-6 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
    {onCreate && <ChartbookCreateCard onCreate={onCreate} />}
    {chartbooks.map(chartbook => (
      <ChartbookFolderCard
        key={chartbook.chartbookId}
        chartbook={chartbook}
        onArchive={onArchive}
        onDropDiagram={onDropDiagram}
      />
    ))}
  </div>
);
