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
  reviewedVersion,
  reviewedContentHash,
  version,
  contentHash,
}: {
  decision?: string;
  reviewedVersion?: number;
  reviewedContentHash?: string;
  version?: number;
  contentHash?: string;
}) => decision === 'REPAIR'
  && typeof reviewedVersion === 'number'
  && Number.isFinite(reviewedVersion)
  && typeof version === 'number'
  && Number.isFinite(version)
  && version > reviewedVersion
  && Boolean(reviewedContentHash?.trim())
  && Boolean(contentHash?.trim())
  && contentHash !== reviewedContentHash;

export const shouldShowUnavailableReview = (hasReviewPresentation: boolean) => (
  !hasReviewPresentation
);
