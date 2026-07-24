'use client';

import type { MaterialCatalogCard } from '../materials/material-types';
import type { ConversationAttachment } from '../sources/conversation-attachments';
import { filesPanelStatusLabel, type FilesPanelGroups } from './files-panel-model';

type FilesPanelProps = {
  groups: FilesPanelGroups;
  uploads: ConversationAttachment[];
  chartbookName?: string;
  loading?: boolean;
  error?: string;
  disabled?: boolean;
  busyMaterialId?: string;
  onClose: () => void;
  onUpload: () => void;
  onRetryUpload: (upload: ConversationAttachment) => void;
  onPreview: (file: MaterialCatalogCard) => void;
  onDownload: (file: MaterialCatalogCard) => void;
  onAddToChartbook: (file: MaterialCatalogCard) => void;
  onRemoveFromConversation: (file: MaterialCatalogCard) => void;
  onRemoveFromChartbook: (file: MaterialCatalogCard) => void;
  onRetry: (file: MaterialCatalogCard) => void;
};

const PendingUploadRow = ({
  upload,
  onRetry,
}: {
  upload: ConversationAttachment;
  onRetry: () => void;
}) => (
  <li className="rounded-lg border border-dashed border-stone-200 bg-stone-50 p-3">
    <span className="block truncate text-sm font-medium text-zinc-800">{upload.fileName}</span>
    <span className="mt-0.5 block text-[11px] text-zinc-500">
      {filesPanelStatusLabel(upload.state)}
    </span>
    {['FAILED', 'REJECTED'].includes(upload.state.trim().toUpperCase()) && (
      <button type="button" onClick={onRetry}
        className="mt-2 text-[11px] font-medium text-zinc-600 hover:text-zinc-900">
        Retry Upload
      </button>
    )}
  </li>
);

const FileRow = ({
  file,
  scope,
  hasChartbook,
  disabled,
  busy,
  onPreview,
  onDownload,
  onAddToChartbook,
  onRemoveFromConversation,
  onRemoveFromChartbook,
  onRetry,
}: {
  file: MaterialCatalogCard;
  scope: 'CONVERSATION' | 'CHARTBOOK';
  hasChartbook: boolean;
  disabled?: boolean;
  busy?: boolean;
} & Pick<FilesPanelProps,
  'onPreview' | 'onDownload' | 'onAddToChartbook' |
  'onRemoveFromConversation' | 'onRemoveFromChartbook' | 'onRetry'>) => {
  const actionDisabled = disabled || busy;
  const processingReady = ['READY', 'PARTIAL_READY', 'SUCCEEDED']
    .includes(file.processingStatus.trim().toUpperCase());
  const status = filesPanelStatusLabel(
    scope === 'CHARTBOOK' && processingReady ? file.searchStatus : file.processingStatus,
  );
  const retryable = status === 'Failed' || status === 'Rejected' || status === 'Search limited';

  return (
    <li className="rounded-lg border border-stone-200 bg-white p-3">
      <div className="flex items-start gap-2">
        <span className="grid h-9 w-9 shrink-0 place-items-center rounded-lg bg-stone-100 text-[10px] font-semibold uppercase text-zinc-500">
          {file.displayName.split('.').pop()?.slice(0, 4) || 'FILE'}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-sm font-medium text-zinc-800">{file.displayName}</span>
          <span className="mt-0.5 block text-[11px] text-zinc-500">{status}</span>
        </span>
      </div>
      <div className="mt-2 flex flex-wrap gap-x-2 gap-y-1 text-[11px] font-medium">
        <button type="button" disabled={actionDisabled} onClick={() => onPreview(file)}
          className="text-zinc-600 hover:text-zinc-900 disabled:opacity-40">Preview</button>
        <button type="button" disabled={actionDisabled || !file.latestVersionId} onClick={() => onDownload(file)}
          className="text-zinc-600 hover:text-zinc-900 disabled:opacity-40">Download</button>
        {retryable && (
          <button type="button" disabled={actionDisabled} onClick={() => onRetry(file)}
            className="text-zinc-600 hover:text-zinc-900 disabled:opacity-40">
            Retry
          </button>
        )}
        {scope === 'CONVERSATION' && hasChartbook && (
          <button type="button" disabled={actionDisabled} onClick={() => onAddToChartbook(file)}
            className="text-emerald-700 hover:text-emerald-900 disabled:opacity-40">Add to Chartbook</button>
        )}
        {scope === 'CONVERSATION' ? (
          <button type="button" disabled={actionDisabled} onClick={() => onRemoveFromConversation(file)}
            className="text-rose-600 hover:text-rose-800 disabled:opacity-40">Remove from Conversation</button>
        ) : (
          <button type="button" disabled={actionDisabled} onClick={() => onRemoveFromChartbook(file)}
            className="text-rose-600 hover:text-rose-800 disabled:opacity-40">Remove from Chartbook</button>
        )}
      </div>
    </li>
  );
};

/** File management surface; the composer only retains short-lived upload chips. */
export const FilesPanel = ({
  groups,
  uploads,
  chartbookName,
  loading,
  error,
  disabled,
  busyMaterialId,
  onClose,
  onUpload,
  onRetryUpload,
  ...actions
}: FilesPanelProps) => (
  <div className="drawio-files-panel fixed inset-x-3 bottom-16 top-3 z-40 flex w-auto flex-col overflow-hidden rounded-lg border border-stone-200 bg-white shadow-2xl shadow-zinc-700/10 sm:absolute sm:bottom-3 sm:left-16 sm:top-3 sm:w-80">
    <div className="flex h-12 shrink-0 items-center justify-between border-b border-stone-100 px-4">
      <span className="text-sm font-semibold text-zinc-800">Files</span>
      <button type="button" onClick={onClose} className="rounded-md p-1.5 text-zinc-500 hover:bg-stone-100" title="Close files">
        <span aria-hidden="true">×</span>
      </button>
    </div>
    <div className="border-b border-stone-100 p-3">
      <button type="button" disabled={disabled} onClick={onUpload}
        className="w-full rounded-lg bg-zinc-800 px-3 py-2 text-sm font-medium text-white hover:bg-zinc-700 disabled:opacity-40">
        Upload
      </button>
    </div>
    <div className="flex-1 space-y-5 overflow-y-auto p-3">
      {error && <p className="rounded-lg bg-rose-50 px-3 py-2 text-xs text-rose-700">{error}</p>}
      {loading && <p className="py-8 text-center text-xs text-zinc-400">Loading files...</p>}
      {!loading && groups.chartbookSharedFiles !== undefined && (
        <section>
          <h2 className="mb-2 text-[10px] font-semibold uppercase tracking-[0.12em] text-zinc-400">
            Chartbook Shared Files{chartbookName ? ` · ${chartbookName}` : ''}
          </h2>
          <ul className="space-y-2">
            {groups.chartbookSharedFiles.map(file => (
              <FileRow key={file.materialId} file={file} scope="CHARTBOOK" hasChartbook
                disabled={disabled} busy={busyMaterialId === file.materialId} {...actions} />
            ))}
          </ul>
          {groups.chartbookSharedFiles.length === 0 && (
            <p className="rounded-lg bg-stone-50 px-3 py-2 text-xs text-zinc-400">No shared files.</p>
          )}
        </section>
      )}
      {!loading && (
        <section>
          <h2 className="mb-2 text-[10px] font-semibold uppercase tracking-[0.12em] text-zinc-400">
            Conversation Files
          </h2>
          <ul className="space-y-2">
            {uploads
              .filter(upload => !upload.materialId
                || ![...groups.conversationFiles, ...(groups.chartbookSharedFiles || [])]
                  .some(file => file.materialId === upload.materialId))
              .map(upload => <PendingUploadRow key={upload.uploadId} upload={upload}
                onRetry={() => onRetryUpload(upload)} />)}
            {groups.conversationFiles.map(file => (
              <FileRow key={file.materialId} file={file} scope="CONVERSATION"
                hasChartbook={groups.chartbookSharedFiles !== undefined}
                disabled={disabled} busy={busyMaterialId === file.materialId} {...actions} />
            ))}
          </ul>
          {groups.conversationFiles.length === 0 && uploads.length === 0 && (
            <p className="rounded-lg bg-stone-50 px-3 py-2 text-xs text-zinc-400">No conversation files.</p>
          )}
        </section>
      )}
    </div>
  </div>
);
