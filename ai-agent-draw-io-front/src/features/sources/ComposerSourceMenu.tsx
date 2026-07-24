'use client';

import { useEffect, useRef, useState } from 'react';
import { SourceModeControl } from './SourceModeControl';
import { SourcePicker, type SourceOption } from './SourcePicker';
import { sourceModeAfterVersionSelection, type SourceMode } from './source-selection';

type ComposerSourceMenuProps = {
  sourceMode: SourceMode;
  onSourceModeChange: (mode: SourceMode) => void;
  options: SourceOption[];
  selectedVersionIds: string[];
  onSelectedVersionIdsChange: (versionIds: string[]) => void;
  activeScopeLabels?: string[];
  onUploadLocal: () => void;
  disabled?: boolean;
};

/** Keeps upload and retrieval controls behind the composer's single add-material action. */
export const ComposerSourceMenu = ({
  sourceMode,
  onSourceModeChange,
  options,
  selectedVersionIds,
  onSelectedVersionIdsChange,
  activeScopeLabels = [],
  onUploadLocal,
  disabled,
}: ComposerSourceMenuProps) => {
  const [open, setOpen] = useState(false);
  const [showLibrary, setShowLibrary] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const firstActionRef = useRef<HTMLButtonElement>(null);
  const menuOpen = open && !disabled;

  useEffect(() => {
    if (!menuOpen) return;
    const focusFrame = window.requestAnimationFrame(() => firstActionRef.current?.focus());
    const closeOnOutsidePointer = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) {
        setOpen(false);
        setShowLibrary(false);
      }
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false);
        setShowLibrary(false);
        triggerRef.current?.focus();
      }
    };
    document.addEventListener('pointerdown', closeOnOutsidePointer);
    document.addEventListener('keydown', closeOnEscape);
    return () => {
      window.cancelAnimationFrame(focusFrame);
      document.removeEventListener('pointerdown', closeOnOutsidePointer);
      document.removeEventListener('keydown', closeOnEscape);
    };
  }, [menuOpen]);

  useEffect(() => {
    if (!disabled) return;
    // Hide immediately through menuOpen, then clear the remembered state after this render.
    const closeTimer = window.setTimeout(() => {
      setOpen(false);
      setShowLibrary(false);
    }, 0);
    return () => window.clearTimeout(closeTimer);
  }, [disabled]);

  const updateSelectedVersions = (versionIds: string[]) => {
    onSelectedVersionIdsChange(versionIds);
    onSourceModeChange(sourceModeAfterVersionSelection(sourceMode, versionIds.length));
  };

  const hasCustomSourceMode = sourceMode !== 'AUTO';
  const selectedCount = selectedVersionIds.length;

  return (
    <div ref={rootRef} className="relative shrink-0">
      <button
        ref={triggerRef}
        type="button"
        disabled={disabled}
        aria-haspopup="dialog"
        aria-expanded={menuOpen}
        aria-label={selectedCount > 0 ? `添加资料，已选择 ${selectedCount} 项资料库资料` : '添加资料'}
        title="添加资料"
        onClick={() => {
          setOpen(previous => !previous);
          if (menuOpen) setShowLibrary(false);
        }}
        className="relative grid h-9 w-9 place-items-center rounded-full text-zinc-600 transition-colors hover:bg-stone-100 hover:text-zinc-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-stone-300 disabled:cursor-not-allowed disabled:opacity-40"
      >
        <svg aria-hidden="true" viewBox="0 0 20 20" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.7">
          <path d="M10 4v12M4 10h12" />
        </svg>
        {(selectedCount > 0 || hasCustomSourceMode) && (
          <span className="absolute right-0.5 top-0.5 h-1.5 w-1.5 rounded-full bg-emerald-500" aria-hidden="true" />
        )}
      </button>

      {menuOpen && (
        <div
          role="dialog"
          aria-label="添加资料"
          className="absolute bottom-full left-0 z-[70] mb-2 w-[min(22rem,calc(100vw-2rem))] overflow-hidden rounded-2xl border border-stone-200 bg-white text-left shadow-2xl"
        >
          <div className="p-1.5">
            <button
              ref={firstActionRef}
              type="button"
              disabled={disabled}
              onClick={() => {
                // Keep this call in the click handler so browsers preserve file-picker activation.
                onUploadLocal();
                setOpen(false);
                setShowLibrary(false);
                window.requestAnimationFrame(() => triggerRef.current?.focus());
              }}
              className="flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left hover:bg-stone-100 disabled:cursor-not-allowed disabled:opacity-45"
            >
              <span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-stone-100 text-zinc-600">
                <svg aria-hidden="true" viewBox="0 0 20 20" className="h-4.5 w-4.5" fill="none" stroke="currentColor" strokeWidth="1.6">
                  <path d="M3.5 5.5h13v9h-13zM7 17h6M10 14.5V17" />
                </svg>
              </span>
              <span>
                <span className="block text-sm font-medium text-zinc-800">从本机上传</span>
                <span className="block text-xs text-zinc-500">PDF、PNG 或 JPEG</span>
              </span>
            </button>

            <button
              type="button"
              disabled={disabled || options.length === 0}
              onClick={() => setShowLibrary(previous => !previous)}
              className="flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left hover:bg-stone-100 disabled:cursor-not-allowed disabled:opacity-45"
            >
              <span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-stone-100 text-zinc-600">
                <svg aria-hidden="true" viewBox="0 0 20 20" className="h-4.5 w-4.5" fill="none" stroke="currentColor" strokeWidth="1.6">
                  <path d="M4 4.5h3v11H4zM8.5 4.5h3v11h-3zM13 5.5l2.5-.7 2.7 9.8-2.5.7z" />
                </svg>
              </span>
              <span className="min-w-0 flex-1">
                <span className="block text-sm font-medium text-zinc-800">从资料库选择</span>
                <span className="block text-xs text-zinc-500">
                  {options.length > 0 ? `${options.length} 项可用资料` : '暂无可用资料'}
                </span>
              </span>
              {options.length > 0 && (
                <svg aria-hidden="true" viewBox="0 0 20 20" className={`h-4 w-4 text-zinc-400 transition-transform ${showLibrary ? 'rotate-180' : ''}`} fill="none" stroke="currentColor" strokeWidth="1.8">
                  <path d="m5 7 5 5 5-5" />
                </svg>
              )}
            </button>
          </div>

          {showLibrary && options.length > 0 && (
            <div className="max-h-64 overflow-y-auto border-t border-stone-200 bg-stone-50 p-3">
              <SourcePicker
                options={options}
                selectedVersionIds={selectedVersionIds}
                onChange={updateSelectedVersions}
                activeScopeLabels={activeScopeLabels}
                disabled={disabled}
              />
            </div>
          )}

          <div className="border-t border-stone-200 px-3 py-3">
            <SourceModeControl value={sourceMode} onChange={onSourceModeChange} disabled={disabled} />
          </div>
        </div>
      )}
    </div>
  );
};
