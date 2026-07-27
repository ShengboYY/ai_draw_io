'use client';

import {
  MaterialUploader,
  type MaterialUploaderHandle,
  type ReportedUploadStatus,
} from '../materials/MaterialUploader';
import { type MaterialUploadTarget } from '../materials/material-types';
import { isReadyUploadStatus } from '../materials/upload-machine';
import { forwardRef, useEffect, useImperativeHandle, useRef, useState, type Dispatch, type SetStateAction } from 'react';
import { createPortal } from 'react-dom';
import type { createMaterialClient } from '@/api/material';
import { type ConversationAttachment } from './conversation-attachments';

type MaterialClient = ReturnType<typeof createMaterialClient>;
type AttachmentDetail = { fileName: string; previewUrl: string };

const imageExtensions = new Set(['png', 'jpg', 'jpeg']);

const AttachmentThumbnail = ({
  fileName,
  previewUrl,
}: {
  fileName: string;
  previewUrl?: string;
}) => {
  const [previewFailed, setPreviewFailed] = useState(false);
  const extension = fileName.split('.').pop()?.slice(0, 4).toUpperCase() || 'FILE';

  if (previewUrl && !previewFailed) {
    return (
      <span className="block h-full w-full overflow-hidden rounded-xl border border-stone-200 bg-stone-50">
        {/* The protected preview endpoint keeps the thumbnail scoped to the signed-in conversation owner. */}
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          src={previewUrl}
          alt={`Preview of ${fileName}`}
          className="h-full w-full object-cover"
          onError={() => setPreviewFailed(true)}
        />
      </span>
    );
  }

  return (
    <span className="flex h-full w-full items-center justify-center rounded-xl border border-stone-200 bg-stone-50 text-[10px] font-semibold uppercase tracking-wide text-zinc-400">
      {extension}
    </span>
  );
};

const AttachmentDetailDialog = ({
  detail,
  onClose,
}: {
  detail: AttachmentDetail;
  onClose: () => void;
}) => {
  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', closeOnEscape);
    return () => document.removeEventListener('keydown', closeOnEscape);
  }, [onClose]);

  if (typeof document === 'undefined') return null;

  // A portal keeps the image viewer above the scrollable composer and canvas panes.
  return createPortal(
    <div
      className="fixed inset-0 z-[100] flex items-center justify-center bg-zinc-950/80 p-4 backdrop-blur-sm"
      onMouseDown={event => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label={`Preview ${detail.fileName}`}
        className="relative flex max-h-[92vh] max-w-[92vw] flex-col"
      >
        <button
          type="button"
          autoFocus
          onClick={onClose}
          aria-label="Close image preview"
          title="Close"
          className="absolute right-2 top-2 z-10 grid h-9 w-9 place-items-center rounded-full bg-zinc-900/85 text-white shadow-lg transition hover:bg-zinc-700"
        >
          <svg viewBox="0 0 20 20" aria-hidden="true" className="h-5 w-5" fill="none">
            <path d="M5 5l10 10M15 5L5 15" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
          </svg>
        </button>
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          src={detail.previewUrl}
          alt={detail.fileName}
          className="max-h-[86vh] max-w-[92vw] rounded-2xl bg-white object-contain shadow-2xl"
        />
        <p className="mt-2 max-w-[92vw] truncate text-center text-xs text-white/80">{detail.fileName}</p>
      </div>
    </div>,
    document.body,
  );
};

/** Uploads temporary conversation material and stores only its server-issued upload ID. */
export const ConversationAttachmentTray = forwardRef<MaterialUploaderHandle, {
  client: MaterialClient;
  sessionId: string;
  diagramId?: string;
  acceptedMimeTypes: string[];
  attachments: ConversationAttachment[];
  onChange: Dispatch<SetStateAction<ConversationAttachment[]>>;
  suppressedUploadIds?: string[];
  onSentAttachmentStatus?: (attachment: ReportedUploadStatus) => void;
  onPrepareUpload?: () => Promise<MaterialUploadTarget>;
  disabled?: boolean;
}>(function ConversationAttachmentTray({
  client,
  sessionId,
  diagramId,
  acceptedMimeTypes,
  attachments,
  onChange,
  suppressedUploadIds = [],
  onSentAttachmentStatus,
  onPrepareUpload,
  disabled,
}, ref) {
  const [dismissedUploadIds, setDismissedUploadIds] = useState<string[]>([]);
  const [attachmentDetail, setAttachmentDetail] = useState<AttachmentDetail | null>(null);
  const dismissedUploadIdsRef = useRef(new Set<string>());
  const uploaderRef = useRef<MaterialUploaderHandle>(null);
  useImperativeHandle(ref, () => ({
    openPicker: () => uploaderRef.current?.openPicker(),
    retryUpload: uploadId => uploaderRef.current?.retryUpload(uploadId) || false,
  }), []);
  const upsert = (next: ConversationAttachment) => {
    if (dismissedUploadIdsRef.current.has(next.uploadId)) return;
    onChange(previous => {
      const index = previous.findIndex(item => item.uploadId === next.uploadId);
      return index < 0 ? [...previous, next] : previous.map(item => item.uploadId === next.uploadId ? { ...item, ...next } : item);
    });
  };
  const removeAttachment = (uploadId: string) => {
    // Suppress late upload callbacks so a removed thumbnail cannot reappear.
    dismissedUploadIdsRef.current.add(uploadId);
    setDismissedUploadIds(previous => previous.includes(uploadId) ? previous : [...previous, uploadId]);
    onChange(previous => previous.filter(item => item.uploadId !== uploadId));
  };
  const allSuppressedUploadIds = [...suppressedUploadIds, ...dismissedUploadIds];

  return (
    <section className={attachments.length > 0 ? 'mb-2 px-1 pt-1' : ''} aria-label="Conversation attachments">
      <MaterialUploader
        ref={uploaderRef}
        client={client}
        target={{ scopeType: 'CONVERSATION', scopeId: sessionId, retentionClass: 'TEMPORARY', diagramId }}
        beforeUpload={onPrepareUpload}
        acceptedMimeTypes={acceptedMimeTypes}
        disabled={disabled}
        variant="compact"
        showTrigger={false}
        suppressedUploadIds={allSuppressedUploadIds}
        onUploadInitiated={upload => upsert({ ...upload, state: 'UPLOADING' })}
        onUploadStatus={upload => upsert(upload)}
        onSuppressedUploadStatus={upload => {
          if (!dismissedUploadIdsRef.current.has(upload.uploadId)) onSentAttachmentStatus?.(upload);
        }}
      />
      {attachments.length > 0 && (
        /* ChatGPT-style attachments keep the composer focused on the image itself. */
        <ul className="flex gap-2 overflow-x-auto pb-1 pr-1">
          {attachments.map(attachment => {
            const normalizedState = attachment.state.trim().toUpperCase();
            const extension = attachment.fileName.split('.').pop()?.trim().toLowerCase() || '';
            const ready = isReadyUploadStatus(normalizedState) || normalizedState === 'PARTIAL_READY';
            const previewUrl = ready && imageExtensions.has(extension) && attachment.materialId && attachment.versionId
              ? client.previewUrl(attachment.materialId, attachment.versionId)
              : undefined;
            return (
              <li
                key={attachment.uploadId}
                className="relative h-20 w-20 shrink-0"
              >
                <button
                  type="button"
                  onClick={() => {
                    if (previewUrl) setAttachmentDetail({ fileName: attachment.fileName, previewUrl });
                  }}
                  aria-label={previewUrl ? `View ${attachment.fileName}` : attachment.fileName}
                  className={`block h-full w-full rounded-xl focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-zinc-500 ${
                    previewUrl ? 'cursor-zoom-in' : 'cursor-default'
                  }`}
                >
                  <AttachmentThumbnail fileName={attachment.fileName} previewUrl={previewUrl} />
                </button>
                <button
                  type="button"
                  disabled={disabled}
                  onClick={() => removeAttachment(attachment.uploadId)}
                  aria-label={`Remove ${attachment.fileName}`}
                  title={`Remove ${attachment.fileName}`}
                  className="absolute right-1 top-1 grid h-6 w-6 place-items-center rounded-full border-2 border-white bg-zinc-900 text-white shadow-sm transition hover:bg-zinc-700 disabled:opacity-50"
                >
                  <svg viewBox="0 0 20 20" aria-hidden="true" className="h-3.5 w-3.5" fill="none">
                    <path d="M5 5l10 10M15 5L5 15" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
                  </svg>
                </button>
              </li>
            );
          })}
        </ul>
      )}
      {attachmentDetail && (
        <AttachmentDetailDialog detail={attachmentDetail} onClose={() => setAttachmentDetail(null)} />
      )}
    </section>
  );
});
