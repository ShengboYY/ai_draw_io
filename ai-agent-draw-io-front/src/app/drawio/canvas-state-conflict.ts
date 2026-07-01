type CanvasStateConflictInput = {
  content?: string;
  expectedVersion?: number;
};

const DEFAULT_CONFLICT_MESSAGE = 'The diagram changed in another session. Refresh the diagram and try again.';

// Keep conflict copy centralized so stream handlers only decide how to display it.
export const buildCanvasStateConflictMessage = ({
  content,
  expectedVersion,
}: CanvasStateConflictInput) => {
  const baseMessage = content?.trim() || DEFAULT_CONFLICT_MESSAGE;
  const versionHint = Number.isFinite(expectedVersion) ? ` Expected version: ${expectedVersion}.` : '';

  return `${baseMessage}${versionHint}`;
};
