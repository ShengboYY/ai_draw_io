'use client';

export const MaterialGapDialog = ({
  gapCode,
  onClose,
}: {
  gapCode?: string;
  onClose: () => void;
}) => (
  <div className="rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900" role="status">
    <div className="flex items-start justify-between gap-3">
      <p>The item is partially ready. Missing content will not be used automatically as evidence.{gapCode ? ` Reason: ${gapCode}` : ''}</p>
      <button type="button" onClick={onClose} className="shrink-0 font-medium underline">Got it</button>
    </div>
  </div>
);
