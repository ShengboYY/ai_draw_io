'use client';

import { MaterialUploader } from '../materials/MaterialUploader';
import { useState, type Dispatch, type SetStateAction } from 'react';
import type { createMaterialClient } from '@/api/material';
import type { ConversationAttachment } from './conversation-attachments';

type MaterialClient = ReturnType<typeof createMaterialClient>;

const statusLabel = (state: string) => ({
  READY: '已就绪', PARTIAL_READY: '部分就绪', FAILED: '处理失败', REJECTED: '已拒绝', CANCELLED: '已取消',
}[state] || '处理中');

/** Uploads temporary conversation material and stores only its server-issued upload ID. */
export const ConversationAttachmentTray = ({
  client,
  sessionId,
  acceptedMimeTypes,
  attachments,
  onChange,
  onInitializeSession,
  disabled,
}: {
  client: MaterialClient;
  sessionId: string;
  acceptedMimeTypes: string[];
  attachments: ConversationAttachment[];
  onChange: Dispatch<SetStateAction<ConversationAttachment[]>>;
  onInitializeSession?: () => void;
  disabled?: boolean;
}) => {
  const [suppressedUploadIds, setSuppressedUploadIds] = useState<string[]>([]);
  const upsert = (next: ConversationAttachment) => {
    if (suppressedUploadIds.includes(next.uploadId)) return;
    onChange(previous => {
      const index = previous.findIndex(item => item.uploadId === next.uploadId);
      return index < 0 ? [...previous, next] : previous.map(item => item.uploadId === next.uploadId ? { ...item, ...next } : item);
    });
  };
  const processingAttachments = attachments.filter(item => !['READY', 'PARTIAL_READY', 'FAILED', 'REJECTED', 'CANCELLED'].includes(item.state));

  if (!sessionId) return (
    <section className="mb-2 flex items-center justify-between gap-2 rounded-xl border border-stone-200 bg-stone-50 p-2.5 text-xs">
      <span className="text-zinc-600">先创建会话，再添加临时附件。</span>
      <button type="button" disabled={disabled || !onInitializeSession} onClick={onInitializeSession} className="rounded-md border border-stone-300 bg-white px-2 py-1 font-medium text-zinc-700 disabled:opacity-50">创建附件会话</button>
    </section>
  );
  return (
    <section className="mb-2 rounded-xl border border-stone-200 bg-stone-50 p-2.5" aria-label="会话附件">
      <div className="mb-2 flex items-center justify-between gap-2">
        <span className="text-xs font-medium text-zinc-700">本次会话附件</span>
        <span className="text-[11px] text-zinc-500">临时资料，不会自动存入资料库</span>
      </div>
      <MaterialUploader
        client={client}
        target={{ scopeType: 'CONVERSATION', scopeId: sessionId, retentionClass: 'TEMPORARY' }}
        acceptedMimeTypes={acceptedMimeTypes}
        disabled={disabled}
        suppressedUploadIds={suppressedUploadIds}
        onUploadInitiated={upload => upsert({ ...upload, state: 'UPLOADING' })}
        onUploadStatus={upload => upsert(upload)}
      />
      {attachments.length > 0 && <ul className="mt-2 space-y-1">
        {attachments.map(attachment => <li key={attachment.uploadId} className="flex items-center justify-between gap-2 rounded-md bg-white px-2 py-1.5 text-xs">
          <span className="min-w-0 truncate text-zinc-700">{attachment.fileName}</span>
          <span className="shrink-0 text-zinc-500">{statusLabel(attachment.state)}</span>
          <button type="button" disabled={disabled} onClick={() => {
            setSuppressedUploadIds(previous => [...new Set([...previous, attachment.uploadId])]);
            onChange(previous => previous.filter(item => item.uploadId !== attachment.uploadId));
          }} className="shrink-0 text-zinc-500 underline disabled:opacity-50">移除</button>
        </li>)}
      </ul>}
      {processingAttachments.length > 0 && <p className="mt-2 text-[11px] text-amber-700">附件处理中；发送时服务端会按当前可用状态处理。</p>}
    </section>
  );
};
