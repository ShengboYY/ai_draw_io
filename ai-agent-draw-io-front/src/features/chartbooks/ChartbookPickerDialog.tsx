'use client';

import { useEffect, useState } from 'react';
import { ChartbookItemPicker, type ChartbookPickerOption } from './ChartbookItemPicker';

/** Picks existing diagrams or library files to pull into an open chartbook. */
export const ChartbookPickerDialog = ({
  heading,
  hint,
  confirmLabel,
  emptyMessage,
  options,
  onCancel,
  onConfirm,
}: {
  heading: string;
  hint?: string;
  confirmLabel: string;
  emptyMessage: string;
  options: ChartbookPickerOption[];
  onCancel: () => void;
  onConfirm: (ids: string[]) => void;
}) => {
  const [selectedIds, setSelectedIds] = useState<string[]>([]);

  useEffect(() => {
    const cancelOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onCancel();
    };
    window.addEventListener('keydown', cancelOnEscape);
    return () => window.removeEventListener('keydown', cancelOnEscape);
  }, [onCancel]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center overflow-y-auto bg-zinc-900/25 px-4 py-8 backdrop-blur-sm"
      onMouseDown={event => {
        if (event.target === event.currentTarget) onCancel();
      }}
    >
      <form
        role="dialog"
        aria-modal="true"
        aria-labelledby="chartbook-picker-heading"
        onSubmit={event => {
          event.preventDefault();
          if (selectedIds.length > 0) onConfirm(selectedIds);
        }}
        className="my-auto w-full max-w-md rounded-xl border border-stone-200 bg-white p-5 shadow-xl"
      >
        <h2 id="chartbook-picker-heading" className="text-base font-semibold text-zinc-900">{heading}</h2>
        {hint && <p className="mt-1 text-sm text-zinc-500">{hint}</p>}
        <div className="mt-4">
          {options.length > 0 ? (
            <ChartbookItemPicker
              options={options}
              selectedIds={selectedIds}
              onToggle={id => setSelectedIds(previous => previous.includes(id)
                ? previous.filter(selected => selected !== id)
                : [...previous, id])}
            />
          ) : (
            <p className="rounded-lg border border-dashed border-stone-300 bg-stone-50 px-4 py-6 text-center text-sm text-zinc-500">
              {emptyMessage}
            </p>
          )}
        </div>
        <div className="mt-5 flex items-center justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            className="h-9 rounded-lg px-3 text-sm font-medium text-zinc-600 transition hover:bg-stone-100"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={selectedIds.length === 0}
            className="h-9 rounded-lg bg-zinc-800 px-4 text-sm font-semibold text-white shadow-sm transition hover:bg-zinc-700 disabled:opacity-40"
          >
            {selectedIds.length > 1 ? `${confirmLabel} (${selectedIds.length})` : confirmLabel}
          </button>
        </div>
      </form>
    </div>
  );
};
