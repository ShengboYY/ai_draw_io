import type {
  CanvasVisualReviewRequestDTO,
  CanvasVisualReviewStage,
} from '@/types/api';

type ReviewableMutation = {
  version?: number;
  contentHash?: string;
};

type BuildCanvasVisualReviewRequestInput = Omit<
  CanvasVisualReviewRequestDTO,
  'rendererVersion' | 'stage'
> & {
  stage: CanvasVisualReviewStage;
};

export const canStartPostMutationReview = ({
  version,
  contentHash,
}: ReviewableMutation) => (
  Number.isFinite(version)
  && Boolean(contentHash?.trim())
);

export const buildCanvasVisualReviewRequest = (
  input: BuildCanvasVisualReviewRequestInput,
): CanvasVisualReviewRequestDTO => ({
  ...input,
  rendererVersion: 'drawio-embed-png-v1',
});

export const shouldRunFinalVerification = ({
  decision,
  version,
  contentHash,
}: {
  decision?: string;
  version?: number;
  contentHash?: string;
}) => decision === 'REPAIR' && Number.isFinite(version) && Boolean(contentHash?.trim());
