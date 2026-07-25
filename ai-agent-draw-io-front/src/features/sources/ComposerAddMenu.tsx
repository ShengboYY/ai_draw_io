'use client';

import { useMemo, useRef, useState } from 'react';

import type { createMaterialClient } from '@/api/material';
import type { MaterialCatalogCard } from '@/features/materials/material-types';
import type { ConversationLibrarySelection } from './conversation-library-selections';

type MaterialClient = ReturnType<typeof createMaterialClient>;

type ComposerAddMenuProps = {
  client: MaterialClient;
  disabled?: boolean;
  selections: ConversationLibrarySelection[];
  onSelect: (selection: ConversationLibrarySelection) => Promise<void>;
  onRemove: (versionId: string) => void;
  onUploadFromComputer: () => void;
};

const READY_STATES = new Set(['READY', 'PARTIAL_READY', 'SUCCEEDED']);

const toSelection = (item: MaterialCatalogCard): ConversationLibrarySelection | null => {
  if (!item.latestVersionId) return null;
  return {
    materialId: item.materialId,
    versionId: item.latestVersionId,
    displayName: item.displayName,
    kind: item.kind,
  };
};

export const ComposerLibrarySelectionTray = ({
  selections,
  onRemove,
}: Pick<ComposerAddMenuProps, 'selections' | 'onRemove'>) => {
  if (selections.length === 0) return null;
  return (
    <div className="flex flex-wrap gap-2 px-1 pb-1" aria-label="Library files">
      {selections.map(selection => (
        <span
          key={selection.versionId}
          className="inline-flex max-w-full items-center gap-2 rounded-xl border border-stone-200 bg-stone-50 px-2.5 py-1.5 text-xs text-zinc-700"
        >
          <span className="max-w-52 truncate">{selection.displayName}</span>
          <button
            type="button"
            aria-label={`Remove ${selection.displayName}`}
            onClick={() => onRemove(selection.versionId)}
            className="text-base leading-none text-zinc-400 hover:text-zinc-700"
          >
            ×
          </button>
        </span>
      ))}
    </div>
  );
};

export const ComposerAddMenu = ({
  client,
  disabled,
  selections,
  onSelect,
  onRemove,
  onUploadFromComputer,
}: ComposerAddMenuProps) => {
  const [menuOpen, setMenuOpen] = useState(false);
  const [libraryOpen, setLibraryOpen] = useState(false);
  const [items, setItems] = useState<MaterialCatalogCard[]>([]);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [busyVersionId, setBusyVersionId] = useState('');
  const loadRequestRef = useRef(0);

  const visibleItems = useMemo(() => {
    const normalizedQuery = query.trim().toLocaleLowerCase();
    return items.filter(item => !normalizedQuery
      || item.displayName.toLocaleLowerCase().includes(normalizedQuery));
  }, [items, query]);
  const selectedVersionIds = useMemo(
    () => new Set(selections.map(selection => selection.versionId)),
    [selections],
  );

  const loadLibrary = async (searchQuery: string) => {
    const requestId = ++loadRequestRef.current;
    setLoading(true);
    setError('');
    try {
      const page = await client.list({
        query: searchQuery.trim() || undefined,
        lifecycleState: 'ACTIVE',
        limit: 100,
        offset: 0,
      });
      if (requestId !== loadRequestRef.current) return;
      // The Library chooser only exposes retained, immutable versions owned by the current account.
      setItems(page.items.filter(item => item.retentionClass === 'RETAINED' && item.latestVersionId));
    } catch (loadError) {
      if (requestId !== loadRequestRef.current) return;
      setError(loadError instanceof Error ? loadError.message : 'Unable to load Library files.');
    } finally {
      if (requestId === loadRequestRef.current) setLoading(false);
    }
  };

  const openLibrary = () => {
    setMenuOpen(false);
    setLibraryOpen(true);
    setQuery('');
    void loadLibrary('');
  };

  const toggleSelection = async (item: MaterialCatalogCard) => {
    const selection = toSelection(item);
    if (!selection) return;
    if (selectedVersionIds.has(selection.versionId)) {
      onRemove(selection.versionId);
      return;
    }
    setBusyVersionId(selection.versionId);
    setError('');
    try {
      await onSelect(selection);
    } catch (selectError) {
      setError(selectError instanceof Error ? selectError.message : 'Unable to attach this Library file.');
    } finally {
      setBusyVersionId('');
    }
  };

  return (
    <div className="relative">
      <button
        type="button"
        aria-label="Add files"
        aria-haspopup="menu"
        aria-expanded={menuOpen}
        onClick={() => setMenuOpen(open => !open)}
        disabled={disabled}
        className="grid h-9 w-9 shrink-0 place-items-center rounded-full text-zinc-500 transition-colors hover:bg-stone-100 hover:text-zinc-800 disabled:cursor-not-allowed disabled:opacity-40"
        title="Add files"
      >
        <svg aria-hidden="true" viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="2">
          <path d="M12 5v14M5 12h14" strokeLinecap="round" />
        </svg>
      </button>

      {menuOpen && (
        <div role="menu" className="absolute bottom-11 left-0 z-50 w-56 rounded-2xl border border-stone-200 bg-white p-1.5 shadow-xl">
          <button
            type="button"
            role="menuitem"
            onClick={openLibrary}
            className="w-full rounded-xl px-3 py-2 text-left text-sm text-zinc-700 hover:bg-stone-100"
          >
            Choose from Library
          </button>
          <button
            type="button"
            role="menuitem"
            onClick={() => {
              setMenuOpen(false);
              onUploadFromComputer();
            }}
            className="w-full rounded-xl px-3 py-2 text-left text-sm text-zinc-700 hover:bg-stone-100"
          >
            Upload from computer
          </button>
        </div>
      )}

      {libraryOpen && (
        <div
          role="dialog"
          aria-modal="true"
          aria-label="Choose from Library"
          className="absolute -left-3 bottom-11 z-50 w-72 max-w-[calc(100vw-2rem)] rounded-2xl border border-stone-200 bg-white p-3 shadow-2xl"
        >
          <div className="mb-2 flex items-center justify-between">
            <div>
              <div className="text-sm font-semibold text-zinc-800">Library</div>
              <div className="text-xs text-zinc-500">Attach files to this conversation</div>
            </div>
            <button
              type="button"
              aria-label="Close Library"
              onClick={() => setLibraryOpen(false)}
              className="grid h-7 w-7 place-items-center rounded-full text-zinc-400 hover:bg-stone-100 hover:text-zinc-700"
            >
              ×
            </button>
          </div>
          <input
            value={query}
            onChange={event => {
              const nextQuery = event.target.value;
              setQuery(nextQuery);
              // Query the catalog instead of limiting search to the first locally loaded page.
              void loadLibrary(nextQuery);
            }}
            placeholder="Search Library"
            className="mb-2 h-9 w-full rounded-xl border border-stone-200 px-3 text-sm outline-none focus:border-stone-400"
          />
          <div className="max-h-64 space-y-1 overflow-y-auto">
            {loading && <div className="px-2 py-6 text-center text-sm text-zinc-500">Loading…</div>}
            {!loading && visibleItems.length === 0 && !error && (
              <div className="px-2 py-6 text-center text-sm text-zinc-500">No Library files found.</div>
            )}
            {!loading && visibleItems.map(item => {
              const versionId = item.latestVersionId || '';
              const selected = selectedVersionIds.has(versionId);
              const ready = READY_STATES.has(item.processingStatus.trim().toUpperCase());
              return (
                <button
                  key={item.materialId}
                  type="button"
                  disabled={!ready || Boolean(busyVersionId)}
                  onClick={() => void toggleSelection(item)}
                  className="flex w-full items-center justify-between gap-3 rounded-xl px-3 py-2 text-left hover:bg-stone-100 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  <span className="min-w-0">
                    <span className="block truncate text-sm text-zinc-800">{item.displayName}</span>
                    <span className="block text-[11px] text-zinc-500">{ready ? item.kind : item.processingStatus}</span>
                  </span>
                  <span className="shrink-0 text-xs font-medium text-zinc-500">
                    {selected ? 'Attached' : busyVersionId === versionId ? 'Adding…' : 'Add'}
                  </span>
                </button>
              );
            })}
          </div>
          {error && <div role="alert" className="mt-2 text-xs text-red-600">{error}</div>}
        </div>
      )}
    </div>
  );
};
