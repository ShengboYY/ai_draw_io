'use client';

import { useEffect, useState } from 'react';
import type { Chartbook } from '../materials/material-types';

export const MY_DIAGRAMS_DESTINATION = '__my_diagrams__';

const DestinationRadio = ({
  checked,
  value,
  title,
  caption,
  icon,
  onChange,
}: {
  checked: boolean;
  value: string;
  title: string;
  caption: string;
  icon: React.ReactNode;
  onChange: (value: string) => void;
}) => (
  <label
    className={`flex cursor-pointer items-center gap-3 rounded-xl border px-3.5 py-3 transition ${
      checked
        ? 'border-zinc-700 bg-zinc-50 ring-1 ring-zinc-700/10'
        : 'border-stone-200 bg-white hover:border-stone-300 hover:bg-stone-50'
    }`}
  >
    <input
      type="radio"
      name="diagram-destination"
      value={value}
      checked={checked}
      onChange={() => onChange(value)}
      className="sr-only"
    />
    <span
      className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-lg ${
        checked ? 'bg-zinc-800 text-white' : 'bg-stone-100 text-zinc-500'
      }`}
      aria-hidden="true"
    >
      {icon}
    </span>
    <span className="min-w-0 flex-1">
      <span className="block truncate text-sm font-semibold text-zinc-800">{title}</span>
      <span className="mt-0.5 block truncate text-xs text-zinc-500">{caption}</span>
    </span>
    <span
      className={`flex h-4 w-4 shrink-0 items-center justify-center rounded-full border ${
        checked ? 'border-zinc-800 bg-zinc-800' : 'border-stone-300 bg-white'
      }`}
      aria-hidden="true"
    >
      {checked && <span className="h-1.5 w-1.5 rounded-full bg-white" />}
    </span>
  </label>
);

const TrayIcon = () => (
  <svg viewBox="0 0 24 24" className="h-[18px] w-[18px]" fill="none" stroke="currentColor" strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round">
    <path d="M4 14.5h4l1.5 2h5l1.5-2h4" />
    <path d="M5.5 5h13L20 14.5V19H4v-4.5Z" />
  </svg>
);

const FolderIcon = () => (
  <svg viewBox="0 0 24 24" className="h-[18px] w-[18px]" fill="none" stroke="currentColor" strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round">
    <path d="M3.5 7.5A1.5 1.5 0 0 1 5 6h3.6a1.5 1.5 0 0 1 1.06.44l1.06 1.06H19A1.5 1.5 0 0 1 20.5 9v8.5A1.5 1.5 0 0 1 19 19H5a1.5 1.5 0 0 1-1.5-1.5Z" />
  </svg>
);

/** Lets a diagram leave its chartbook or move directly into another one. */
export const DiagramMoveDialog = ({
  chartbooks,
  isLoading = false,
  isMoving = false,
  loadError,
  onCancel,
  onConfirm,
}: {
  chartbooks: Chartbook[];
  isLoading?: boolean;
  isMoving?: boolean;
  loadError?: string | null;
  onCancel: () => void;
  onConfirm: (destinationId: string) => void;
}) => {
  const [destinationId, setDestinationId] = useState(MY_DIAGRAMS_DESTINATION);

  useEffect(() => {
    const cancelOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !isMoving) onCancel();
    };
    window.addEventListener('keydown', cancelOnEscape);
    return () => window.removeEventListener('keydown', cancelOnEscape);
  }, [isMoving, onCancel]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center overflow-y-auto bg-zinc-900/25 px-4 py-8 backdrop-blur-sm"
      onMouseDown={event => {
        if (event.target === event.currentTarget && !isMoving) onCancel();
      }}
    >
      <form
        role="dialog"
        aria-modal="true"
        aria-labelledby="diagram-move-heading"
        onSubmit={event => {
          event.preventDefault();
          if (!isLoading && !isMoving) onConfirm(destinationId);
        }}
        className="my-auto w-full max-w-md rounded-2xl border border-stone-200 bg-white p-5 shadow-2xl"
      >
        <h2 id="diagram-move-heading" className="text-lg font-semibold tracking-tight text-zinc-900">
          Move diagram
        </h2>

        <div className="mt-5 space-y-2">
          <DestinationRadio
            checked={destinationId === MY_DIAGRAMS_DESTINATION}
            value={MY_DIAGRAMS_DESTINATION}
            title="My diagrams"
            caption="Keep it outside of any chartbook"
            icon={<TrayIcon />}
            onChange={setDestinationId}
          />

          <div className="flex items-center gap-3 px-1 py-1.5" aria-hidden="true">
            <span className="h-px flex-1 bg-stone-200" />
            <span className="text-[11px] font-semibold uppercase tracking-wider text-zinc-400">Other chartbooks</span>
            <span className="h-px flex-1 bg-stone-200" />
          </div>

          <div className="max-h-52 space-y-2 overflow-y-auto pr-1">
            {isLoading ? (
              <div className="rounded-xl border border-dashed border-stone-200 bg-stone-50 px-4 py-6 text-center text-sm text-zinc-500">
                Loading chartbooks...
              </div>
            ) : chartbooks.length > 0 ? (
              chartbooks.map(chartbook => (
                <DestinationRadio
                  key={chartbook.chartbookId}
                  checked={destinationId === chartbook.chartbookId}
                  value={chartbook.chartbookId}
                  title={chartbook.name}
                  caption={`${chartbook.diagramIds.length} ${chartbook.diagramIds.length === 1 ? 'diagram' : 'diagrams'}`}
                  icon={<FolderIcon />}
                  onChange={setDestinationId}
                />
              ))
            ) : (
              <div className="rounded-xl border border-dashed border-stone-200 bg-stone-50 px-4 py-5 text-center text-sm text-zinc-500">
                No other chartbooks available.
              </div>
            )}
          </div>
        </div>

        {loadError && (
          <p className="mt-3 rounded-lg bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-800">{loadError}</p>
        )}

        <div className="mt-5 flex items-center justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            disabled={isMoving}
            className="h-10 rounded-xl px-3.5 text-sm font-medium text-zinc-600 transition hover:bg-stone-100 disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={isLoading || isMoving}
            className="h-10 rounded-xl bg-zinc-800 px-4 text-sm font-semibold text-white shadow-sm transition hover:bg-zinc-700 disabled:opacity-40"
          >
            {isMoving ? 'Moving...' : 'Move diagram'}
          </button>
        </div>
      </form>
    </div>
  );
};
