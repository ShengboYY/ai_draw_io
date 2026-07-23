'use client';

import {
  sourceUsePreferenceLabel,
  type SourceUsePreference,
} from './source-intent';

const descriptions: Record<SourceUsePreference, string> = {
  AUTO: '由 Router 根据图片和请求自动选择。',
  DIRECT: '尽量按图片中的结构和内容还原。',
  DIRECT_AND_RETRIEVAL: '还原图片，并允许从已授权资料中补充内容。',
};

/** Offers an explicit override while keeping automatic routing as the default. */
export const SourceUseControl = ({ value, onChange, disabled }: {
  value: SourceUsePreference;
  onChange: (value: SourceUsePreference) => void;
  disabled?: boolean;
}) => (
  <div className="mb-2 rounded-lg border border-stone-200 bg-stone-50 px-3 py-2">
    <div className="flex flex-wrap items-center gap-2">
      <label className="flex items-center gap-2 text-xs text-zinc-700">
        <span className="font-medium">图片转换</span>
        <select
          aria-label="图片转换方式"
          value={value}
          disabled={disabled}
          onChange={event => onChange(event.target.value as SourceUsePreference)}
          className="rounded-md border border-stone-300 bg-white px-2 py-1 text-xs disabled:opacity-50"
        >
          {(['AUTO', 'DIRECT', 'DIRECT_AND_RETRIEVAL'] as SourceUsePreference[]).map(preference => (
            <option key={preference} value={preference}>
              {sourceUsePreferenceLabel(preference)}
              {preference === 'AUTO' ? '（推荐）' : ''}
            </option>
          ))}
        </select>
      </label>
      <span className="text-[11px] text-zinc-500">{descriptions[value]}</span>
    </div>
  </div>
);
