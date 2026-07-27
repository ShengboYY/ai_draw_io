'use client';

import { useEffect, useRef } from 'react';
import { TrashIcon } from './DiagramActionMenu';

type DiagramDeleteDialogProps = {
  diagramTitle: string;
  isDeleting?: boolean;
  onCancel: () => void;
  onConfirm: () => void;
};

/** Confirms permanent diagram deletion inside the product UI instead of using window.confirm. */
export const DiagramDeleteDialog = ({
  diagramTitle,
  isDeleting = false,
  onCancel,
  onConfirm,
}: DiagramDeleteDialogProps) => {
  const cancelButtonRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    cancelButtonRef.current?.focus();
  }, []);

  useEffect(() => {
    const cancelOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !isDeleting) onCancel();
    };
    window.addEventListener('keydown', cancelOnEscape);
    return () => window.removeEventListener('keydown', cancelOnEscape);
  }, [isDeleting, onCancel]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-zinc-900/25 px-4 backdrop-blur-sm"
      onMouseDown={event => {
        if (event.target === event.currentTarget && !isDeleting) onCancel();
      }}
    >
      <div
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="diagram-delete-heading"
        aria-describedby="diagram-delete-description"
        className="w-full max-w-sm rounded-2xl border border-stone-200 bg-white p-5 shadow-2xl"
      >
        <div className="flex items-start gap-3">
          <span
            className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-rose-50 text-rose-600"
            aria-hidden="true"
          >
            <TrashIcon />
          </span>
          <div className="min-w-0 pt-0.5">
            <h2 id="diagram-delete-heading" className="text-lg font-semibold tracking-tight text-zinc-900">
              Delete diagram?
            </h2>
            <p id="diagram-delete-description" className="mt-1 text-sm leading-6 text-zinc-500">
              <span className="font-medium text-zinc-700">“{diagramTitle}”</span> will be permanently deleted.
              This action cannot be undone.
            </p>
          </div>
        </div>
        <div className="mt-5 flex items-center justify-end gap-2">
          <button
            ref={cancelButtonRef}
            type="button"
            onClick={onCancel}
            disabled={isDeleting}
            className="h-10 rounded-xl px-3.5 text-sm font-medium text-zinc-600 transition hover:bg-stone-100 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-zinc-700/20 disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={isDeleting}
            className="h-10 rounded-xl bg-rose-600 px-4 text-sm font-semibold text-white shadow-sm transition hover:bg-rose-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-rose-600/30 disabled:opacity-50"
          >
            {isDeleting ? 'Deleting...' : 'Delete diagram'}
          </button>
        </div>
      </div>
    </div>
  );
};
