'use client';

import { useEffect, useRef, useState } from 'react';
import { MaterialUploader } from '../materials/MaterialUploader';
import { ChartbookItemPicker, type ChartbookPickerOption } from './ChartbookItemPicker';
import type { Chartbook, MaterialUploadTarget } from '../materials/material-types';
import type { createMaterialClient } from '@/api/material';

export type ChartbookUploadTools = {
  client: ReturnType<typeof createMaterialClient>;
  acceptedMimeTypes: string[];
};

/**
 * Names a chartbook and, optionally, fills it in the same step: pick existing diagrams
 * and upload files. Uploads need a server-side scope, so choosing files creates the
 * chartbook first through `ensureChartbook` and then uploads into it.
 */
export const ChartbookCreateDialog = ({
  heading = 'New chartbook',
  hint,
  defaultName = '',
  confirmLabel = 'Create chartbook',
  diagramOptions,
  uploadTools,
  ensureChartbook,
  onCancel,
  onFinish,
}: {
  heading?: string;
  hint?: string;
  defaultName?: string;
  confirmLabel?: string;
  /** Omit to hide the "add diagrams" section. */
  diagramOptions?: ChartbookPickerOption[];
  /** Omit to hide the "upload files" section. */
  uploadTools?: ChartbookUploadTools;
  ensureChartbook: (name: string) => Promise<Chartbook | null>;
  onCancel: () => void;
  onFinish: (chartbook: Chartbook, diagramIds: string[]) => void;
}) => {
  const [name, setName] = useState(defaultName);
  const [selectedDiagramIds, setSelectedDiagramIds] = useState<string[]>([]);
  const [created, setCreated] = useState<Chartbook | null>(null);
  const [isBusy, setIsBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    // Preselect the suggested name so typing replaces it.
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

  /** Creates the chartbook at most once per dialog, whichever step needs it first. */
  const ensureCreated = async () => {
    if (created) return created;
    const chartbook = await ensureChartbook(name);
    if (!chartbook) {
      setFailure('Could not create the chartbook. Please try again.');
      return null;
    }
    setCreated(chartbook);
    return chartbook;
  };

  const prepareUploadTarget = async (): Promise<MaterialUploadTarget> => {
    const chartbook = await ensureCreated();
    if (!chartbook) throw new Error('Chartbook is unavailable');
    return { scopeType: 'CHARTBOOK', scopeId: chartbook.chartbookId, retentionClass: 'RETAINED' };
  };

  const toggleDiagram = (diagramId: string) => {
    setSelectedDiagramIds(previous => previous.includes(diagramId)
      ? previous.filter(id => id !== diagramId)
      : [...previous, diagramId]);
  };

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!name.trim() || isBusy) return;
    setIsBusy(true);
    setFailure(null);
    const chartbook = await ensureCreated();
    setIsBusy(false);
    if (chartbook) onFinish(chartbook, selectedDiagramIds);
  };

  const canUpload = Boolean(uploadTools) && Boolean(name.trim());
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center overflow-y-auto bg-zinc-900/25 px-4 py-8 backdrop-blur-sm"
      // Only a press on the backdrop itself dismisses; presses inside the card must not.
      onMouseDown={event => {
        if (event.target === event.currentTarget) onCancel();
      }}
    >
      <form
        role="dialog"
        aria-modal="true"
        aria-labelledby="chartbook-create-dialog-heading"
        onSubmit={submit}
        className="my-auto w-full max-w-md rounded-xl border border-stone-200 bg-white p-5 shadow-xl"
      >
        <h2 id="chartbook-create-dialog-heading" className="text-base font-semibold text-zinc-900">{heading}</h2>
        {hint && <p className="mt-1 text-sm text-zinc-500">{hint}</p>}

        <label className="sr-only" htmlFor="chartbook-create-dialog-input">Chartbook name</label>
        <input
          id="chartbook-create-dialog-input"
          ref={inputRef}
          value={name}
          onChange={event => setName(event.target.value)}
          // The name is fixed once the chartbook exists on the server.
          disabled={Boolean(created)}
          placeholder="Chartbook name"
          className="mt-4 h-10 w-full rounded-lg border border-stone-200 bg-stone-50 px-3 text-sm text-zinc-800 placeholder:text-zinc-400 transition focus:border-zinc-400 focus:bg-white focus:outline-none focus:ring-2 focus:ring-zinc-700/10 disabled:text-zinc-500"
        />

        {diagramOptions && diagramOptions.length > 0 && (
          <section className="mt-4" aria-label="Add diagrams">
            <h3 className="flex items-center gap-2 text-sm font-medium text-zinc-700">
              Add diagrams
              <span className="text-xs font-normal text-zinc-400">
                {selectedDiagramIds.length > 0 ? `${selectedDiagramIds.length} selected` : 'optional'}
              </span>
            </h3>
            <div className="mt-2">
              <ChartbookItemPicker
                options={diagramOptions}
                selectedIds={selectedDiagramIds}
                onToggle={toggleDiagram}
              />
            </div>
          </section>
        )}

        {uploadTools && (
          <section className="mt-4" aria-label="Upload files to the chartbook">
            <h3 className="flex items-center gap-2 text-sm font-medium text-zinc-700">
              Files
              <span className="text-xs font-normal text-zinc-400">optional</span>
            </h3>
            <p className="mt-1 text-xs text-zinc-500">
              {canUpload ? 'Adding files creates the chartbook right away.' : 'Name the chartbook first, then add files.'}
            </p>
            <div className="mt-2 [&_h2]:text-sm">
              {/* The panel variant keeps each file's progress visible inside the dialog. */}
              <MaterialUploader
                client={uploadTools.client}
                target={{ scopeType: 'CHARTBOOK', scopeId: created?.chartbookId || '', retentionClass: 'RETAINED' }}
                beforeUpload={prepareUploadTarget}
                acceptedMimeTypes={uploadTools.acceptedMimeTypes}
                disabled={!canUpload}
              />
            </div>
          </section>
        )}

        {failure && <p className="mt-4 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-700">{failure}</p>}

        <div className="mt-5 flex items-center justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            className="h-9 rounded-lg px-3 text-sm font-medium text-zinc-600 transition hover:bg-stone-100"
          >
            {created ? 'Close' : 'Cancel'}
          </button>
          <button
            type="submit"
            disabled={!name.trim() || isBusy}
            className="h-9 rounded-lg bg-zinc-800 px-4 text-sm font-semibold text-white shadow-sm transition hover:bg-zinc-700 disabled:opacity-40"
          >
            {created ? 'Done' : confirmLabel}
          </button>
        </div>
      </form>
    </div>
  );
};
