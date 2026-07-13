'use client';

import { useEffect, useState } from 'react';
import { agentApi } from '@/api/agent';
import type { EvalDatasetCoverageDTO, EvalDatasetDTO, EvalDatasetMemberDTO, EvalDatasetVersionDTO, EvaluationTarget, PublishedEvalCaseDTO } from '@/types/api';
import { AdminShell } from '../admin-shell';
import { EvaluationWorkspace } from '../evaluation-workspace';
import { Btn, EmptyState, ErrorNote, Field, inputCls, StatusBadge } from '../eval-ui';

export default function AdminEvalDatasetsPage() {
  const [datasets, setDatasets] = useState<EvalDatasetDTO[]>([]);
  const [selected, setSelected] = useState<EvalDatasetDTO | null>(null);
  const [versions, setVersions] = useState<EvalDatasetVersionDTO[]>([]);
  const [current, setCurrent] = useState<EvalDatasetVersionDTO | null>(null);
  const [members, setMembers] = useState('');
  const [publishedCases, setPublishedCases] = useState<PublishedEvalCaseDTO[]>([]);
  const [selectedPublishedCase, setSelectedPublishedCase] = useState('');
  const [coverage, setCoverage] = useState<EvalDatasetCoverageDTO | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Inline creation forms replace the old window.prompt flows.
  const [datasetDraft, setDatasetDraft] = useState<{ name: string; datasetClass: 'DEV' | 'CORE' } | null>(null);
  const [versionDraft, setVersionDraft] = useState<string | null>(null);
  const [promoteDraft, setPromoteDraft] = useState<{ targetId: string; targetVersion: string } | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [targetFilter, setTargetFilter] = useState<EvaluationTarget | ''>('');

  const showError = (reason: unknown) => setError(reason instanceof Error ? reason.message : 'Dataset operation failed');

  useEffect(() => { agentApi.adminListEvalDatasets().then(({ data }) => setDatasets(data || [])).catch(showError); }, []);
  useEffect(() => {
    agentApi.adminListPublishedEvalCases()
      .then(({ data }) => setPublishedCases((data || []).filter((item) => !item.retiredAt)))
      .catch(showError);
  }, []);

  const open = (dataset: EvalDatasetDTO) => {
    setSelected(dataset); setCurrent(null); setMembers(''); setCoverage(null); setNotice(null);
    agentApi.adminListEvalDatasetVersions(dataset.id).then(({ data }) => setVersions(data || [])).catch(showError);
  };

  const selectVersion = (version: EvalDatasetVersionDTO) => {
    setCurrent(version);
    setMembers(version.members.map((member) => `${member.caseId}@${member.caseVersion}`).join('\n'));
    setCoverage(null); setNotice(null);
  };

  const parseMembers = (): EvalDatasetMemberDTO[] => members.split('\n').map((line) => line.trim()).filter(Boolean).map((line) => {
    const split = line.lastIndexOf('@');
    if (split < 1) throw new Error(`Invalid member: ${line} — use case-id@version`);
    return { caseId: line.slice(0, split), caseVersion: line.slice(split + 1) };
  });

  const createDataset = () => {
    if (!datasetDraft?.name.trim()) return;
    agentApi.adminCreateEvalDataset(datasetDraft.name.trim(), datasetDraft.datasetClass)
      .then(({ data }) => { setDatasets((all) => [data, ...all]); setDatasetDraft(null); open(data); })
      .catch(showError);
  };

  const createVersion = () => {
    if (!selected || !versionDraft?.trim()) return;
    try {
      agentApi.adminCreateEvalDatasetVersion(selected.id, versionDraft.trim(), parseMembers())
        .then(({ data }) => { setVersions((all) => [data, ...all]); setVersionDraft(null); selectVersion(data); })
        .catch(showError);
    } catch (reason) { showError(reason); }
  };

  const update = (value: EvalDatasetVersionDTO) => {
    setCurrent(value);
    setVersions((all) => all.map((item) => item.version === value.version ? value : item));
  };

  const save = () => {
    if (!selected || !current) return;
    try {
      agentApi.adminReplaceEvalDatasetMembers(selected.id, current.version, current.revision, parseMembers())
        .then(({ data }) => { update(data); setNotice('Members saved.'); })
        .catch(showError);
    } catch (reason) { showError(reason); }
  };

  const action = (name: 'validate' | 'publish') => selected && current
    && (name !== 'publish' || window.confirm(`Freeze ${selected.name}@${current.version} with ${current.members.length} pinned Case versions? A published version can never change.`))
    && agentApi.adminEvalDatasetAction(selected.id, current.version, name)
      .then(({ data }) => { update(data); setNotice(name === 'publish' ? 'Version published.' : 'Validation finished.'); })
      .catch(showError);

  const promote = () => {
    if (!selected || !current || !promoteDraft?.targetId.trim() || !promoteDraft.targetVersion.trim()) return;
    agentApi.adminCloneEvalDatasetVersion(selected.id, current.version, promoteDraft.targetId.trim(), promoteDraft.targetVersion.trim())
      .then(() => { setPromoteDraft(null); setNotice('Draft version cloned into the target dataset.'); })
      .catch(showError);
  };

  const addPublishedCase = () => {
    if (!selectedPublishedCase) return;
    // Preserve the text editor as the canonical draft while making the common add flow selectable.
    const existing = new Set(members.split('\n').map((line) => line.trim()).filter(Boolean));
    existing.add(selectedPublishedCase);
    setMembers([...existing].join('\n'));
    setSelectedPublishedCase('');
  };

  const loadCoverage = () => selected && current
    && agentApi.adminEvalDatasetCoverage(selected.id, current.version).then(({ data }) => setCoverage(data)).catch(showError);

  const prior = current ? versions.find((item) => item.status === 'PUBLISHED' && item.version !== current.version) : undefined;
  const currentKeys = new Set(current?.members.map((member) => `${member.caseId}@${member.caseVersion}`) || []);
  const priorKeys = new Set(prior?.members.map((member) => `${member.caseId}@${member.caseVersion}`) || []);
  const added = [...currentKeys].filter((key) => !priorKeys.has(key));
  const removed = [...priorKeys].filter((key) => !currentKeys.has(key));
  const currentIsPublished = current?.status === 'PUBLISHED';

  return (
    <AdminShell active="datasets">
      <EvaluationWorkspace active="datasets"
        title="Curate a Dataset"
        description="A Dataset pins exact published Case versions into one reproducible test suite, so every Eval Run replays the same evidence."
        action={<Btn size="md" onClick={() => setDatasetDraft(datasetDraft ? null : { name: '', datasetClass: 'DEV' })}>{datasetDraft ? 'Cancel' : '+ New dataset'}</Btn>}
      />

      <ErrorNote message={error} />

      <div className="mb-4 max-w-xs">
        <Field label="Filter by evaluation target">
          <select value={targetFilter} onChange={(event) => setTargetFilter(event.target.value as EvaluationTarget | '')} className={inputCls}>
            <option value="">All targets</option>
            {(['FULL_AGENT', 'INTENT_ROUTER', 'DRAWING_QUALITY'] as EvaluationTarget[]).map((value) => <option key={value} value={value}>{value.replaceAll('_', ' ')}</option>)}
          </select>
        </Field>
      </div>

      {datasetDraft && (
        <section className="mb-5 flex flex-wrap items-end gap-3 rounded-xl border border-stone-200 bg-white p-4 shadow-sm" aria-label="Create dataset">
          <Field label="Dataset name" className="w-64">
            <input autoFocus value={datasetDraft.name} onChange={(event) => setDatasetDraft({ ...datasetDraft, name: event.target.value })} placeholder="e.g. core-regressions" className={inputCls} />
          </Field>
          <Field label="Class" className="w-56">
            <select value={datasetDraft.datasetClass} onChange={(event) => setDatasetDraft({ ...datasetDraft, datasetClass: event.target.value as 'DEV' | 'CORE' })} className={inputCls}>
              <option value="DEV">DEV — day-to-day iteration</option>
              <option value="CORE">CORE — release gate suite</option>
            </select>
          </Field>
          <Btn onClick={createDataset} disabled={!datasetDraft.name.trim()}>Create dataset</Btn>
        </section>
      )}

      {!error && datasets.length === 0 && !datasetDraft && (
        <EmptyState
          title="Create a Dataset after publishing at least one Case"
          hint="A Dataset pins Case versions so every Eval Run uses the same evidence."
          action={<Btn onClick={() => setDatasetDraft({ name: '', datasetClass: 'DEV' })}>Create Dataset</Btn>}
        />
      )}

      <div className="grid gap-5 lg:grid-cols-[16rem_16rem_minmax(0,1fr)]">
        <Panel title="1 · Datasets" hint="Pick a suite to work on">
          {datasets.filter((item) => !targetFilter || item.evaluationTarget === targetFilter).map((item) => {
            const isSelected = selected?.id === item.id;
            return (
              <button
                key={item.id}
                onClick={() => open(item)}
                aria-pressed={isSelected}
                className={`block w-full border-b border-stone-100 px-4 py-3 text-left text-sm transition last:border-b-0 ${isSelected ? 'bg-zinc-900 text-white' : 'hover:bg-stone-50'}`}
              >
                <span className="font-medium">{item.name}</span>
                <span className={`ml-2 text-xs ${isSelected ? 'text-zinc-300' : 'text-zinc-400'}`}>{item.datasetClass}</span>
                {item.evaluationTarget && <span className={`mt-1 block text-[10px] ${isSelected ? 'text-zinc-300' : 'text-zinc-400'}`}>{item.evaluationTarget.replaceAll('_', ' ')}</span>}
              </button>
            );
          })}
          {datasets.length === 0 && <PanelHint text="No datasets yet." />}
        </Panel>

        <Panel title="2 · Versions" hint="Each version freezes one member list">
          <div className="border-b border-stone-100 p-3">
            {versionDraft === null
              ? <Btn variant="secondary" disabled={!selected} onClick={() => setVersionDraft('')}>+ New version</Btn>
              : (
                <div className="space-y-2">
                  <input autoFocus value={versionDraft} onChange={(event) => setVersionDraft(event.target.value)} placeholder="e.g. core-v2" className={inputCls} />
                  <div className="flex gap-2">
                    <Btn onClick={createVersion} disabled={!versionDraft.trim()}>Create</Btn>
                    <Btn variant="secondary" onClick={() => setVersionDraft(null)}>Cancel</Btn>
                  </div>
                </div>
              )}
          </div>
          {versions.map((item) => {
            const isSelected = current?.version === item.version;
            return (
              <button
                key={item.version}
                onClick={() => selectVersion(item)}
                aria-pressed={isSelected}
                className={`flex w-full items-center justify-between gap-2 border-b border-stone-100 px-4 py-3 text-left text-sm transition last:border-b-0 ${isSelected ? 'bg-zinc-900 text-white' : 'hover:bg-stone-50'}`}
              >
                <span className="truncate font-medium">{item.version}</span>
                <StatusBadge value={item.status} />
              </button>
            );
          })}
          {selected && versions.length === 0 && <PanelHint text="No versions yet — create one." />}
          {!selected && <PanelHint text="Select a dataset first." />}
        </Panel>

        <Panel
          title={current ? `Members of ${current.version}` : '3 · Member editor'}
          hint={current ? `revision ${current.revision} · one case-id@version per line` : 'Select a version to edit its pinned Cases'}
        >
          <div className="flex flex-col gap-2 border-b border-stone-200 bg-stone-50 p-3 sm:flex-row">
            <select
              aria-label="Published Case to add"
              disabled={!current}
              value={selectedPublishedCase}
              onChange={(event) => setSelectedPublishedCase(event.target.value)}
              className={`${inputCls} min-w-0 flex-1 text-xs`}
            >
              <option value="">Add a published Case…</option>
              {publishedCases.filter((item) => !selected?.evaluationTarget || item.evaluationTarget === selected.evaluationTarget).map((item) => (
                <option key={`${item.caseId}@${item.caseVersion}`} value={`${item.caseId}@${item.caseVersion}`}>{item.caseId}@{item.caseVersion}</option>
              ))}
            </select>
            <Btn disabled={!current || !selectedPublishedCase} onClick={addPublishedCase}>Add Case</Btn>
          </div>
          <textarea
            aria-label="Pinned case versions"
            value={members}
            onChange={(event) => setMembers(event.target.value)}
            placeholder={'One pinned member per line:\ncase-id@version'}
            disabled={!current}
            className="min-h-64 w-full border-b border-stone-200 p-4 font-mono text-xs text-zinc-800 focus:outline-none disabled:bg-stone-50"
          />
          <div className="flex flex-wrap items-center gap-2 p-3">
            <Btn onClick={save} disabled={!current || currentIsPublished}>Save members</Btn>
            <Btn variant="secondary" onClick={() => action('validate')} disabled={!current}>Validate</Btn>
            <Btn variant="secondary" onClick={() => action('publish')} disabled={!current || currentIsPublished}>Publish (freeze)</Btn>
            <Btn variant="secondary" onClick={() => setPromoteDraft(promoteDraft ? null : { targetId: '', targetVersion: '' })} disabled={!current}>Clone to another dataset…</Btn>
            <Btn variant="secondary" onClick={loadCoverage} disabled={!current}>Check coverage</Btn>
            {notice && <span className="text-xs text-emerald-700">{notice}</span>}
          </div>
          {currentIsPublished && <p className="border-t border-stone-100 px-4 py-2 text-[11px] text-zinc-400">This version is published and immutable — clone it to a new version to make changes.</p>}
          {promoteDraft && current && (
            <div className="flex flex-wrap items-end gap-3 border-t border-stone-200 bg-stone-50 p-3" aria-label="Clone version to another dataset">
              <Field label="Target dataset id" className="w-56">
                <input value={promoteDraft.targetId} onChange={(event) => setPromoteDraft({ ...promoteDraft, targetId: event.target.value })} className={inputCls} />
              </Field>
              <Field label="New target version" className="w-40">
                <input value={promoteDraft.targetVersion} onChange={(event) => setPromoteDraft({ ...promoteDraft, targetVersion: event.target.value })} className={inputCls} />
              </Field>
              <Btn onClick={promote} disabled={!promoteDraft.targetId.trim() || !promoteDraft.targetVersion.trim()}>Clone draft</Btn>
            </div>
          )}
          {current && (
            <div className="border-t border-stone-200 p-4 text-xs text-zinc-500">
              <b className="text-zinc-700">Changes vs {prior?.version || 'empty baseline'}</b>
              <div className="mt-1 text-emerald-700">Added: {added.join(', ') || 'none'}</div>
              <div className="mt-1 text-rose-700">Removed: {removed.join(', ') || 'none'}</div>
            </div>
          )}
          {coverage && (
            <div className="grid gap-3 border-t border-stone-200 p-4 sm:grid-cols-2">
              <Coverage title="Routes" values={coverage.routes} />
              <Coverage title="Risks" values={coverage.risks} />
              <Coverage title="Languages" values={coverage.languages} />
              <Coverage title="Diagram types" values={coverage.diagramTypes} />
              <Coverage title="Agents" values={coverage.agents} />
            </div>
          )}
        </Panel>
      </div>
    </AdminShell>
  );
}

function Panel({ title, hint, children }: { title: string; hint?: string; children: React.ReactNode }) {
  return (
    <section className="self-start overflow-hidden rounded-xl border border-stone-200 bg-white shadow-sm">
      <div className="border-b border-stone-200 px-4 py-3">
        <h2 className="text-sm font-semibold text-zinc-900">{title}</h2>
        {hint && <p className="mt-0.5 text-[11px] text-zinc-400">{hint}</p>}
      </div>
      {children}
    </section>
  );
}

function PanelHint({ text }: { text: string }) {
  return <p className="px-4 py-6 text-center text-xs text-zinc-400">{text}</p>;
}

function Coverage({ title, values }: { title: string; values: Record<string, number> }) {
  return (
    <div>
      <h3 className="text-xs font-semibold text-zinc-700">{title}</h3>
      <div className="mt-1 text-xs text-zinc-500">
        {Object.entries(values).map(([key, value]) => `${key}: ${value}`).join(' · ') || 'No data'}
      </div>
    </div>
  );
}
