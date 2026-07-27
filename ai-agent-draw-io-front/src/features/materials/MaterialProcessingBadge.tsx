import type { UploadStage } from './upload-machine';

const labels: Record<UploadStage, string> = {
  IDLE: 'Waiting to upload',
  HASHING: 'Checking file',
  INITIATING: 'Preparing upload',
  UPLOADING_BYTES: 'Uploading file',
  COMPLETING: 'Submitting for processing',
  PROCESSING: 'Scanning and indexing',
  READY: 'Ready',
  PARTIAL_READY: 'Partially ready',
  FAILED: 'Upload failed',
  REJECTED: 'File rejected',
  CANCELLED: 'Cancelled',
};

export const MaterialProcessingBadge = ({ stage }: { stage: UploadStage }) => (
  <span className="rounded-full bg-stone-100 px-2.5 py-1 text-xs font-medium text-zinc-700">
    {labels[stage]}
  </span>
);
