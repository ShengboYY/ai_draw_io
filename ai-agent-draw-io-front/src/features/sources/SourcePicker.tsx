'use client';

export type SourceOption = {
  versionId: string;
  label: string;
  group: 'CURRENT_DIAGRAM' | 'CHARTBOOK' | 'PERSONAL_LIBRARY';
};

const groupLabels: Record<SourceOption['group'], string> = {
  CURRENT_DIAGRAM: 'Current diagram',
  CHARTBOOK: 'Shared chartbook',
  PERSONAL_LIBRARY: 'Personal library',
};

/** Allows version selection only; the backend re-validates every selected version. */
export const SourcePicker = ({ options, selectedVersionIds, onChange, activeScopeLabels = [], disabled }: {
  options: SourceOption[];
  selectedVersionIds: string[];
  onChange: (versionIds: string[]) => void;
  activeScopeLabels?: string[];
  disabled?: boolean;
}) => {
  const selected = new Set(selectedVersionIds);
  const toggle = (versionId: string) => {
    const next = new Set(selected);
    if (next.has(versionId)) next.delete(versionId); else next.add(versionId);
    onChange([...next]);
  };
  const groups = (Object.keys(groupLabels) as SourceOption['group'][])
    .map(group => [group, options.filter(option => option.group === group)] as const)
    .filter(([, items]) => items.length > 0);

  if (groups.length === 0 && activeScopeLabels.length === 0) return null;
  return (
    <section className="text-[11px]" aria-label="Choose source versions">
      <p className="font-medium text-zinc-700">Choose sources{selected.size ? ` (${selected.size})` : ''}</p>
      <div className="mt-1.5 space-y-2.5 rounded-lg border border-stone-200 bg-white p-2.5">
        {activeScopeLabels.length > 0 && <p className="text-zinc-500">Automatic: {activeScopeLabels.join(', ')}</p>}
        {groups.map(([group, items]) => <fieldset key={group}>
          <legend className="mb-1 text-zinc-500">{groupLabels[group]}</legend>
          <div className="flex flex-wrap gap-x-3 gap-y-1.5">
            {items.map(option => <label key={option.versionId} className="flex max-w-full items-center gap-1 text-zinc-700">
              <input type="checkbox" checked={selected.has(option.versionId)} disabled={disabled} onChange={() => toggle(option.versionId)} />
              <span className="truncate">{option.label}</span>
            </label>)}
          </div>
        </fieldset>)}
      </div>
    </section>
  );
};
