'use client';

import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from 'react';
import { type BrowserPostPolicy, type MaterialUploadTarget } from './material-types';
import { MaterialGapDialog } from './MaterialGapDialog';
import { MaterialProcessingBadge } from './MaterialProcessingBadge';
import { createUploadState, isReadyUploadStatus, transitionUpload, type UploadState } from './upload-machine';
import type { createMaterialClient } from '@/api/material';

type MaterialClient = ReturnType<typeof createMaterialClient>;
type UploadItem = UploadState & { file: File; postPolicy?: BrowserPostPolicy };
export type MaterialUploaderHandle = {
  openPicker: () => void;
  retryUpload: (uploadId: string) => boolean;
};

type MaterialUploaderProps = {
  client: MaterialClient;
  target: MaterialUploadTarget;
  acceptedMimeTypes: string[];
  disabled?: boolean;
  newVersionOfMaterialId?: string;
  beforeUpload?: () => Promise<MaterialUploadTarget | void>;
  onReady?: () => void;
  onUploadInitiated?: (upload: { uploadId: string; fileName: string }) => void;
  onUploadStatus?: (upload: { uploadId: string; fileName: string; state: string; errorCode?: string }) => void;
  onRetryableUploadIdsChange?: (uploadIds: string[]) => void;
  suppressedUploadIds?: string[];
  variant?: 'panel' | 'compact';
  showTrigger?: boolean;
};

const idempotencyKey = () => globalThis.crypto.randomUUID();

const sha256 = async (file: File) => {
  const digest = await globalThis.crypto.subtle.digest('SHA-256', await file.arrayBuffer());
  return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('');
};

export const MaterialUploader = forwardRef<MaterialUploaderHandle, MaterialUploaderProps>(function MaterialUploader({
  client,
  target,
  acceptedMimeTypes,
  disabled,
  newVersionOfMaterialId,
  beforeUpload,
  onReady,
  onUploadInitiated,
  onUploadStatus,
  onRetryableUploadIdsChange,
  suppressedUploadIds = [],
  variant = 'panel',
  showTrigger = true,
}, ref) {
  const [items, setItems] = useState<UploadItem[]>([]);
  const [dismissedGaps, setDismissedGaps] = useState<Set<File>>(() => new Set());
  const inputRef = useRef<HTMLInputElement>(null);
  const suppressedIds = new Set(suppressedUploadIds);
  const suppressedIdsRef = useRef(new Set<string>());
  useEffect(() => {
    suppressedIdsRef.current = new Set(suppressedUploadIds);
  }, [suppressedUploadIds]);
  useEffect(() => {
    // Only expose retries backed by this browser's in-memory file and upload state.
    onRetryableUploadIdsChange?.(items.flatMap(item => (
      item.uploadId && item.retryable ? [item.uploadId] : []
    )));
  }, [items, onRetryableUploadIdsChange]);

  const reportUploadStatus = (upload: { uploadId: string; fileName: string; state: string; errorCode?: string }) => {
    if (!suppressedIdsRef.current.has(upload.uploadId)) onUploadStatus?.(upload);
  };

  const update = (file: File, event: Parameters<typeof transitionUpload>[1], details?: Partial<UploadItem>) => {
    setItems(previous => previous.map(item => item.file === file
      ? { ...item, ...transitionUpload(item, event), ...details }
      : item));
  };

  const completeAndPoll = async (file: File, uploadId: string) => {
    const completed = await client.complete(uploadId);
    update(file, { type: 'COMPLETED', status: completed.state, errorCode: completed.errorCode });
    reportUploadStatus({ uploadId, fileName: file.name, state: completed.state, errorCode: completed.errorCode });
    const terminal = await client.pollStatus(uploadId, status => {
      update(file, { type: 'COMPLETED', status: status.state, errorCode: status.errorCode });
      reportUploadStatus({ uploadId, fileName: file.name, state: status.state, errorCode: status.errorCode });
    });
    if (isReadyUploadStatus(terminal.state)) onReady?.();
  };

  const uploadBytesAndComplete = async (file: File, uploadId: string, postPolicy?: BrowserPostPolicy) => {
    await client.uploadBytes(postPolicy, file);
    update(file, { type: 'BYTES_UPLOADED' });
    await completeAndPoll(file, uploadId);
  };

  const initiateAndUpload = async (file: File, knownHash?: string) => {
    let uploadId = '';
    try {
      // Establish the server-side ownership scope before asking it to accept file metadata.
      const preparedTarget = await beforeUpload?.();
      const hash = knownHash || await sha256(file);
      const initiated = await client.initiate({
        displayName: file.name,
        mediaType: file.type as 'application/pdf' | 'image/png' | 'image/jpeg',
        byteSize: file.size,
        sha256: hash,
        target: preparedTarget || target,
        newVersionOfMaterialId,
      }, idempotencyKey());
      uploadId = initiated.uploadId;
      update(file, { type: 'INITIATED', uploadId }, { postPolicy: initiated.postPolicy });
      // Expose the opaque upload ID to a parent without exposing the file bytes to chat payloads.
      onUploadInitiated?.({ uploadId, fileName: file.name });
      await uploadBytesAndComplete(file, uploadId, initiated.postPolicy);
      return uploadId;
    } catch (error) {
      if (uploadId) reportUploadStatus({ uploadId, fileName: file.name, state: 'FAILED' });
      throw error;
    }
  };

  const upload = async (file: File) => {
    let uploadId = '';
    try {
      update(file, { type: 'START' });
      const hash = await sha256(file);
      update(file, { type: 'HASHED' });
      uploadId = await initiateAndUpload(file, hash);
    } catch (error) {
      update(file, { type: 'FAILED', message: error instanceof Error ? error.message : '上传失败' });
      if (uploadId) reportUploadStatus({ uploadId, fileName: file.name, state: 'FAILED' });
    }
  };

  const chooseFiles = (files: FileList | null) => {
    const supported = Array.from(files || []).filter(file => acceptedMimeTypes.includes(file.type));
    const nextItems = supported.map(file => ({ ...createUploadState(file.name), file }));
    setItems(previous => [...previous, ...nextItems]);
    // Let React commit the per-file IDLE entries before their transitions begin.
    window.setTimeout(() => supported.forEach(file => void upload(file)), 0);
  };

  const retry = (item: UploadItem) => {
    const retryState = transitionUpload(item, { type: 'RETRY' });
    setItems(previous => previous.map(current => current.file === item.file ? { ...current, ...retryState } : current));
    if (retryState.uploadId) {
      reportUploadStatus({ uploadId: retryState.uploadId, fileName: item.file.name, state: 'PROCESSING' });
    }
    if (retryState.stage === 'COMPLETING' && retryState.uploadId) {
      void completeAndPoll(item.file, retryState.uploadId)
        .catch(error => {
          update(item.file, { type: 'FAILED', message: error instanceof Error ? error.message : '上传失败' });
          reportUploadStatus({ uploadId: retryState.uploadId!, fileName: item.file.name, state: 'FAILED' });
        });
    } else if (retryState.stage === 'UPLOADING_BYTES' && retryState.uploadId && item.postPolicy) {
      void uploadBytesAndComplete(item.file, retryState.uploadId, item.postPolicy)
        .catch(error => {
          update(item.file, { type: 'FAILED', message: error instanceof Error ? error.message : '上传失败' });
          reportUploadStatus({ uploadId: retryState.uploadId!, fileName: item.file.name, state: 'FAILED' });
        });
    } else if (retryState.stage === 'INITIATING') {
      void initiateAndUpload(item.file)
        .catch(error => update(item.file, { type: 'FAILED', message: error instanceof Error ? error.message : '上传失败' }));
    }
  };

  const retryRef = useRef<(item: UploadItem) => void>(() => undefined);
  useEffect(() => {
    retryRef.current = retry;
  });
  useImperativeHandle(ref, () => ({
    openPicker: () => inputRef.current?.click(),
    retryUpload: uploadId => {
      const item = items.find(candidate => candidate.uploadId === uploadId && candidate.retryable);
      if (!item) return false;
      retryRef.current(item);
      return true;
    },
  }), [items]);

  const visibleItems = items
    .filter(item => !item.uploadId || !suppressedIds.has(item.uploadId))
    // Once initiated, the conversation tray owns the compact attachment card and status.
    .filter(item => variant === 'panel' || !item.uploadId);

  if (variant === 'compact') return (
    <section aria-label="上传资料">
      {showTrigger && (
        <button
          type="button"
          disabled={disabled}
          onClick={() => inputRef.current?.click()}
          className="inline-flex items-center gap-1.5 rounded-full border border-stone-200 bg-white px-3 py-1.5 text-xs font-medium text-zinc-700 shadow-sm transition-colors hover:bg-stone-50 disabled:opacity-40"
        >
          <span aria-hidden="true">＋</span>
          添加文件
        </button>
      )}
      <input ref={inputRef} type="file" multiple hidden accept={acceptedMimeTypes.join(',')} onChange={event => chooseFiles(event.target.files)} />
      {visibleItems.length > 0 && (
        <ul className="flex flex-wrap gap-2">
          {visibleItems.map(item => (
            <li key={`${item.file.name}-${item.file.lastModified}`} className="max-w-full rounded-xl border border-stone-200 bg-white px-3 py-2 text-xs shadow-sm">
              <div className="flex min-w-0 items-center gap-2">
                <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-stone-100 text-[10px] font-semibold uppercase text-zinc-500">
                  {item.file.name.split('.').pop()?.slice(0, 4) || 'FILE'}
                </span>
                <span className="max-w-48 truncate font-medium text-zinc-700">{item.fileName}</span>
                <MaterialProcessingBadge stage={item.stage} />
              </div>
              {item.errorMessage && <p className="mt-1.5 max-w-72 text-rose-700">{item.errorMessage}</p>}
              {item.retryable && <button type="button" onClick={() => retry(item)} className="mt-1.5 font-medium text-zinc-700 underline">重试</button>}
            </li>
          ))}
        </ul>
      )}
    </section>
  );

  return (
    <section aria-label="上传资料" className="rounded-xl border border-dashed border-stone-300 bg-stone-50 p-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="font-semibold text-zinc-900">上传资料</h2>
          <p className="mt-1 text-sm text-zinc-500">支持 PDF、PNG 和 JPEG。文件会在浏览器直接上传后再进行处理。</p>
        </div>
        <button type="button" disabled={disabled} onClick={() => inputRef.current?.click()} className="theme-btn-primary rounded-lg px-4 py-2 text-sm disabled:opacity-40">选择文件</button>
        <input ref={inputRef} type="file" multiple hidden accept={acceptedMimeTypes.join(',')} onChange={event => chooseFiles(event.target.files)} />
      </div>
      {items.length > 0 && <ul className="mt-4 space-y-2">
        {visibleItems.map(item => <li key={`${item.file.name}-${item.file.lastModified}`} className="rounded-lg bg-white p-3 text-sm shadow-sm">
          <div className="flex items-center justify-between gap-3"><span className="truncate">{item.fileName}</span><MaterialProcessingBadge stage={item.stage} /></div>
          {item.errorMessage && <p className="mt-2 text-rose-700">{item.errorMessage}</p>}
          {item.stage === 'PARTIAL_READY' && !dismissedGaps.has(item.file) && <div className="mt-2"><MaterialGapDialog gapCode={item.gapCode} onClose={() => setDismissedGaps(previous => new Set(previous).add(item.file))} /></div>}
          {item.retryable && <button type="button" onClick={() => retry(item)} className="mt-2 font-medium text-zinc-700 underline">重试</button>}
        </li>)}
      </ul>}
    </section>
  );
});
