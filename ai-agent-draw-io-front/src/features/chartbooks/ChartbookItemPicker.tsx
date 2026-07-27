'use client';

export type ChartbookPickerOption = { id: string; title: string; caption?: string };

/** Checkbox list of diagrams or library files, shared by every chartbook picker. */
export const ChartbookItemPicker = ({
  options,
  selectedIds,
  onToggle,
}: {
  options: ChartbookPickerOption[];
  selectedIds: string[];
  onToggle: (id: string) => void;
}) => (
  <ul className="max-h-44 space-y-1 overflow-y-auto rounded-lg border border-stone-200 bg-stone-50 p-1.5">
    {options.map(option => (
      <li key={option.id}>
        <label className="flex cursor-pointer items-center gap-2.5 rounded-md px-2 py-1.5 transition hover:bg-white">
          <input
            type="checkbox"
            checked={selectedIds.includes(option.id)}
            onChange={() => onToggle(option.id)}
            className="h-4 w-4 shrink-0 accent-zinc-800"
          />
          <span className="min-w-0 flex-1 truncate text-sm text-zinc-700">{option.title}</span>
          {option.caption && <span className="shrink-0 text-xs text-zinc-400">{option.caption}</span>}
        </label>
      </li>
    ))}
  </ul>
);
