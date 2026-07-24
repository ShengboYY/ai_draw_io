'use client';

export type SourceOption = {
  versionId: string;
  label: string;
  group: 'CURRENT_DIAGRAM' | 'CHARTBOOK' | 'PERSONAL_LIBRARY';
};

const groupLabels: Record<SourceOption['group'], string> = {
  CURRENT_DIAGRAM: '本图资料',
  CHARTBOOK: '图表册共享资料',
  PERSONAL_LIBRARY: '个人资料库',
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
    <section className="text-xs" aria-label="选择资料版本">
      <p className="font-medium text-zinc-700">指定资料版本{selected.size ? `（${selected.size}）` : ''}</p>
      <div className="mt-2 space-y-3 rounded-lg border border-stone-200 bg-white p-3">
        {activeScopeLabels.length > 0 && <p className="text-zinc-500">自动范围：{activeScopeLabels.join('、')}</p>}
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
