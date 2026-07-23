'use client';

import {
  directConfirmationIssue,
  type DirectClarificationResolution,
} from './direct-confirmation';

/** Collects one explicit bounded resolution for every unsafe image observation. */
export const DirectConfirmationPanel = ({
  reasons,
  selections,
  onSelectionChange,
  onConfirm,
  onCancel,
  disabled,
}: {
  reasons: string[];
  selections: Record<string, DirectClarificationResolution>;
  onSelectionChange: (reasonCode: string, value: DirectClarificationResolution) => void;
  onConfirm: () => void;
  onCancel: () => void;
  disabled?: boolean;
}) => {
  const issues = reasons.map(directConfirmationIssue);
  const complete = issues.length > 0 && issues.every(issue => Boolean(selections[issue.reasonCode]));

  return (
    <aside className="absolute right-5 top-5 z-30 w-80 max-w-[calc(100%-2.5rem)] rounded-xl border border-amber-200 bg-white/95 p-4 shadow-lg backdrop-blur">
      <p className="text-xs font-semibold uppercase tracking-wide text-amber-700">需要确认图片结构</p>
      <p className="mt-1 text-xs text-zinc-500">确认后会重新读取同一张图片；若仍不确定，系统不会修改画布。</p>
      <div className="my-3 max-h-64 space-y-3 overflow-y-auto">
        {issues.map(issue => (
          <label key={issue.reasonCode} className="block rounded-lg border border-stone-200 bg-stone-50 p-3">
            <span className="block text-sm font-medium text-zinc-800">{issue.targetLabel}</span>
            <span className="mt-1 block text-xs text-zinc-500">{issue.prompt}</span>
            <select
              aria-label={`${issue.targetLabel}确认选项`}
              value={selections[issue.reasonCode] || ''}
              disabled={disabled}
              onChange={event => onSelectionChange(
                issue.reasonCode,
                event.target.value as DirectClarificationResolution,
              )}
              className="mt-2 w-full rounded-md border border-stone-300 bg-white px-2 py-1.5 text-xs disabled:opacity-50"
            >
              <option value="">请选择</option>
              {issue.options.map(option => (
                <option key={option.value} value={option.value}>{option.label}</option>
              ))}
            </select>
          </label>
        ))}
      </div>
      <div className="flex justify-end gap-2">
        <button
          type="button"
          onClick={onCancel}
          disabled={disabled}
          className="rounded-md border border-stone-300 px-3 py-1.5 text-xs text-zinc-600 disabled:opacity-50"
        >
          取消转换
        </button>
        <button
          type="button"
          onClick={onConfirm}
          disabled={disabled || !complete}
          className="rounded-md bg-amber-600 px-3 py-1.5 text-xs font-medium text-white disabled:cursor-not-allowed disabled:opacity-40"
        >
          确认并继续
        </button>
      </div>
    </aside>
  );
};
