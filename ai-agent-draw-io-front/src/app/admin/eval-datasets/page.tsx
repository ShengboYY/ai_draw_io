'use client';

import { useEffect, useState } from 'react';
import { agentApi } from '@/api/agent';
import type { EvalDatasetCoverageDTO, EvalDatasetDTO, EvalDatasetMemberDTO, EvalDatasetVersionDTO, PublishedEvalCaseDTO } from '@/types/api';
import { AdminPageHeading, AdminShell } from '../admin-shell';
import { EvaluationWorkspace } from '../evaluation-workspace';

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
  const showError = (reason: unknown) => setError(reason instanceof Error ? reason.message : 'Dataset operation failed');
  useEffect(() => { agentApi.adminListEvalDatasets().then(({ data }) => setDatasets(data || []))
    .catch((reason) => setError(reason instanceof Error ? reason.message : 'Dataset operation failed')); }, []);
  useEffect(() => { agentApi.adminListPublishedEvalCases().then(({ data }) => setPublishedCases((data || []).filter((item) => !item.retiredAt)))
    .catch((reason) => setError(reason instanceof Error ? reason.message : 'Dataset operation failed')); }, []);
  const open = (dataset: EvalDatasetDTO) => { setSelected(dataset); setCoverage(null); agentApi.adminListEvalDatasetVersions(dataset.id).then(({ data }) => setVersions(data || [])).catch(showError); };
  const selectVersion = (version: EvalDatasetVersionDTO) => { setCurrent(version); setMembers(version.members.map((m) => `${m.caseId}@${m.caseVersion}`).join('\n')); setCoverage(null); };
  const parseMembers = (): EvalDatasetMemberDTO[] => members.split('\n').map((line) => line.trim()).filter(Boolean).map((line) => {
    const split = line.lastIndexOf('@'); if (split < 1) throw new Error(`Invalid member: ${line}`);
    return { caseId: line.slice(0, split), caseVersion: line.slice(split + 1) };
  });
  const createDataset = () => { const name = window.prompt('Dataset name:'); if (!name) return; const requestedClass = window.prompt('Class: DEV or CORE', 'DEV')?.toUpperCase(); if (requestedClass !== 'DEV' && requestedClass !== 'CORE') return; agentApi.adminCreateEvalDataset(name, requestedClass).then(({ data }) => { setDatasets((all) => [data, ...all]); open(data); }).catch(showError); };
  const createVersion = () => { if (!selected) return; const version = window.prompt('Version (for example core-v2):'); if (!version) return; try { agentApi.adminCreateEvalDatasetVersion(selected.id, version, parseMembers()).then(({ data }) => { setVersions((all) => [data, ...all]); selectVersion(data); }).catch(showError); } catch (reason) { showError(reason); } };
  const update = (value: EvalDatasetVersionDTO) => { setCurrent(value); setVersions((all) => all.map((item) => item.version === value.version ? value : item)); };
  const save = () => { if (!selected || !current) return; try { agentApi.adminReplaceEvalDatasetMembers(selected.id, current.version, current.revision, parseMembers()).then(({ data }) => update(data)).catch(showError); } catch (reason) { showError(reason); } };
  const action = (name: 'validate' | 'publish') => selected && current
    && (name !== 'publish' || window.confirm(`Freeze ${selected.name}@${current.version} with ${current.members.length} pinned Case versions?`))
    && agentApi.adminEvalDatasetAction(selected.id, current.version, name).then(({ data }) => update(data)).catch(showError);
  const promote = () => { if (!selected || !current) return; const targetId = window.prompt('Target dev/core dataset id:'); const targetVersion = window.prompt('New target version:'); if (!targetId || !targetVersion) return; agentApi.adminCloneEvalDatasetVersion(selected.id, current.version, targetId, targetVersion).then(() => window.alert('Draft version cloned into the target dataset.')).catch(showError); };
  const addPublishedCase = () => {
    if (!selectedPublishedCase) return;
    // Preserve the text editor as the canonical draft while making the common add flow selectable.
    const existing = new Set(members.split('\n').map((line) => line.trim()).filter(Boolean));
    existing.add(selectedPublishedCase);
    setMembers([...existing].join('\n'));
    setSelectedPublishedCase('');
  };
  const prior = current ? versions.find((item) => item.status === 'PUBLISHED' && item.version !== current.version) : undefined;
  const currentKeys = new Set(current?.members.map((member) => `${member.caseId}@${member.caseVersion}`) || []);
  const priorKeys = new Set(prior?.members.map((member) => `${member.caseId}@${member.caseVersion}`) || []);
  const added = [...currentKeys].filter((key) => !priorKeys.has(key));
  const removed = [...priorKeys].filter((key) => !currentKeys.has(key));
  return <AdminShell active="datasets"><AdminPageHeading eyebrow="Evaluation · Step 2" title="Curate a Dataset"
    description="Group exact published Case versions into one reproducible test suite, then inspect whether its routes, agents and risks are balanced."
    action={<button onClick={createDataset} className="rounded-lg bg-zinc-800 px-4 py-2 text-sm font-medium text-white">New dataset</button>} />
    <EvaluationWorkspace active="datasets" />
    {error && <p className="mb-4 rounded-lg bg-rose-50 p-3 text-sm text-rose-700">{error}</p>}
    {!error && datasets.length === 0 && <section className="mb-5 rounded-xl border border-dashed border-stone-300 bg-stone-50 px-5 py-10 text-center"><p className="text-sm font-medium text-zinc-700">Create a Dataset after publishing at least one Case</p><p className="mt-1 text-xs text-zinc-500">A Dataset pins Case versions so every Eval Run uses the same evidence.</p><button onClick={createDataset} className="mt-4 text-sm font-semibold text-zinc-700 hover:underline">Create Dataset →</button></section>}
    <div className="grid gap-5 lg:grid-cols-[16rem_16rem_minmax(0,1fr)]">
      <Panel title="Datasets">{datasets.map((item) => <button key={item.id} onClick={() => open(item)} className="block w-full border-b border-stone-100 p-3 text-left text-sm"><b>{item.name}</b><span className="ml-2 text-xs text-zinc-400">{item.datasetClass}</span></button>)}</Panel>
      <Panel title="Versions"><button disabled={!selected} onClick={createVersion} className="m-3 rounded bg-zinc-800 px-3 py-1.5 text-xs text-white disabled:opacity-40">New version</button>{versions.map((item) => <button key={item.version} onClick={() => selectVersion(item)} className="block w-full border-t border-stone-100 p-3 text-left text-sm">{item.version}<span className="ml-2 text-xs text-zinc-400">{item.status}</span></button>)}</Panel>
      <Panel title={current ? `${current.version} · revision ${current.revision}` : 'Member editor'}><div className="flex flex-col gap-2 border-b border-stone-200 bg-stone-50 p-3 sm:flex-row"><select aria-label="Published Case to add" disabled={!current} value={selectedPublishedCase} onChange={(event) => setSelectedPublishedCase(event.target.value)} className="min-w-0 flex-1 rounded border border-stone-200 bg-white px-2 py-2 text-xs disabled:opacity-50"><option value="">Select a published Case…</option>{publishedCases.map((item) => <option key={`${item.caseId}@${item.caseVersion}`} value={`${item.caseId}@${item.caseVersion}`}>{item.caseId}@{item.caseVersion}</option>)}</select><Button disabled={!current || !selectedPublishedCase} onClick={addPublishedCase}>Add Case</Button></div><textarea aria-label="Pinned case versions" value={members} onChange={(event) => setMembers(event.target.value)} placeholder="case-id@version" className="min-h-64 w-full border-b border-stone-200 p-4 font-mono text-xs" />
        <div className="flex flex-wrap gap-2 p-3"><Button onClick={save}>Save members</Button><Button onClick={() => action('validate')}>Validate</Button><Button onClick={() => action('publish')}>Publish</Button><Button onClick={promote}>Promote / clone</Button><Button onClick={() => selected && current && agentApi.adminEvalDatasetCoverage(selected.id, current.version).then(({ data }) => setCoverage(data)).catch(showError)}>Coverage</Button></div>
        {current && <div className="border-t border-stone-200 p-4 text-xs text-zinc-500"><b className="text-zinc-700">Diff from {prior?.version || 'empty baseline'}</b><div className="mt-1 text-emerald-700">Added: {added.join(', ') || 'none'}</div><div className="mt-1 text-rose-700">Removed: {removed.join(', ') || 'none'}</div></div>}
        {coverage && <div className="grid gap-3 border-t border-stone-200 p-4 sm:grid-cols-2"><Coverage title="Routes" values={coverage.routes} /><Coverage title="Risks" values={coverage.risks} /><Coverage title="Languages" values={coverage.languages} /><Coverage title="Diagram types" values={coverage.diagramTypes} /><Coverage title="Agents" values={coverage.agents} /></div>}
      </Panel>
    </div>
  </AdminShell>;
}

function Panel({ title, children }: { title: string; children: React.ReactNode }) { return <section className="overflow-hidden rounded-lg border border-stone-200 bg-white"><h2 className="border-b border-stone-200 px-4 py-3 text-sm font-semibold">{title}</h2>{children}</section>; }
function Button({ children, onClick, disabled = false }: { children: React.ReactNode; onClick: () => void; disabled?: boolean }) { return <button type="button" disabled={disabled} onClick={onClick} className="rounded bg-zinc-800 px-3 py-1.5 text-xs text-white disabled:opacity-40">{children}</button>; }
function Coverage({ title, values }: { title: string; values: Record<string, number> }) { return <div><h3 className="text-xs font-semibold">{title}</h3><div className="mt-1 text-xs text-zinc-500">{Object.entries(values).map(([key, value]) => `${key}: ${value}`).join(' · ') || 'No data'}</div></div>; }
