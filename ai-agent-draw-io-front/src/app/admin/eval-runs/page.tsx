'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { agentApi } from '@/api/agent';
import type { EvalRunSummaryDTO } from '@/types/api';
import { AdminPageHeading, AdminShell } from '../admin-shell';

export default function AdminEvalRunsPage() {
  const router = useRouter(); const [runs, setRuns] = useState<EvalRunSummaryDTO[]>([]);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { let active = true; const load = () => agentApi.adminListEvalRuns().then(({ data }) => active && setRuns(data || [])).catch((reason) => active && setError(reason instanceof Error ? reason.message : 'Failed to load runs')); load(); const timer = window.setInterval(load, 3000); return () => { active = false; window.clearInterval(timer); }; }, []);
  const start = () => { const datasetId = window.prompt('Published Dataset ID:'); const datasetVersion = window.prompt('Published Dataset version:'); const gitSha = window.prompt('Candidate Git SHA:'); if (!datasetId || !datasetVersion || !gitSha) return; const repetitions = Number(window.prompt('Repetitions per Case:', '1')); const idempotencyKey = `${datasetId}:${datasetVersion}:${gitSha}:${repetitions}`; agentApi.adminStartEvalRun({ datasetId, datasetVersion, gitSha, repetitions, idempotencyKey }).then(({ data }) => router.push(`/admin/eval-runs/${encodeURIComponent(data.id)}`)).catch((reason) => setError(reason instanceof Error ? reason.message : 'Start failed')); };
  return <AdminShell active="evalRuns"><AdminPageHeading eyebrow="Evaluation Control Plane" title="Eval Runs"
    description="Asynchronous Dataset evaluations. COMPLETED describes execution state; PASS/FAIL remain Episode outcomes."
    action={<button onClick={start} className="rounded-lg bg-zinc-800 px-4 py-2 text-sm font-medium text-white">New Mode B run</button>} />
    {error && <p className="mb-4 rounded-lg bg-rose-50 p-3 text-sm text-rose-700">{error}</p>}
    <div className="space-y-3">{runs.map((run) => <Link key={run.id} href={`/admin/eval-runs/${encodeURIComponent(run.id)}`} className="block rounded-lg border border-stone-200 bg-white p-4 shadow-sm">
      <div className="flex items-start justify-between gap-3"><div><div className="font-medium">{run.datasetId}@{run.datasetVersion}</div><div className="mt-1 font-mono text-[11px] text-zinc-400">{run.mode} · {run.gitSha} · {run.id}</div></div><Status value={run.status} /></div>
      <div className="mt-4 h-1.5 overflow-hidden rounded-full bg-stone-100"><div className="h-full bg-zinc-700" style={{ width: `${Math.round(run.progress * 100)}%` }} /></div>
      <div className="mt-2 flex flex-wrap gap-4 text-xs text-zinc-500"><span>{run.completedEpisodes}/{run.totalEpisodes} episodes</span><span className="text-emerald-700">PASS {run.passCount}</span><span className="text-rose-700">FAIL {run.failCount}</span><span className="text-amber-700">ERROR {run.errorCount}</span><span>{run.totalLatencyMs} ms</span><span>${run.estimatedCost.toFixed(4)}</span></div>
    </Link>)}</div>
  </AdminShell>;
}

function Status({ value }: { value: string }) { return <span className="rounded-full bg-stone-100 px-2 py-1 font-mono text-[10px] text-zinc-600">{value}</span>; }
