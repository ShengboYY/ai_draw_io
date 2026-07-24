'use client';

import { MaterialUploader, type MaterialUploaderHandle } from '../materials/MaterialUploader';
import { type MaterialUploadTarget } from '../materials/material-types';
import { isReadyUploadStatus, isTerminalUploadStatus } from '../materials/upload-machine';
import { forwardRef, useImperativeHandle, useRef, useState, type Dispatch, type SetStateAction } from 'react';
import type { createMaterialClient } from '@/api/material';
import {
  addAttachmentToCurrentSelection,
  type ConversationAttachment,
} from './conversation-attachments';

type MaterialClient = ReturnType<typeof createMaterialClient>;

const statusLabel = (state: string) => ({
  SUCCEEDED: '已就绪', READY: '已就绪', PARTIAL_READY: '部分就绪', FAILED: '处理失败', REJECTED: '已拒绝', CANCELLED: '已取消',
}[state] || '处理中');

/** Uploads temporary conversation material and stores only its server-issued upload ID. */
export const ConversationAttachmentTray = forwardRef<MaterialUploaderHandle, {
  client: MaterialClient;
  sessionId: string;
  diagramId?: string;
  acceptedMimeTypes: string[];
  attachments: ConversationAttachment[];
  selectedUploadIds: string[];
  onChange: Dispatch<SetStateAction<ConversationAttachment[]>>;
  onSelectedUploadIdsChange: Dispatch<SetStateAction<string[]>>;
  onPrepareUpload?: () => Promise<MaterialUploadTarget>;
  disabled?: boolean;
}>(function ConversationAttachmentTray({
  client,
  sessionId,
  diagramId,
  acceptedMimeTypes,
  attachments,
  selectedUploadIds,
  onChange,
  onSelectedUploadIdsChange,
  onPrepareUpload,
  disabled,
}, ref) {
  const [suppressedUploadIds, setSuppressedUploadIds] = useState<string[]>([]);
  const [retryableUploadIds, setRetryableUploadIds] = useState<string[]>([]);
  const uploaderRef = useRef<MaterialUploaderHandle>(null);
  useImperativeHandle(ref, () => ({
    openPicker: () => uploaderRef.current?.openPicker(),
    retryUpload: uploadId => uploaderRef.current?.retryUpload(uploadId) || false,
  }), []);
  const upsert = (next: ConversationAttachment) => {
    if (suppressedUploadIds.includes(next.uploadId)) return;
    onChange(previous => {
      const index = previous.findIndex(item => item.uploadId === next.uploadId);
      return index < 0 ? [...previous, next] : previous.map(item => item.uploadId === next.uploadId ? { ...item, ...next } : item);
    });
  };
  const processingAttachments = attachments.filter(item => !isTerminalUploadStatus(item.state));
  const selected = new Set(selectedUploadIds);
  const retryable = new Set(retryableUploadIds);
  const toggleSelection = (uploadId: string) => onSelectedUploadIdsChange(previous => (
    previous.includes(uploadId)
      ? previous.filter(value => value !== uploadId)
      : addAttachmentToCurrentSelection(previous, uploadId)
  ));

  return (
    <section className={attachments.length > 0 ? 'mb-2 border-b border-stone-200 px-1 pb-2' : ''} aria-label="会话附件">
      <MaterialUploader
        ref={uploaderRef}
        client={client}
        target={{ scopeType: 'CONVERSATION', scopeId: sessionId, retentionClass: 'TEMPORARY', diagramId }}
        beforeUpload={onPrepareUpload}
        acceptedMimeTypes={acceptedMimeTypes}
        disabled={disabled}
        suppressedUploadIds={suppressedUploadIds}
        variant="compact"
        showTrigger={false}
        onRetryableUploadIdsChange={setRetryableUploadIds}
        onUploadInitiated={upload => {
          upsert({ ...upload, state: 'UPLOADING' });
          // A newly attached file belongs to the current turn until the user explicitly opts it out.
          onSelectedUploadIdsChange(previous => addAttachmentToCurrentSelection(previous, upload.uploadId));
        }}
        onUploadStatus={upload => upsert(upload)}
      />
      {attachments.length > 0 && (
        <>
          <div className="mb-1.5 flex items-center justify-between gap-2 px-0.5 text-[11px] text-zinc-500">
            <span>附件 · 仅用于本次会话</span>
            {selected.size > 0 && (
              <button
                type="button"
                disabled={disabled}
                onClick={() => onSelectedUploadIdsChange([])}
                className="hover:text-zinc-800 disabled:opacity-50"
              >
                取消使用
              </button>
            )}
          </div>
          <ul className="flex flex-wrap gap-2">
            {attachments.map(attachment => {
              const normalizedState = attachment.state.trim().toUpperCase();
              // A partially processed file is usable, so it must not look like a hard failure.
              const partial = normalizedState === 'PARTIAL_READY';
              const ready = isReadyUploadStatus(normalizedState) || partial;
              const failed = isTerminalUploadStatus(normalizedState) && !ready;
              return (
                <li
                  key={attachment.uploadId}
                  className={`flex max-w-full items-center gap-2 rounded-xl border bg-white px-2.5 py-2 text-xs shadow-sm transition-opacity ${
                    selected.has(attachment.uploadId) ? 'border-stone-200' : 'border-stone-100 opacity-55'
                  }`}
                >
                  <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-stone-100 text-[10px] font-semibold uppercase text-zinc-500">
                    {attachment.fileName.split('.').pop()?.slice(0, 4) || 'FILE'}
                  </span>
                  <span className="min-w-0">
                    <span className="block max-w-48 truncate font-medium text-zinc-700">{attachment.fileName}</span>
                    <span className="mt-0.5 flex items-center gap-1.5 text-[11px] text-zinc-500">
                      <span
                        className={`h-1.5 w-1.5 rounded-full ${
                          partial ? 'bg-amber-500' : ready ? 'bg-emerald-500' : failed ? 'bg-rose-500' : 'animate-pulse bg-amber-500'
                        }`}
                        aria-hidden="true"
                      />
                      {statusLabel(normalizedState)}
                    </span>
                  </span>
                  <label className="ml-1 flex shrink-0 items-center" title="在本次消息中使用">
                    <span className="sr-only">在本次消息中使用 {attachment.fileName}</span>
                    <input
                      type="checkbox"
                      checked={selected.has(attachment.uploadId)}
                      disabled={disabled}
                      onChange={() => toggleSelection(attachment.uploadId)}
                      className="h-3.5 w-3.5 accent-zinc-700"
                    />
                  </label>
                  {retryable.has(attachment.uploadId) && (
                    <button
                      type="button"
                      disabled={disabled}
                      onClick={() => uploaderRef.current?.retryUpload(attachment.uploadId)}
                      className="shrink-0 font-medium text-zinc-600 hover:text-zinc-900 disabled:opacity-50"
                    >
                      重试
                    </button>
                  )}
                  <button
                    type="button"
                    disabled={disabled}
                    title="移除附件"
                    aria-label={`移除 ${attachment.fileName}`}
                    onClick={() => {
                      setSuppressedUploadIds(previous => [...new Set([...previous, attachment.uploadId])]);
                      onChange(previous => previous.filter(item => item.uploadId !== attachment.uploadId));
                      onSelectedUploadIdsChange(previous => previous.filter(value => value !== attachment.uploadId));
                    }}
                    className="ml-0.5 shrink-0 rounded-full p-1 text-zinc-400 hover:bg-stone-100 hover:text-zinc-700 disabled:opacity-50"
                  >
                    <svg aria-hidden="true" viewBox="0 0 20 20" className="h-3.5 w-3.5" fill="none" stroke="currentColor" strokeWidth="1.8">
                      <path d="m5 5 10 10M15 5 5 15" />
                    </svg>
                  </button>
                </li>
              );
            })}
          </ul>
        </>
      )}
      {processingAttachments.some(attachment => selected.has(attachment.uploadId)) && (
        <p className="mt-1.5 px-0.5 text-[11px] text-zinc-500">附件仍在处理；发送后会自动等待就绪。</p>
      )}
    </section>
  );
});
