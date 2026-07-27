'use client';

import { useEffect, useRef, useState } from 'react';

/** Keeps diagram renaming inside the chartbook UI instead of using a browser prompt. */
export const DiagramRenameDialog = ({
  currentName,
  isSaving = false,
  onCancel,
  onConfirm,
}: {
  currentName: string;
  isSaving?: boolean;
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
      if (event.key === 'Escape' && !isSaving) onCancel();
    };
    window.addEventListener('keydown', cancelOnEscape);
    return () => window.removeEventListener('keydown', cancelOnEscape);
  }, [isSaving, onCancel]);

  const trimmedName = name.trim();

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-zinc-900/25 px-4 backdrop-blur-sm"
      onMouseDown={event => {
        if (event.target === event.currentTarget && !isSaving) onCancel();
      }}
    >
      <form
        role="dialog"
        aria-modal="true"
        aria-labelledby="diagram-rename-heading"
        onSubmit={event => {
          event.preventDefault();
          if (trimmedName && trimmedName !== currentName && !isSaving) onConfirm(trimmedName);
        }}
        className="w-full max-w-sm rounded-2xl border border-stone-200 bg-white p-5 shadow-2xl"
      >
        <h2 id="diagram-rename-heading" className="text-lg font-semibold tracking-tight text-zinc-900">
          Rename diagram
        </h2>
        <p className="mt-1 text-sm text-zinc-500">Choose a clear name for this diagram.</p>
        <label className="sr-only" htmlFor="diagram-rename-input">Diagram name</label>
        <input
          id="diagram-rename-input"
          ref={inputRef}
          value={name}
          disabled={isSaving}
          onChange={event => setName(event.target.value)}
          placeholder="Diagram name"
          className="mt-4 h-11 w-full rounded-xl border border-stone-200 bg-stone-50 px-3.5 text-sm text-zinc-800 placeholder:text-zinc-400 transition focus:border-zinc-400 focus:bg-white focus:outline-none focus:ring-2 focus:ring-zinc-700/10 disabled:opacity-60"
        />
        <div className="mt-5 flex items-center justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            disabled={isSaving}
            className="h-10 rounded-xl px-3.5 text-sm font-medium text-zinc-600 transition hover:bg-stone-100 disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={!trimmedName || trimmedName === currentName || isSaving}
            className="h-10 rounded-xl bg-zinc-800 px-4 text-sm font-semibold text-white shadow-sm transition hover:bg-zinc-700 disabled:opacity-40"
          >
            {isSaving ? 'Saving...' : 'Save name'}
          </button>
        </div>
      </form>
    </div>
  );
};
