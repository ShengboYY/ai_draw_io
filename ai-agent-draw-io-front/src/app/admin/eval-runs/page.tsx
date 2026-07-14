'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { agentApi } from '@/api/agent';
import type { EvalDatasetDTO, EvalDatasetVersionDTO, EvaluationProfileDTO, EvaluationTarget, EvalRunSummaryDTO } from '@/types/api';
import { AdminShell } from '../admin-shell';
import { EvaluationWorkspace } from '../evaluation-workspace';
import { Btn, EmptyState, ErrorNote, Field, inputCls, modeLabel, StatusBadge } from '../eval-ui';

type RunMode = 'MODE_B' | 'MODE_C' | 'RELEASE';
type RunDraft = { mode: RunMode; datasetId: string; datasetVersion: string; gitSha: string; baselineRef: string;
  profileKey: string };
const emptyDraft: RunDraft = { mode: 'MODE_B', datasetId: '', datasetVersion: '', gitSha: '', baselineRef: '', profileKey: '' };

const MODE_OPTIONS: { value: RunMode; title: string; recommended?: boolean; description: string }[] = [
  { value: 'MODE_B', title: 'Recorded replay', recommended: true, description: 'Recorded-model replay: deterministic, no live credentials or production traffic. Runs the real router, tools, XML mutation and graders.' },
  { value: 'MODE_C', title: 'Live model', description: 'Calls the live model to measure real behavior. Requires provider credentials and a live execution profile.' },
  { value: 'RELEASE', title: 'Release comparison', description: 'Compares a candidate against a baseline run for a release decision. Requires Release Owner access and calibrated Judges.' },
];

export default function AdminEvalRunsPage() {
  const router = useRouter();
  const [runs, setRuns] = useState<EvalRunSummaryDTO[]>([]);
  const [datasets, setDatasets] = useState<EvalDatasetDTO[]>([]);
  const [datasetVersions, setDatasetVersions] = useState<EvalDatasetVersionDTO[]>([]);
  const [profiles, setProfiles] = useState<EvaluationProfileDTO[]>([]);
  const [draft, setDraft] = useState<RunDraft>(emptyDraft);
  const [showForm, setShowForm] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [targetFilter, setTargetFilter] = useState<EvaluationTarget | ''>('');

  useEffect(() => {
    let active = true;
    const load = () => agentApi.adminListEvalRuns()
      .then(({ data }) => active && setRuns(data || []))
      .catch((reason) => active && setError(reason instanceof Error ? reason.message : 'Failed to load runs'));
    load();
    const timer = window.setInterval(load, 3000);
    return () => { active = false; window.clearInterval(timer); };
  }, []);

  useEffect(() => {
    agentApi.adminListEvalDatasets().then(({ data }) => setDatasets(data || []))
      .catch((reason) => setError(reason instanceof Error ? reason.message : 'Failed to load Datasets'));
    agentApi.adminListEvaluationProfiles().then(({ data }) => setProfiles(data || []))
      .catch((reason) => setError(reason instanceof Error ? reason.message : 'Failed to load Evaluation Profiles'));
  }, []);

  const update = (field: keyof RunDraft, value: string) => setDraft((currentDraft) => ({ ...currentDraft, [field]: value }));

  const selectDataset = (datasetId: string) => {
    setDraft((currentDraft) => ({ ...currentDraft, datasetId, datasetVersion: '', profileKey: '' }));
    setDatasetVersions([]);
    if (datasetId) agentApi.adminListEvalDatasetVersions(datasetId)
      .then(({ data }) => setDatasetVersions((data || []).filter((version) => version.status === 'PUBLISHED')))
      .catch((reason) => setError(reason instanceof Error ? reason.message : 'Failed to load Dataset versions'));
  };

  const start = () => {
    const profile = profiles.find((item) => `${item.profileId}@${item.version}` === draft.profileKey);
    if (!draft.datasetId || !draft.datasetVersion || !draft.gitSha || !profile) {
      setError('Dataset, published version, Evaluation Profile and candidate Git SHA are required.');
      return;
    }
    // The same immutable manifest intentionally resolves to the same idempotency key.
    const idempotencyKey = [draft.mode, draft.datasetId, draft.datasetVersion, profile.profileId, profile.version, draft.gitSha, draft.baselineRef].join(':');
    agentApi.adminStartEvalRun({ mode: draft.mode, datasetId: draft.datasetId, datasetVersion: draft.datasetVersion,
      gitSha: draft.gitSha, candidateRef: draft.gitSha, baselineRef: draft.baselineRef || undefined,
      profileId: profile.profileId, profileVersion: profile.version, repetitions: profile.repetitions, idempotencyKey })
      .then(({ data }) => router.push(`/admin/eval-runs/${encodeURIComponent(data.id)}`))
      .catch((reason) => setError(reason instanceof Error ? reason.message : 'Start failed'));
  };
  const datasetTarget = datasets.find((item) => item.id === draft.datasetId)?.evaluationTarget;
  const compatibleProfiles = profiles.filter((profile) => profile.target === datasetTarget
    && (profile.mode === draft.mode || draft.mode === 'RELEASE' && profile.mode === 'MODE_C' && profile.gateEligible));
  const visibleRuns = runs.filter((run) => !targetFilter || run.evaluationTarget === targetFilter);

  return (
    <AdminShell active="evalRuns">
      <EvaluationWorkspace active="runs"
        title="Run and inspect the Agent"
        description="Replay a frozen Dataset against a candidate build, inspect each failed Episode, then turn what you learned into the next Case or fix."
        action={<Btn size="md" onClick={() => setShowForm((value) => !value)}>{showForm ? 'Close form' : '+ New Eval Run'}</Btn>}
      />

      <ErrorNote message={error} />

      <div className="mb-4 max-w-xs">
        <Field label="Filter by evaluation target">
          <select value={targetFilter} onChange={(event) => setTargetFilter(event.target.value as EvaluationTarget | '')} className={inputCls}>
            <option value="">All targets</option>
            {(['FULL_AGENT', 'INTENT_ROUTER', 'DRAWING_QUALITY', 'VISUAL_REVIEW'] as EvaluationTarget[]).map((value) => <option key={value} value={value}>{value.replaceAll('_', ' ')}</option>)}
          </select>
        </Field>
      </div>

      {showForm && (
        <section className="mb-6 rounded-xl border border-stone-200 bg-white p-5 shadow-sm" aria-label="Start an Eval Run">
          <h2 className="text-sm font-semibold text-zinc-900">1 · Choose a run mode</h2>
          <div className="mt-3 grid gap-3 md:grid-cols-3" role="radiogroup" aria-label="Run mode">
            {MODE_OPTIONS.map((option) => {
              const selected = draft.mode === option.value;
              return (
                <button
                  key={option.value}
                  type="button"
                  role="radio"
                  aria-checked={selected}
                  onClick={() => setDraft((current) => ({ ...current, mode: option.value, profileKey: '' }))}
                  className={`rounded-xl border p-4 text-left transition ${selected ? 'border-zinc-900 bg-zinc-900 text-white shadow-sm' : 'border-stone-200 bg-white hover:border-zinc-400'}`}
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-sm font-semibold">{option.title}</span>
                    {option.recommended && <span className={`rounded-full px-2 py-0.5 text-[10px] font-semibold ${selected ? 'bg-emerald-400/20 text-emerald-300' : 'bg-emerald-50 text-emerald-700'}`}>Recommended</span>}
                  </div>
                  <p className={`mt-1.5 text-xs leading-5 ${selected ? 'text-zinc-300' : 'text-zinc-500'}`}>{option.description}</p>
                </button>
              );
            })}
          </div>

          <h2 className="mt-6 text-sm font-semibold text-zinc-900">2 · Pick the evidence and candidate</h2>
          <div className="mt-3 grid gap-4 md:grid-cols-3">
            <Field label="Dataset">
              <select value={draft.datasetId} onChange={(event) => selectDataset(event.target.value)} className={inputCls}>
                <option value="">Select Dataset…</option>
                {datasets.map((dataset) => <option key={dataset.id} value={dataset.id}>{dataset.name} · {dataset.datasetClass}</option>)}
              </select>
            </Field>
            <Field label="Published version">
              <select disabled={!draft.datasetId} value={draft.datasetVersion} onChange={(event) => update('datasetVersion', event.target.value)} className={inputCls}>
                <option value="">{draft.datasetId ? 'Select version…' : 'Choose Dataset first'}</option>
                {datasetVersions.map((version) => <option key={version.version} value={version.version}>{version.version}</option>)}
              </select>
            </Field>
            <Field label="Candidate Git SHA — the build under test">
              <input value={draft.gitSha} onChange={(event) => update('gitSha', event.target.value)} placeholder="e.g. 133f724e" className={inputCls} />
            </Field>
            <Field label="Evaluation Profile — versioned execution settings">
              <select disabled={!datasetTarget} value={draft.profileKey} onChange={(event) => update('profileKey', event.target.value)} className={inputCls}>
                <option value="">{datasetTarget ? 'Select compatible Profile…' : 'Choose Dataset first'}</option>
                {compatibleProfiles.map((profile) => <option key={`${profile.profileId}@${profile.version}`} value={`${profile.profileId}@${profile.version}`}>{profile.profileId}@{profile.version} · {profile.repetitions}×</option>)}
              </select>
            </Field>
            {draft.mode === 'RELEASE' && (
              <Field label="Baseline Run ID — the run to compare against">
                <input value={draft.baselineRef} onChange={(event) => update('baselineRef', event.target.value)} className={inputCls} />
              </Field>
            )}
          </div>

          <div className="mt-5 flex items-center gap-3">
            <Btn size="md" onClick={start}>Start {modeLabel(draft.mode)}</Btn>
            {draft.mode !== 'MODE_B' && <p className="text-xs text-zinc-500">Live runs require configured provider credentials. Release additionally requires Release Owner access, calibrated Judge evidence and sequestered readiness.</p>}
          </div>
        </section>
      )}

      {!error && runs.length === 0 && (
        <EmptyState
          title="No evaluation history yet"
          hint="Publish a Dataset, then start a Recorded-model replay (Mode B) run to get your first repeatable baseline."
          action={<Btn onClick={() => { setDraft((currentDraft) => ({ ...currentDraft, mode: 'MODE_B' })); setShowForm(true); }}>Start a recorded replay</Btn>}
        />
      )}

      <div className="space-y-3">
        {visibleRuns.map((run) => (
          <Link key={run.id} href={`/admin/eval-runs/${encodeURIComponent(run.id)}`} className="block rounded-xl border border-stone-200 bg-white p-4 shadow-sm transition hover:border-zinc-400">
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <div className="truncate font-medium text-zinc-900">{run.datasetId}<span className="text-zinc-400">@{run.datasetVersion}</span></div>
                <div className="mt-1 truncate text-xs text-zinc-500">{run.evaluationTarget?.replaceAll('_', ' ') || 'Target unresolved'} · {run.profileId ? `${run.profileId}@${run.profileVersion}` : 'Legacy Profile'} · {modeLabel(run.mode)} · candidate <span className="font-mono">{run.gitSha}</span> · <span className="font-mono">{run.id}</span></div>
              </div>
              <StatusBadge value={run.status} />
            </div>
            <div className="mt-4 h-1.5 overflow-hidden rounded-full bg-stone-100" role="progressbar" aria-valuenow={Math.round(run.progress * 100)} aria-valuemin={0} aria-valuemax={100}>
              <div className="h-full rounded-full bg-zinc-700 transition-[width]" style={{ width: `${Math.round(run.progress * 100)}%` }} />
            </div>
            <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-zinc-500">
              <span>{run.completedEpisodes}/{run.totalEpisodes} episodes</span>
              <span className="font-medium text-emerald-700">{run.passCount} PASS</span>
              <span className="font-medium text-rose-700">{run.failCount} FAIL</span>
              <span className="font-medium text-amber-700">{run.errorCount} ERROR</span>
              <span>{run.totalLatencyMs} ms total</span>
              <span>${run.estimatedCost.toFixed(4)} est. cost</span>
            </div>
          </Link>
        ))}
        {runs.length > 0 && visibleRuns.length === 0 && (
          <EmptyState title="No Eval Runs match this target" hint="Choose another target or start a compatible Eval Run." />
        )}
      </div>
    </AdminShell>
  );
}
