export type SourceMode = 'NONE' | 'AUTO' | 'EXPLICIT' | 'EXPLICIT_ONLY';

type SourceDeclarationInput = {
  attachmentUploadIds: string[];
  sourceMode: SourceMode;
  selectedVersionIds: string[];
};

const opaqueIds = (ids: string[]) => [...new Set(ids.map(id => id.trim()).filter(Boolean))];

/** Builds the optional source fields without allowing file data into a chat request. */
export const buildSourceDeclaration = ({
  attachmentUploadIds,
  sourceMode,
  selectedVersionIds,
}: SourceDeclarationInput) => {
  const attachments = opaqueIds(attachmentUploadIds);
  const versions = opaqueIds(selectedVersionIds);
  if (attachments.length === 0 && versions.length === 0 && sourceMode === 'AUTO') return {};
  return {
    ...(attachments.length > 0 && { attachmentUploadIds: attachments }),
    sourceMode,
    ...(versions.length > 0 && { selectedVersionIds: versions }),
  };
};
