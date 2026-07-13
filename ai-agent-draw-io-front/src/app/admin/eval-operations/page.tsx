'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { agentApi } from '@/api/agent';
import type { EvalCanaryAssessmentDTO, EvalCaseHealthDTO } from '@/types/api';
import { AdminPageHeading, AdminShell } from '../admin-shell';

const healthStatuses = ['', 'FLAKY', 'STALE_REVIEW', 'BROKEN_BASELINE', 'ALWAYS_PASS_REVIEW', 'UNSCORABLE', 'HEALTHY'];

export default function AdminEvalOperationsPage() {
  const [runId, setRunId] = useState('');
  const [canary, setCanary] = useState<EvalCanaryAssessmentDTO[]>([]);
  const [health, setHealth] = useState<EvalCaseHealthDTO[]>([]);
  const [healthStatus, setHealthStatus] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  const showError = (reason: unknown) => setError(reason instanceof Error ? reason.message : 'Evaluation operation failed');
  const loadHealth = (status = healthStatus) => agentApi.adminListEvalCaseHealth(status || undefined)
    .then(({ data }) => setHealth(data || [])).catch(showError);
  const loadCanary = () => {
    if (!runId.trim()) { setError('Enter a Release Eval Run ID.'); return; }
    setError(null);
    agentApi.adminListEvalCanaryAssessments(runId.trim()).then(({ data }) => setCanary(data || [])).catch(showError);
  };
  const refreshHealth = () => {
    setRefreshing(true); setError(null);
    agentApi.adminRefreshEvalCaseHealth().then(() => loadHealth()).catch(showError).finally(() => setRefreshing(false));
  };

  useEffect(() => {
    // Load only non-sensitive Case Health summaries on entry.
    agentApi.adminListEvalCaseHealth().then(({ data }) => setHealth(data || []))
      .catch((reason) => setError(reason instanceof Error ? reason.message : 'Evaluation operation failed'));
  }, []);

  return <AdminShell active="operations">
    <AdminPageHeading eyebrow="Continuous Evaluation" title="Evaluation Operations"
      description="Observe Release canaries and maintain regression Cases. Recommendations never deploy or roll back automatically." />

    {error && <p className="mb-4 rounded-lg border border-rose-200 bg-rose-50 p-3 text-sm text-rose-700">{error}</p>}

    <section className="mb-6 rounded-xl border border-amber-200 bg-amber-50 p-4">
      <p className="font-medium text-amber-900">Operator decision required</p>
      <p className="mt-1 text-sm text-amber-800">CONTINUE, HALT_RECOMMENDED and NO_DECISION are evidence-backed recommendations. The deployment platform remains the only system allowed to promote, pause or roll back a release.</p>
    </section>

    <section className="mb-6 rounded-xl border border-stone-200 bg-white p-5 shadow-sm">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div><h2 className="font-display text-xl font-semibold text-zinc-900">Canary recommendations</h2><p className="mt-1 text-sm text-zinc-500">Metrics are posted by the authorized deployment adapter after a Release Gate PASS or approved override.</p></div>
        <div className="flex gap-2"><input aria-label="Release Eval Run ID" value={runId} onChange={(event) => setRunId(event.target.value)} placeholder="Release Eval Run ID" className="w-64 rounded-lg border border-stone-200 px-3 py-2 text-sm" /><button onClick={loadCanary} className="rounded-lg bg-zinc-800 px-4 py-2 text-sm font-medium text-white">Load</button></div>
      </div>
      <div className="mt-4 space-y-3">
        {canary.length === 0 && <Empty text="No canary assessment loaded." />}
        {canary.map((item) => <article key={item.id} className="rounded-lg border border-stone-200 p-4">
          <div className="flex flex-wrap items-start justify-between gap-3"><div><p className="font-medium text-zinc-900">{item.deploymentRef}</p><p className="mt-1 font-mono text-[11px] text-zinc-400">{item.evalRunId} · {item.policyVersion}</p></div><Outcome value={item.outcome} /></div>
          <p className="mt-3 text-sm text-zinc-600">{item.reasons.join(' · ')}</p>
          <div className="mt-3 grid gap-2 text-xs text-zinc-500 sm:grid-cols-3 lg:grid-cols-6"><Metric label="Requests" value={item.canaryRequests} /><Metric label="Failures" value={item.canaryFailures} /><Metric label="Critical" value={item.criticalFindings} /><Metric label="Infra errors" value={item.infrastructureErrors} /><Metric label="P95 ms" value={item.p95LatencyMs} /><Metric label="Avg cost" value={`$${item.averageCost.toFixed(4)}`} /></div>
        </article>)}
      </div>
    </section>

    <section className="mb-6 rounded-xl border border-stone-200 bg-white p-5 shadow-sm">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between"><div><h2 className="font-display text-xl font-semibold text-zinc-900">Case Health queue</h2><p className="mt-1 text-sm text-zinc-500">Nightly results contain Case/version health only—never production user, run, trace or payload.</p></div><div className="flex gap-2"><select value={healthStatus} onChange={(event) => { setHealthStatus(event.target.value); loadHealth(event.target.value); }} className="rounded-lg border border-stone-200 px-3 py-2 text-sm">{healthStatuses.map((status) => <option key={status} value={status}>{status || 'ALL STATUSES'}</option>)}</select><button disabled={refreshing} onClick={refreshHealth} className="rounded-lg border border-stone-300 px-4 py-2 text-sm font-medium text-zinc-700 disabled:opacity-50">{refreshing ? 'Refreshing…' : 'Refresh now'}</button></div></div>
      <div className="mt-4 overflow-x-auto"><table className="w-full text-left text-sm"><thead className="border-b border-stone-200 text-xs uppercase tracking-wide text-zinc-400"><tr><th className="px-2 py-3">Case</th><th className="px-2 py-3">Health</th><th className="px-2 py-3">Baseline reproduced</th><th className="px-2 py-3">Evidence summary</th><th className="px-2 py-3">Updated</th></tr></thead><tbody>{health.map((item) => <tr key={`${item.caseId}@${item.caseVersion}`} className="border-b border-stone-100"><td className="px-2 py-3 font-mono text-xs">{item.caseId}@{item.caseVersion}</td><td className="px-2 py-3"><Health value={item.healthStatus} /></td><td className="px-2 py-3">{item.baselineReproduced == null ? 'unknown' : item.baselineReproduced ? 'yes' : 'no'}</td><td className="px-2 py-3 text-zinc-500">{item.summary}</td><td className="px-2 py-3 text-zinc-500">{new Date(item.updatedAt).toLocaleString()}</td></tr>)}</tbody></table>{health.length === 0 && <Empty text="No Case Health records match this filter." />}</div>
    </section>

    <section className="rounded-xl border border-stone-200 bg-stone-50 p-5">
      <h2 className="font-display text-xl font-semibold text-zinc-900">Regression feedback loop</h2>
      <p className="mt-1 text-sm text-zinc-500">Production identity stops at the reviewed Finding boundary. Published Cases retain sanitized provenance, then fixes are verified through immutable Eval Runs.</p>
      <div className="mt-4 grid gap-3 md:grid-cols-3"><FlowStep number="1" title="Finding" href="/admin/trace-findings" text="Review deterministic, semantic or visual findings." /><FlowStep number="2" title="Case & Dataset" href="/admin/eval-cases" text="Sanitize, dry-run, approve and publish a regression Case." /><FlowStep number="3" title="Fix & rerun" href="/admin/eval-runs" text="Compare candidate and baseline, evaluate Gate, then observe Canary." /></div>
    </section>
  </AdminShell>;
}

function Outcome({ value }: { value: EvalCanaryAssessmentDTO['outcome'] }) { const color = value === 'CONTINUE' ? 'bg-emerald-100 text-emerald-800' : value === 'HALT_RECOMMENDED' ? 'bg-rose-100 text-rose-800' : 'bg-amber-100 text-amber-800'; return <span className={`rounded-full px-3 py-1 font-mono text-[11px] font-semibold ${color}`}>{value}</span>; }
function Health({ value }: { value: EvalCaseHealthDTO['healthStatus'] }) { const color = value === 'HEALTHY' ? 'bg-emerald-100 text-emerald-800' : value === 'FLAKY' || value === 'BROKEN_BASELINE' ? 'bg-rose-100 text-rose-800' : 'bg-amber-100 text-amber-800'; return <span className={`rounded-full px-2 py-1 font-mono text-[10px] ${color}`}>{value}</span>; }
function Metric({ label, value }: { label: string; value: string | number }) { return <div className="rounded bg-stone-50 px-2 py-2"><span className="block text-zinc-400">{label}</span><span className="mt-0.5 block font-medium text-zinc-700">{value}</span></div>; }
function Empty({ text }: { text: string }) { return <p className="py-6 text-center text-sm text-zinc-400">{text}</p>; }
function FlowStep({ number, title, href, text }: { number: string; title: string; href: string; text: string }) { return <Link href={href} className="rounded-lg border border-stone-200 bg-white p-4 transition hover:border-zinc-400"><span className="font-mono text-xs text-zinc-400">STEP {number}</span><p className="mt-2 font-medium text-zinc-900">{title} →</p><p className="mt-1 text-sm text-zinc-500">{text}</p></Link>; }
