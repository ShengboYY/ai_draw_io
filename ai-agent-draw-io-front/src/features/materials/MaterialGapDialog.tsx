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
      <p>资料已部分就绪，缺失内容不会被自动用作依据。{gapCode ? ` 原因：${gapCode}` : ''}</p>
      <button type="button" onClick={onClose} className="shrink-0 font-medium underline">知道了</button>
    </div>
  </div>
);
