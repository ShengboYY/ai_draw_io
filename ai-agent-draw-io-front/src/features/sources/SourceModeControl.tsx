'use client';

import type { SourceMode } from './source-selection';

const labels: Record<SourceMode, string> = {
  AUTO: '自动选择（推荐）',
  EXPLICIT: '自动选择 + 已选资料',
  EXPLICIT_ONLY: '仅使用已选资料',
  NONE: '不使用资料',
};

/** Keeps source intent explicit while leaving authorization to the server. */
export const SourceModeControl = ({ value, onChange, disabled }: {
  value: SourceMode;
  onChange: (value: SourceMode) => void;
  disabled?: boolean;
}) => (
  <label className="flex items-center gap-2 text-xs text-zinc-600">
    <span className="font-medium">资料使用方式</span>
    <select
      aria-label="资料使用方式"
      value={value}
      disabled={disabled}
      onChange={event => onChange(event.target.value as SourceMode)}
      className="rounded-md border border-stone-300 bg-white px-2 py-1 text-xs disabled:opacity-50"
    >
      {(Object.keys(labels) as SourceMode[]).map(mode => <option key={mode} value={mode}>{labels[mode]}</option>)}
    </select>
  </label>
);
