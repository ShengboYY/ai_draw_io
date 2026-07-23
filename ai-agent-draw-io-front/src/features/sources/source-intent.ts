import type { ConversationAttachment } from './conversation-attachments';

export type SourceUsePreference = 'AUTO' | 'DIRECT' | 'DIRECT_AND_RETRIEVAL';
export type DirectSourceUse = Exclude<SourceUsePreference, 'AUTO'>;

const imageFileName = /\.(?:png|jpe?g)$/i;

/** UI eligibility is only a hint; the server still verifies media kind, readiness, and ownership. */
export const hasSingleReadyImageSelection = (
  attachments: ConversationAttachment[],
  selectedUploadIds: string[],
) => {
  const selected = new Set(selectedUploadIds);
  const selectedAttachments = attachments.filter(attachment => selected.has(attachment.uploadId));
  return selectedAttachments.length === 1
    && selectedAttachments[0].state === 'READY'
    && imageFileName.test(selectedAttachments[0].fileName);
};

/** Automatic mode sends no hint so the intent router remains authoritative by default. */
export const buildSourceUseOverride = (
  preference: SourceUsePreference,
  hasSingleReadyImage: boolean,
): { sourceUseOverride?: DirectSourceUse } => (
  preference === 'AUTO' || !hasSingleReadyImage
    ? {}
    : { sourceUseOverride: preference }
);

export const sourceUsePreferenceLabel = (preference: SourceUsePreference) => ({
  AUTO: '自动判断',
  DIRECT: '按原图还原',
  DIRECT_AND_RETRIEVAL: '允许资料补充',
}[preference]);
