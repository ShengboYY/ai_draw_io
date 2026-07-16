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

export const MAX_AUTOMATIC_VISUAL_REPAIR_ROUNDS = 2;

export const nextVisualReviewStage = (
  completedRepairRounds: number,
): CanvasVisualReviewStage | undefined => {
  if (completedRepairRounds <= 0 || completedRepairRounds > MAX_AUTOMATIC_VISUAL_REPAIR_ROUNDS) {
    return undefined;
  }
  return completedRepairRounds === MAX_AUTOMATIC_VISUAL_REPAIR_ROUNDS
    ? 'VERIFY_ONLY'
    : 'POST_REPAIR';
};

export const shouldReviewSavedRepair = ({
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
