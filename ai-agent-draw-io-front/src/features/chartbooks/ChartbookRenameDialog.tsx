'use client';

import { useEffect, useRef, useState } from 'react';

/** Renames an open chartbook, matching the create dialog rather than window.prompt. */
export const ChartbookRenameDialog = ({
  currentName,
  onCancel,
  onConfirm,
}: {
  currentName: string;
  onCancel: () => void;
  onConfirm: (name: string) => void;
}) => {
  const [name, setName] = useState(currentName);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    inputRef.current?.focus();
    inputRef.current?.select();
  }, []);

  useEffect(() => {
    const cancelOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onCancel();
    };
    window.addEventListener('keydown', cancelOnEscape);
    return () => window.removeEventListener('keydown', cancelOnEscape);
  }, [onCancel]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-zinc-900/25 px-4 backdrop-blur-sm"
      onMouseDown={event => {
        if (event.target === event.currentTarget) onCancel();
      }}
    >
      <form
        role="dialog"
        aria-modal="true"
        aria-labelledby="chartbook-rename-heading"
        onSubmit={event => {
          event.preventDefault();
          const trimmed = name.trim();
          if (trimmed && trimmed !== currentName) onConfirm(trimmed);
          else onCancel();
        }}
        className="w-full max-w-sm rounded-xl border border-stone-200 bg-white p-5 shadow-xl"
      >
        <h2 id="chartbook-rename-heading" className="text-base font-semibold text-zinc-900">Rename chartbook</h2>
        <label className="sr-only" htmlFor="chartbook-rename-input">Chartbook name</label>
        <input
          id="chartbook-rename-input"
          ref={inputRef}
          value={name}
          onChange={event => setName(event.target.value)}
          placeholder="Chartbook name"
          className="mt-4 h-10 w-full rounded-lg border border-stone-200 bg-stone-50 px-3 text-sm text-zinc-800 placeholder:text-zinc-400 transition focus:border-zinc-400 focus:bg-white focus:outline-none focus:ring-2 focus:ring-zinc-700/10"
        />
        <div className="mt-4 flex items-center justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            className="h-9 rounded-lg px-3 text-sm font-medium text-zinc-600 transition hover:bg-stone-100"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={!name.trim()}
            className="h-9 rounded-lg bg-zinc-800 px-4 text-sm font-semibold text-white shadow-sm transition hover:bg-zinc-700 disabled:opacity-40"
          >
            Save name
          </button>
        </div>
      </form>
    </div>
  );
};
