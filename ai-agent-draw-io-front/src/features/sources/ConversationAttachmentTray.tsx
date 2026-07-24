'use client';

import { MaterialUploader, type MaterialUploaderHandle } from '../materials/MaterialUploader';
import { type MaterialUploadTarget } from '../materials/material-types';
import { isReadyUploadStatus, isTerminalUploadStatus } from '../materials/upload-machine';
import { forwardRef, useImperativeHandle, useRef, useState, type Dispatch, type SetStateAction } from 'react';
import type { createMaterialClient } from '@/api/material';
import { type ConversationAttachment } from './conversation-attachments';

type MaterialClient = ReturnType<typeof createMaterialClient>;

const rejectionLabels: Record<string, string> = {
  REJECTED_SECURITY: '安全检查未通过',
  REJECTED_LIMIT: '文件超出处理限制',
  REJECTED_FORMAT: '文件格式不受支持',
};

// Prefer the server's rejection category over a generic terminal-state label.
const statusLabel = (state: string, errorCode?: string) => {
  if (state === 'REJECTED') {
    return rejectionLabels[errorCode?.trim().toUpperCase() || ''] || '文件已被拒绝';
  }
  return ({
    SUCCEEDED: '已就绪', READY: '已就绪', PARTIAL_READY: '部分就绪', FAILED: '处理失败', CANCELLED: '已取消',
  }[state] || '处理中');
};

/** Uploads temporary conversation material and stores only its server-issued upload ID. */
export const ConversationAttachmentTray = forwardRef<MaterialUploaderHandle, {
  client: MaterialClient;
  sessionId: string;
  diagramId?: string;
  acceptedMimeTypes: string[];
  attachments: ConversationAttachment[];
  onChange: Dispatch<SetStateAction<ConversationAttachment[]>>;
  onPrepareUpload?: () => Promise<MaterialUploadTarget>;
  disabled?: boolean;
}>(function ConversationAttachmentTray({
  client,
  sessionId,
  diagramId,
  acceptedMimeTypes,
  attachments,
  onChange,
  onPrepareUpload,
  disabled,
}, ref) {
  const [retryableUploadIds, setRetryableUploadIds] = useState<string[]>([]);
  const uploaderRef = useRef<MaterialUploaderHandle>(null);
  useImperativeHandle(ref, () => ({
    openPicker: () => uploaderRef.current?.openPicker(),
    retryUpload: uploadId => uploaderRef.current?.retryUpload(uploadId) || false,
  }), []);
  const upsert = (next: ConversationAttachment) => {
    onChange(previous => {
      const index = previous.findIndex(item => item.uploadId === next.uploadId);
      return index < 0 ? [...previous, next] : previous.map(item => item.uploadId === next.uploadId ? { ...item, ...next } : item);
    });
  };
  const processingAttachments = attachments.filter(item => !isTerminalUploadStatus(item.state));
  const retryable = new Set(retryableUploadIds);

  return (
    <section className={attachments.length > 0 ? 'mb-1 px-1 pb-1' : ''} aria-label="会话附件">
      <MaterialUploader
        ref={uploaderRef}
        client={client}
        target={{ scopeType: 'CONVERSATION', scopeId: sessionId, retentionClass: 'TEMPORARY', diagramId }}
        beforeUpload={onPrepareUpload}
        acceptedMimeTypes={acceptedMimeTypes}
        disabled={disabled}
        variant="compact"
        showTrigger={false}
        onRetryableUploadIdsChange={setRetryableUploadIds}
        onUploadInitiated={upload => upsert({ ...upload, state: 'UPLOADING' })}
        onUploadStatus={upload => upsert(upload)}
      />
      {processingAttachments.length > 0 && (
        <>
          <div className="mb-2 px-0.5 text-[11px] text-zinc-500">刚上传到 Conversation</div>
          {/* Compact horizontal cards keep attachments visible without compressing the writing area. */}
          <ul className="flex gap-2 overflow-x-auto pb-1">
            {processingAttachments.map(attachment => {
              const normalizedState = attachment.state.trim().toUpperCase();
              // A partially processed file is usable, so it must not look like a hard failure.
              const partial = normalizedState === 'PARTIAL_READY';
              const ready = isReadyUploadStatus(normalizedState) || partial;
              const failed = isTerminalUploadStatus(normalizedState) && !ready;
              const attachmentStatusLabel = statusLabel(normalizedState, attachment.errorCode);
              return (
                <li
                  key={attachment.uploadId}
                  className="relative flex h-[68px] min-w-[190px] max-w-[240px] items-center gap-2.5 rounded-2xl border border-stone-200 bg-stone-50 px-2.5 py-2 text-xs"
                >
                  <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl border border-stone-200 bg-white text-[10px] font-semibold uppercase tracking-wide text-zinc-500">
                    {attachment.fileName.split('.').pop()?.slice(0, 4) || 'FILE'}
                  </span>
                  <span className="min-w-0">
                    <span className="block truncate font-medium text-zinc-700">{attachment.fileName}</span>
                    <span className="mt-0.5 flex min-w-0 items-center gap-1.5 text-[11px] text-zinc-500" title={failed ? attachmentStatusLabel : undefined}>
                      <span
                        className={`h-1.5 w-1.5 rounded-full ${
                          partial ? 'bg-amber-500' : ready ? 'bg-emerald-500' : failed ? 'bg-rose-500' : 'animate-pulse bg-amber-500'
                        }`}
                        aria-hidden="true"
                      />
                      <span className="truncate">{attachmentStatusLabel}</span>
                    </span>
                  </span>
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
                </li>
              );
            })}
          </ul>
        </>
      )}
      {processingAttachments.length > 0 && (
        <p className="mt-1.5 px-0.5 text-[11px] text-zinc-500">附件仍在处理；发送后会自动等待就绪。</p>
      )}
    </section>
  );
});
