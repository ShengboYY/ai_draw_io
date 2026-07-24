export type SourceMode = 'NONE' | 'AUTO' | 'EXPLICIT' | 'EXPLICIT_ONLY';

type SourceDeclarationInput = {
  attachmentUploadIds: string[];
  sourceMode: SourceMode;
  selectedVersionIds: string[];
};

const opaqueIds = (ids: string[]) => [...new Set(ids.map(id => id.trim()).filter(Boolean))];

/** Keeps the retrieval mode aligned with deliberate library selection without widening scope. */
export const sourceModeAfterVersionSelection = (sourceMode: SourceMode, selectedVersionCount: number): SourceMode => {
  if (selectedVersionCount > 0 && sourceMode === 'AUTO') return 'EXPLICIT';
  if (selectedVersionCount > 0 && sourceMode === 'NONE') return 'EXPLICIT_ONLY';
  if (selectedVersionCount === 0 && sourceMode === 'EXPLICIT') return 'AUTO';
  if (selectedVersionCount === 0 && sourceMode === 'EXPLICIT_ONLY') return 'NONE';
  return sourceMode;
};

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
