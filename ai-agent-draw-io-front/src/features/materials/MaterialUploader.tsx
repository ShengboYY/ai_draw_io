'use client';

import { useRef, useState } from 'react';
import { type BrowserPostPolicy, type MaterialUploadTarget } from './material-types';
import { MaterialGapDialog } from './MaterialGapDialog';
import { MaterialProcessingBadge } from './MaterialProcessingBadge';
import { createUploadState, transitionUpload, type UploadState } from './upload-machine';
import type { createMaterialClient } from '@/api/material';

type MaterialClient = ReturnType<typeof createMaterialClient>;
type UploadItem = UploadState & { file: File; postPolicy?: BrowserPostPolicy };

const idempotencyKey = () => globalThis.crypto.randomUUID();

const sha256 = async (file: File) => {
  const digest = await globalThis.crypto.subtle.digest('SHA-256', await file.arrayBuffer());
  return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('');
};

export const MaterialUploader = ({
  client,
  target,
  acceptedMimeTypes,
  disabled,
  newVersionOfMaterialId,
  onReady,
}: {
  client: MaterialClient;
  target: MaterialUploadTarget;
  acceptedMimeTypes: string[];
  disabled?: boolean;
  newVersionOfMaterialId?: string;
  onReady?: () => void;
}) => {
  const [items, setItems] = useState<UploadItem[]>([]);
  const [dismissedGaps, setDismissedGaps] = useState<Set<File>>(() => new Set());
  const inputRef = useRef<HTMLInputElement>(null);

  const update = (file: File, event: Parameters<typeof transitionUpload>[1], details?: Partial<UploadItem>) => {
    setItems(previous => previous.map(item => item.file === file
      ? { ...item, ...transitionUpload(item, event), ...details }
      : item));
  };

  const completeAndPoll = async (file: File, uploadId: string) => {
    const completed = await client.complete(uploadId);
    update(file, { type: 'COMPLETED', status: completed.state, errorCode: completed.errorCode });
    const terminal = await client.pollStatus(uploadId, status => {
      update(file, { type: 'COMPLETED', status: status.state, errorCode: status.errorCode });
    });
    if (terminal.state === 'READY') onReady?.();
  };

  const uploadBytesAndComplete = async (file: File, uploadId: string, postPolicy?: BrowserPostPolicy) => {
    await client.uploadBytes(postPolicy, file);
    update(file, { type: 'BYTES_UPLOADED' });
    await completeAndPoll(file, uploadId);
  };

  const initiateAndUpload = async (file: File, knownHash?: string) => {
    const hash = knownHash || await sha256(file);
    const initiated = await client.initiate({
      displayName: file.name,
      mediaType: file.type as 'application/pdf' | 'image/png' | 'image/jpeg',
      byteSize: file.size,
      sha256: hash,
      target,
      newVersionOfMaterialId,
    }, idempotencyKey());
    update(file, { type: 'INITIATED', uploadId: initiated.uploadId }, { postPolicy: initiated.postPolicy });
    await uploadBytesAndComplete(file, initiated.uploadId, initiated.postPolicy);
  };

  const upload = async (file: File) => {
    try {
      update(file, { type: 'START' });
      const hash = await sha256(file);
      update(file, { type: 'HASHED' });
      await initiateAndUpload(file, hash);
    } catch (error) {
      update(file, { type: 'FAILED', message: error instanceof Error ? error.message : '上传失败' });
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
    if (retryState.stage === 'COMPLETING' && retryState.uploadId) {
      void completeAndPoll(item.file, retryState.uploadId)
        .catch(error => update(item.file, { type: 'FAILED', message: error instanceof Error ? error.message : '上传失败' }));
    } else if (retryState.stage === 'UPLOADING_BYTES' && retryState.uploadId && item.postPolicy) {
      void uploadBytesAndComplete(item.file, retryState.uploadId, item.postPolicy)
        .catch(error => update(item.file, { type: 'FAILED', message: error instanceof Error ? error.message : '上传失败' }));
    } else if (retryState.stage === 'INITIATING') {
      void initiateAndUpload(item.file)
        .catch(error => update(item.file, { type: 'FAILED', message: error instanceof Error ? error.message : '上传失败' }));
    }
  };

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
        {items.map(item => <li key={`${item.file.name}-${item.file.lastModified}`} className="rounded-lg bg-white p-3 text-sm shadow-sm">
          <div className="flex items-center justify-between gap-3"><span className="truncate">{item.fileName}</span><MaterialProcessingBadge stage={item.stage} /></div>
          {item.errorMessage && <p className="mt-2 text-rose-700">{item.errorMessage}</p>}
          {item.stage === 'PARTIAL_READY' && !dismissedGaps.has(item.file) && <div className="mt-2"><MaterialGapDialog gapCode={item.gapCode} onClose={() => setDismissedGaps(previous => new Set(previous).add(item.file))} /></div>}
          {item.retryable && <button type="button" onClick={() => retry(item)} className="mt-2 font-medium text-zinc-700 underline">重试</button>}
        </li>)}
      </ul>}
    </section>
  );
};
