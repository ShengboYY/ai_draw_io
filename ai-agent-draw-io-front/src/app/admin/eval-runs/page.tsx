'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { agentApi } from '@/api/agent';
import type { EvalDatasetDTO, EvalDatasetVersionDTO, EvalRunSummaryDTO } from '@/types/api';
import { AdminPageHeading, AdminShell } from '../admin-shell';
import { EvaluationWorkspace } from '../evaluation-workspace';

type RunMode = 'MODE_B' | 'MODE_C' | 'RELEASE';
type RunDraft = { mode: RunMode; datasetId: string; datasetVersion: string; gitSha: string; baselineRef: string;
  executionProfileHash: string; repetitions: string; maxEstimatedCost: string };
const emptyDraft: RunDraft = { mode: 'MODE_B', datasetId: '', datasetVersion: '', gitSha: '', baselineRef: '', executionProfileHash: '', repetitions: '1', maxEstimatedCost: '10' };

export default function AdminEvalRunsPage() {
  const router = useRouter(); const [runs, setRuns] = useState<EvalRunSummaryDTO[]>([]);
  const [datasets, setDatasets] = useState<EvalDatasetDTO[]>([]); const [datasetVersions, setDatasetVersions] = useState<EvalDatasetVersionDTO[]>([]);
  const [draft, setDraft] = useState<RunDraft>(emptyDraft); const [showForm, setShowForm] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { let active = true; const load = () => agentApi.adminListEvalRuns().then(({ data }) => active && setRuns(data || [])).catch((reason) => active && setError(reason instanceof Error ? reason.message : 'Failed to load runs')); load(); const timer = window.setInterval(load, 3000); return () => { active = false; window.clearInterval(timer); }; }, []);
  useEffect(() => { agentApi.adminListEvalDatasets().then(({ data }) => setDatasets(data || [])).catch((reason) => setError(reason instanceof Error ? reason.message : 'Failed to load Datasets')); }, []);
  const update = (field: keyof RunDraft, value: string) => setDraft((current) => ({ ...current, [field]: value }));
  const selectDataset = (datasetId: string) => {
    setDraft((current) => ({ ...current, datasetId, datasetVersion: '' }));
    setDatasetVersions([]);
    if (datasetId) agentApi.adminListEvalDatasetVersions(datasetId)
      .then(({ data }) => setDatasetVersions((data || []).filter((version) => version.status === 'PUBLISHED')))
      .catch((reason) => setError(reason instanceof Error ? reason.message : 'Failed to load Dataset versions'));
  };
  const start = () => {
    if (!draft.datasetId || !draft.datasetVersion || !draft.gitSha || (draft.mode !== 'MODE_B' && !draft.executionProfileHash)) { setError('Dataset, version, candidate Git SHA and a live execution profile hash are required.'); return; }
    const repetitions = Number(draft.repetitions); const maxEstimatedCost = Number(draft.maxEstimatedCost);
    // The same immutable manifest intentionally resolves to the same idempotency key.
    const idempotencyKey = [draft.mode, draft.datasetId, draft.datasetVersion, draft.gitSha, draft.baselineRef, repetitions].join(':');
    agentApi.adminStartEvalRun({ mode: draft.mode, datasetId: draft.datasetId, datasetVersion: draft.datasetVersion,
      gitSha: draft.gitSha, candidateRef: draft.gitSha, baselineRef: draft.baselineRef || undefined,
      executionProfileHash: draft.executionProfileHash || undefined, repetitions, maxEstimatedCost,
      minimumCases: draft.mode === 'MODE_B' ? 1 : 20, maximumErrorRate: 0.05,
      minimumPairedCases: draft.mode === 'RELEASE' ? 20 : 1, regressionThreshold: 0.01, idempotencyKey })
      .then(({ data }) => router.push(`/admin/eval-runs/${encodeURIComponent(data.id)}`))
      .catch((reason) => setError(reason instanceof Error ? reason.message : 'Start failed'));
  };
  return <AdminShell active="evalRuns"><AdminPageHeading eyebrow="Evaluation · Step 3" title="Run and inspect the Agent"
    description="Replay a frozen Dataset, inspect each failed Episode, then turn what you learned into the next Case or fix."
    action={<button onClick={() => setShowForm((value) => !value)} className="rounded-lg bg-zinc-800 px-4 py-2 text-sm font-medium text-white">New Eval Run</button>} />
    <EvaluationWorkspace active="runs" />
    <section className="mb-5 rounded-xl border border-emerald-200 bg-emerald-50 p-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between"><div><p className="text-sm font-semibold text-emerald-950">Recommended now: Recorded-model replay (Mode B)</p><p className="mt-1 text-xs leading-5 text-emerald-800">No live-model credentials or production traffic required. It replays recorded model responses through the real router, tools, XML mutation and graders.</p></div><button onClick={() => { setDraft((current) => ({ ...current, mode: 'MODE_B' })); setShowForm(true); }} className="shrink-0 rounded-lg border border-emerald-300 bg-white px-3 py-2 text-xs font-semibold text-emerald-900">Start Mode B</button></div>
    </section>
    {error && <p className="mb-4 rounded-lg bg-rose-50 p-3 text-sm text-rose-700">{error}</p>}
    {showForm && <section className="mb-5 rounded-lg border border-stone-200 bg-white p-4"><div className="grid gap-3 md:grid-cols-4">
      <label className="text-xs text-zinc-500">Mode<select value={draft.mode} onChange={(event) => update('mode', event.target.value)} className="mt-1 w-full rounded border border-stone-200 p-2 text-sm"><option value="MODE_B">Mode B · recorded replay</option><option value="MODE_C">Mode C · live model</option><option value="RELEASE">Release · baseline comparison</option></select></label>
      <FormSelect label="Dataset" value={draft.datasetId} placeholder="Select Dataset…" options={datasets.map((dataset) => ({ value: dataset.id, label: `${dataset.name} · ${dataset.datasetClass}` }))} onChange={selectDataset} />
      <FormSelect label="Published version" value={draft.datasetVersion} placeholder={draft.datasetId ? 'Select version…' : 'Choose Dataset first'} disabled={!draft.datasetId} options={datasetVersions.map((version) => ({ value: version.version, label: version.version }))} onChange={(value) => update('datasetVersion', value)} />
      <FormInput label="Candidate Git SHA" value={draft.gitSha} onChange={(value) => update('gitSha', value)} />
      <FormInput label="Execution profile hash" value={draft.executionProfileHash} onChange={(value) => update('executionProfileHash', value)} />
      <FormInput label="Repetitions" value={draft.repetitions} onChange={(value) => update('repetitions', value)} />
      <FormInput label="Budget (USD)" value={draft.maxEstimatedCost} onChange={(value) => update('maxEstimatedCost', value)} />
      {draft.mode === 'RELEASE' && <FormInput label="Baseline Run ID" value={draft.baselineRef} onChange={(value) => update('baselineRef', value)} />}
    </div><button onClick={start} className="mt-4 rounded bg-zinc-800 px-4 py-2 text-sm text-white">Start {draft.mode} run</button>
      {draft.mode !== 'MODE_B' && <p className="mt-2 text-xs text-zinc-500">Live runs require configured provider credentials. Release additionally requires Release Owner access, calibrated Judge evidence and sequestered readiness.</p>}
    </section>}
    <div className="space-y-3">{!error && runs.length === 0 && <div className="rounded-xl border border-dashed border-stone-300 bg-stone-50 px-5 py-10 text-center"><p className="text-sm font-medium text-zinc-700">No evaluation history yet</p><p className="mt-1 text-xs text-zinc-500">Publish a Dataset, then start a Mode B run to get your first repeatable baseline.</p></div>}{runs.map((run) => <Link key={run.id} href={`/admin/eval-runs/${encodeURIComponent(run.id)}`} className="block rounded-lg border border-stone-200 bg-white p-4 shadow-sm">
      <div className="flex items-start justify-between gap-3"><div><div className="font-medium">{run.datasetId}@{run.datasetVersion}</div><div className="mt-1 font-mono text-[11px] text-zinc-400">{run.mode} · {run.gitSha} · {run.id}</div></div><Status value={run.status} /></div>
      <div className="mt-4 h-1.5 overflow-hidden rounded-full bg-stone-100"><div className="h-full bg-zinc-700" style={{ width: `${Math.round(run.progress * 100)}%` }} /></div>
      <div className="mt-2 flex flex-wrap gap-4 text-xs text-zinc-500"><span>{run.completedEpisodes}/{run.totalEpisodes} episodes</span><span className="text-emerald-700">PASS {run.passCount}</span><span className="text-rose-700">FAIL {run.failCount}</span><span className="text-amber-700">ERROR {run.errorCount}</span><span>{run.totalLatencyMs} ms</span><span>${run.estimatedCost.toFixed(4)}</span></div>
    </Link>)}</div>
  </AdminShell>;
}

function FormInput({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }) { return <label className="text-xs text-zinc-500">{label}<input value={value} onChange={(event) => onChange(event.target.value)} className="mt-1 w-full rounded border border-stone-200 p-2 text-sm" /></label>; }
function FormSelect({ label, value, placeholder, options, disabled = false, onChange }: { label: string; value: string; placeholder: string; options: { value: string; label: string }[]; disabled?: boolean; onChange: (value: string) => void }) { return <label className="text-xs text-zinc-500">{label}<select disabled={disabled} value={value} onChange={(event) => onChange(event.target.value)} className="mt-1 w-full rounded border border-stone-200 bg-white p-2 text-sm disabled:opacity-50"><option value="">{placeholder}</option>{options.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label>; }
function Status({ value }: { value: string }) { return <span className="rounded-full bg-stone-100 px-2 py-1 font-mono text-[10px] text-zinc-600">{value}</span>; }
