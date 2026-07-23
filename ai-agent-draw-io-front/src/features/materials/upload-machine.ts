export type UploadStage =
  | 'IDLE'
  | 'HASHING'
  | 'INITIATING'
  | 'UPLOADING_BYTES'
  | 'COMPLETING'
  | 'PROCESSING'
  | 'READY'
  | 'PARTIAL_READY'
  | 'FAILED'
  | 'REJECTED'
  | 'CANCELLED';

export type UploadState = {
  fileName: string;
  stage: UploadStage;
  uploadId?: string;
  errorMessage?: string;
  gapCode?: string;
  retryable: boolean;
  retryStage?: UploadStage;
};

export type UploadEvent =
  | { type: 'START' }
  | { type: 'HASHED' }
  | { type: 'INITIATED'; uploadId: string }
  | { type: 'BYTES_UPLOADED' }
  | { type: 'COMPLETED'; status: string; errorCode?: string }
  | { type: 'FAILED'; message: string }
  | { type: 'RETRY' }
  | { type: 'CANCEL' };

export const createUploadState = (fileName: string): UploadState => ({
  fileName,
  stage: 'IDLE',
  retryable: false,
});

// The UI only dispatches events; this reducer owns every valid per-file state transition.
export const transitionUpload = (state: UploadState, event: UploadEvent): UploadState => {
  switch (event.type) {
    case 'START':
      return state.stage === 'IDLE'
        ? { ...state, stage: 'HASHING', errorMessage: undefined, gapCode: undefined, retryable: false, retryStage: undefined }
        : state;
    case 'HASHED':
      return state.stage === 'HASHING' ? { ...state, stage: 'INITIATING', retryable: false } : state;
    case 'INITIATED':
      return state.stage === 'INITIATING'
        ? { ...state, stage: 'UPLOADING_BYTES', uploadId: event.uploadId, retryable: false }
        : state;
    case 'BYTES_UPLOADED':
      return state.stage === 'UPLOADING_BYTES' ? { ...state, stage: 'COMPLETING', retryable: false } : state;
    case 'COMPLETED':
      return state.stage === 'COMPLETING' || state.stage === 'PROCESSING'
        ? completed(state, event.status, event.errorCode)
        : state;
    case 'FAILED':
      return canFail(state.stage)
        ? {
          ...state,
          stage: 'FAILED',
          errorMessage: event.message,
          retryable: canRetry(state.stage),
          retryStage: canRetry(state.stage) ? state.stage : undefined,
        }
        : state;
    case 'RETRY':
      return state.retryable && state.retryStage
        ? { ...state, stage: state.retryStage, errorMessage: undefined, retryable: false, retryStage: undefined }
        : state;
    case 'CANCEL':
      return canCancel(state.stage)
        ? { ...state, stage: 'CANCELLED', retryable: false, retryStage: undefined }
        : state;
  }
};

const completed = (state: UploadState, status: string, errorCode?: string): UploadState => {
  const normalized = status.trim().toUpperCase();
  const stage: UploadStage = normalized === 'READY'
    ? 'READY'
    : normalized === 'PARTIAL_READY'
      ? 'PARTIAL_READY'
      : normalized === 'REJECTED'
        ? 'REJECTED'
        : normalized === 'CANCELLED'
          ? 'CANCELLED'
          : normalized === 'FAILED'
            ? 'FAILED'
            : 'PROCESSING';
  return {
    ...state,
    stage,
    gapCode: stage === 'PARTIAL_READY' ? errorCode : undefined,
    errorMessage: stage === 'FAILED' || stage === 'REJECTED' ? errorCode : undefined,
    retryable: false,
    retryStage: undefined,
  };
};

const canRetry = (stage: UploadStage) =>
  stage === 'INITIATING' || stage === 'UPLOADING_BYTES' || stage === 'COMPLETING';

const canFail = (stage: UploadStage) => stage === 'HASHING' || canRetry(stage) || stage === 'PROCESSING';

const canCancel = (stage: UploadStage) => !['READY', 'PARTIAL_READY', 'FAILED', 'REJECTED', 'CANCELLED'].includes(stage);
