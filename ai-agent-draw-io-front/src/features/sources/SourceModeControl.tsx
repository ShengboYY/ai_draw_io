'use client';

import type { SourceMode } from './source-selection';

const labels: Record<SourceMode, string> = {
  AUTO: 'Auto (Recommended)',
  EXPLICIT: 'Auto + selected',
  EXPLICIT_ONLY: 'Selected only',
  NONE: 'No sources',
};

/** Keeps source intent explicit while leaving authorization to the server. */
export const SourceModeControl = ({ value, onChange, disabled }: {
  value: SourceMode;
  onChange: (value: SourceMode) => void;
  disabled?: boolean;
}) => (
  <label className="flex items-center justify-between gap-2 text-[11px] text-zinc-600">
    <span className="shrink-0 font-medium">Source usage</span>
    <select
      aria-label="Source usage"
      value={value}
      disabled={disabled}
      onChange={event => onChange(event.target.value as SourceMode)}
      className="min-w-0 rounded-md border border-stone-300 bg-white px-2 py-1 text-[11px] disabled:opacity-50"
    >
      {(Object.keys(labels) as SourceMode[]).map(mode => <option key={mode} value={mode}>{labels[mode]}</option>)}
    </select>
  </label>
);
