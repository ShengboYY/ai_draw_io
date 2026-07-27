'use client';

import { useState } from 'react';
import type { ConversationAttachment } from './conversation-attachments';

const previewKind = (fileName: string): 'image' | 'pdf' | 'file' => {
  const extension = fileName.split('.').pop()?.trim().toLowerCase();
  if (extension === 'pdf') return 'pdf';
  if (['png', 'jpg', 'jpeg'].includes(extension || '')) return 'image';
  return 'file';
};

/** Renders the immutable attachment snapshot owned by one sent user message. */
export const MessageAttachmentPreview = ({
  attachment,
  previewUrl,
}: {
  attachment: ConversationAttachment;
  previewUrl?: string;
}) => {
  const [previewFailed, setPreviewFailed] = useState(false);
  const kind = previewKind(attachment.fileName);
  const canPreview = Boolean(previewUrl) && kind !== 'file' && !previewFailed;
  const extension = attachment.fileName.split('.').pop()?.slice(0, 4).toUpperCase() || 'FILE';

  const content = (
    <figure className="w-[230px] max-w-full overflow-hidden rounded-2xl border border-stone-200 bg-white text-left shadow-sm">
      {canPreview ? (
        <div className="relative h-36 w-full bg-stone-50">
          {/* Protected material previews must be loaded by the browser with its session cookie. */}
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={previewUrl}
            alt={`Preview of ${attachment.fileName}`}
            className="h-full w-full object-contain"
            onError={() => setPreviewFailed(true)}
          />
          {kind === 'pdf' && (
            <span className="absolute left-2 top-2 rounded-md bg-zinc-800/90 px-1.5 py-0.5 text-[9px] font-semibold tracking-wide text-white">
              PDF
            </span>
          )}
        </div>
      ) : (
        <div className="flex h-[76px] items-center gap-3 bg-stone-50 px-3">
          <span className="grid h-11 w-11 shrink-0 place-items-center rounded-xl border border-stone-200 bg-white text-[10px] font-semibold tracking-wide text-zinc-500">
            {extension}
          </span>
          <span className="min-w-0 text-xs text-zinc-500">
            {previewFailed ? 'Preview unavailable' : 'File attachment'}
          </span>
        </div>
      )}
      <figcaption className="flex items-center gap-2 border-t border-stone-100 px-3 py-2">
        <span className="min-w-0 flex-1 truncate text-[11px] font-medium text-zinc-700">
          {attachment.fileName}
        </span>
        <span className="shrink-0 text-[9px] font-semibold uppercase tracking-wide text-zinc-400">
          {extension}
        </span>
      </figcaption>
    </figure>
  );

  return previewUrl ? (
    <a
      href={previewUrl}
      target="_blank"
      rel="noreferrer"
      className="block max-w-full rounded-2xl focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-zinc-400"
      title={`Open ${attachment.fileName}`}
    >
      {content}
    </a>
  ) : content;
};
