'use client';

import Link from 'next/link';
import { materialDetailsHref } from '@/utils/app-routes';
import { filesPanelStatusLabel } from '../files/files-panel-model';
import type { MaterialCatalogCard } from '../materials/material-types';

const formatBytes = (bytes?: number) => {
  if (bytes === undefined) return '—';
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB'];
  let value = bytes / 1024;
  let unitIndex = 0;
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }
  return `${value < 10 ? value.toFixed(1) : Math.round(value)} ${units[unitIndex]}`;
};

const formatAddedAt = (value?: string) => {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short', year: 'numeric' }).format(date);
};

const fileExtension = (displayName: string) => displayName.split('.').pop()?.slice(0, 4).toUpperCase() || 'FILE';

/** Shared-file rows for a chartbook: name, size, added date and processing state. */
export const ChartbookFileList = ({
  files,
  byteSizes,
  onRemove,
}: {
  files: MaterialCatalogCard[];
  /** materialId → latest version size; absent entries render as "—". */
  byteSizes: Record<string, number>;
  onRemove?: (file: MaterialCatalogCard) => void;
}) => (
  <ul className="divide-y divide-stone-200 overflow-hidden rounded-xl border border-stone-200 bg-white">
    <li className="hidden items-center gap-4 bg-stone-50 px-4 py-2 text-xs font-medium text-zinc-500 sm:flex">
      <span className="min-w-0 flex-1">Name</span>
      <span className="w-20 text-right">Size</span>
      <span className="w-24 text-right">Pages</span>
      <span className="w-28 text-right">Added</span>
      <span className="w-24 text-right">Status</span>
      {onRemove && <span className="w-16" aria-hidden="true" />}
    </li>
    {files.map(file => (
      <li key={file.materialId} className="flex flex-wrap items-center gap-x-4 gap-y-1 px-4 py-3 transition hover:bg-stone-50">
        <span className="flex min-w-0 flex-1 items-center gap-3">
          <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-stone-100 text-[10px] font-semibold text-zinc-500" aria-hidden="true">
            {fileExtension(file.displayName)}
          </span>
          <Link
            href={materialDetailsHref(file.materialId)}
            className="min-w-0 flex-1 truncate text-sm font-medium text-zinc-800 hover:text-zinc-600"
          >
            {file.displayName}
          </Link>
        </span>
        <span className="w-20 text-right font-mono text-xs text-zinc-500">{formatBytes(byteSizes[file.materialId])}</span>
        <span className="w-24 text-right font-mono text-xs text-zinc-500">
          {file.pageCount ? `${file.pageCount}` : '—'}
        </span>
        <span className="w-28 text-right font-mono text-xs text-zinc-500">{formatAddedAt(file.updatedAt)}</span>
        <span className="w-24 text-right text-xs text-zinc-500">{filesPanelStatusLabel(file.processingStatus)}</span>
        {onRemove && (
          <span className="w-16 text-right">
            <button
              type="button"
              onClick={() => onRemove(file)}
              className="rounded-lg px-2 py-1 text-xs font-medium text-zinc-500 transition hover:bg-stone-100 hover:text-rose-700"
            >
              Remove
            </button>
          </span>
        )}
      </li>
    ))}
  </ul>
);
