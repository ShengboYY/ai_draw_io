'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { agentApi } from '@/api/agent';
import type { EvalCanaryAssessmentDTO, EvalCaseHealthDTO, EvalCompositeGateDecisionDTO, EvalLiveRunReportDTO, EvaluationTarget } from '@/types/api';
import { AdminPageHeading, AdminShell } from '../admin-shell';
import { Btn, ErrorNote, inputCls, StatusBadge, statusLabel } from '../eval-ui';

const healthStatuses = ['', 'FLAKY', 'STALE_REVIEW', 'BROKEN_BASELINE', 'ALWAYS_PASS_REVIEW', 'UNSCORABLE', 'HEALTHY'];
const releaseTargets: EvaluationTarget[] = ['INTENT_ROUTER', 'DRAWING_QUALITY', 'FULL_AGENT'];

export default function AdminEvalOperationsPage() {
  const [runId, setRunId] = useState('');
  const [canary, setCanary] = useState<EvalCanaryAssessmentDTO[]>([]);
  const [readiness, setReadiness] = useState<EvalLiveRunReportDTO | null>(null);
  const [gateRuns, setGateRuns] = useState<Record<EvaluationTarget, string>>({ INTENT_ROUTER: '', DRAWING_QUALITY: '', FULL_AGENT: '' });
  const [requiredTargets, setRequiredTargets] = useState<EvaluationTarget[]>(releaseTargets);
  const [compositeGate, setCompositeGate] = useState<EvalCompositeGateDecisionDTO | null>(null);
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
  const loadReadiness = () => {
    if (!runId.trim()) { setError('Enter a Release Eval Run ID.'); return; }
    setError(null); setReadiness(null);
    agentApi.adminGetEvalRunInsights(runId.trim()).then(({ data }) => setReadiness(data)).catch(showError);
  };
  const composeGate = () => {
    const configured: Partial<Record<EvaluationTarget, string>> = {};
    releaseTargets.forEach((target) => { if (gateRuns[target].trim()) configured[target] = gateRuns[target].trim(); });
    setError(null); setCompositeGate(null);
    agentApi.adminComposeEvalReleaseGate(configured, requiredTargets).then(({ data }) => setCompositeGate(data)).catch(showError);
  };
  const toggleRequired = (target: EvaluationTarget) => setRequiredTargets((values) => values.includes(target)
    ? values.filter((value) => value !== target) : [...values, target]);
  const refreshHealth = () => {
    setRefreshing(true); setError(null);
    agentApi.adminRefreshEvalCaseHealth().then(() => loadHealth()).catch(showError).finally(() => setRefreshing(false));
  };

  useEffect(() => {
    // Load only non-sensitive Case Health summaries on entry.
    agentApi.adminListEvalCaseHealth().then(({ data }) => setHealth(data || []))
      .catch((reason) => setError(reason instanceof Error ? reason.message : 'Evaluation operation failed'));
  }, []);

  return (
    <AdminShell active="operations">
      <AdminPageHeading
        eyebrow="Continuous Evaluation"
        title="Evaluation Operations"
        description="Observe Release canaries and maintain regression Cases. Recommendations never deploy or roll back automatically — the deployment platform stays in charge."
      />

      <ErrorNote message={error} />

      <section className="mb-6 rounded-xl border border-amber-200 bg-amber-50 p-4">
        <p className="text-sm font-semibold text-amber-900">Operator decision required</p>
        <p className="mt-1 text-sm text-amber-800">CONTINUE, HALT_RECOMMENDED and NO_DECISION are evidence-backed recommendations. The deployment platform remains the only system allowed to promote, pause or roll back a release.</p>
      </section>

      <section className="mb-6 rounded-xl border border-stone-200 bg-white p-5 shadow-sm" aria-label="Release readiness">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <h2 className="font-display text-xl font-semibold text-zinc-900">Calibration & sequestered readiness</h2>
            <p className="mt-1 text-sm text-zinc-500">Inspect the exact provider, Judge calibration and external sequestered evidence captured by one Release Run.</p>
          </div>
          <div className="flex flex-wrap gap-2">
            <input aria-label="Release readiness Run ID" value={runId} onChange={(event) => setRunId(event.target.value)} placeholder="Release Eval Run ID" className={`${inputCls} w-64 shrink-0`} />
            <Btn size="md" onClick={loadReadiness}>Inspect readiness</Btn>
          </div>
        </div>
        {!readiness && <Empty text="No Release evidence loaded — missing evidence is not displayed as a 0% score." />}
        {readiness && (
          <div className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
            <Readiness label="Provider credential" ready={readiness.readiness.providerCredentialReady} />
            <Readiness label="Text Judge calibration" ready={readiness.readiness.judgeCalibrationApproved} detail={readiness.readiness.calibrationVersion} />
            <Readiness label="Visual Judge calibration" ready={readiness.readiness.visualJudgeCalibrationApproved} detail={readiness.readiness.visualCalibrationVersion} />
            <Readiness label="Sequestered set" ready={readiness.readiness.sequesteredCaseCount >= readiness.readiness.minimumSequesteredCases} detail={`${readiness.readiness.sequesteredCaseCount}/${readiness.readiness.minimumSequesteredCases} cases`} />
            <div className="rounded-xl border border-stone-200 p-3">
              <p className="text-xs text-zinc-400">Release Gate</p>
              <div className="mt-2"><StatusBadge value={readiness.gate?.outcome || 'NO_DECISION'} /></div>
            </div>
          </div>
        )}
      </section>

      <section className="mb-6 rounded-xl border border-stone-200 bg-white p-5 shadow-sm" aria-label="Composite release gate">
        <div>
          <h2 className="font-display text-xl font-semibold text-zinc-900">Compose target Gates</h2>
          <p className="mt-1 text-sm text-zinc-500">Select immutable Release Runs. Required targets can block or produce NO_DECISION; optional failures remain visible warnings unless they contain a deterministic hard failure.</p>
        </div>
        <div className="mt-4 grid gap-3 lg:grid-cols-3">
          {releaseTargets.map((target) => (
            <label key={target} className="rounded-xl border border-stone-200 p-3 text-xs font-medium text-zinc-600">
              {statusLabel(target)} Run ID
              <input value={gateRuns[target]} onChange={(event) => setGateRuns((values) => ({ ...values, [target]: event.target.value }))} placeholder="Release Eval Run ID" className={`${inputCls} mt-2`} />
              <span className="mt-2 flex items-center gap-2 font-normal">
                <input type="checkbox" checked={requiredTargets.includes(target)} onChange={() => toggleRequired(target)} /> Required for release
              </span>
            </label>
          ))}
        </div>
        <div className="mt-4 flex items-center gap-3">
          <Btn size="md" disabled={requiredTargets.length === 0} onClick={composeGate}>Compose Gate</Btn>
          <span className="text-xs text-zinc-400">CI exit codes: PASS 0 · BLOCK 1 · NO_DECISION 2</span>
        </div>
        {compositeGate && (
          <div className="mt-4 rounded-xl bg-stone-50 p-4 text-sm">
            <div className="flex items-center gap-3"><StatusBadge value={compositeGate.outcome} /><span className="font-mono text-xs text-zinc-500">exit {compositeGate.exitCode}</span></div>
            <ul className="mt-3 list-disc space-y-1 pl-5 text-zinc-600">{compositeGate.reasons.map((reason) => <li key={reason}>{reason}</li>)}</ul>
            {compositeGate.warnings.length > 0 && <p className="mt-3 text-amber-700">Warnings: {compositeGate.warnings.join(' · ')}</p>}
          </div>
        )}
      </section>

      <section className="mb-6 rounded-xl border border-stone-200 bg-white p-5 shadow-sm" aria-label="Canary recommendations">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <h2 className="font-display text-xl font-semibold text-zinc-900">Canary recommendations</h2>
            <p className="mt-1 text-sm text-zinc-500">Metrics are posted by the authorized deployment adapter after a Release Gate PASS or approved override.</p>
          </div>
          <div className="flex flex-wrap gap-2">
            <input aria-label="Release Eval Run ID" value={runId} onChange={(event) => setRunId(event.target.value)} placeholder="Release Eval Run ID" className={`${inputCls} w-64 shrink-0`} />
            <Btn size="md" onClick={loadCanary}>Load</Btn>
          </div>
        </div>
        <div className="mt-4 space-y-3">
          {canary.length === 0 && <Empty text="No canary assessment loaded — enter a Release Eval Run ID above." />}
          {canary.map((item) => (
            <article key={item.id} className="rounded-xl border border-stone-200 p-4">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                  <p className="font-medium text-zinc-900">{item.deploymentRef}</p>
                  <p className="mt-1 font-mono text-[11px] text-zinc-400">{item.evalRunId} · {item.policyVersion}</p>
                </div>
                <StatusBadge value={item.outcome} />
              </div>
              <p className="mt-3 text-sm text-zinc-600">{item.reasons.join(' · ')}</p>
              <div className="mt-3 grid gap-2 text-xs text-zinc-500 sm:grid-cols-3 lg:grid-cols-6">
                <Metric label="Requests" value={item.canaryRequests} />
                <Metric label="Failures" value={item.canaryFailures} />
                <Metric label="Critical findings" value={item.criticalFindings} />
                <Metric label="Infra errors" value={item.infrastructureErrors} />
                <Metric label="P95 latency" value={`${item.p95LatencyMs} ms`} />
                <Metric label="Avg cost" value={`$${item.averageCost.toFixed(4)}`} />
              </div>
            </article>
          ))}
        </div>
      </section>

      <section className="mb-6 rounded-xl border border-stone-200 bg-white p-5 shadow-sm" aria-label="Case health">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <h2 className="font-display text-xl font-semibold text-zinc-900">Case Health queue</h2>
            <p className="mt-1 text-sm text-zinc-500">Nightly results contain Case/version health only — never production user, run, trace or payload.</p>
          </div>
          <div className="flex flex-wrap gap-2">
            <select aria-label="Health status filter" value={healthStatus} onChange={(event) => { setHealthStatus(event.target.value); loadHealth(event.target.value); }} className={`${inputCls} w-52 shrink-0`}>
              {healthStatuses.map((status) => <option key={status} value={status}>{status ? statusLabel(status) : 'All statuses'}</option>)}
            </select>
            <Btn size="md" variant="secondary" disabled={refreshing} onClick={refreshHealth}>{refreshing ? 'Refreshing…' : 'Refresh now'}</Btn>
          </div>
        </div>
        <div className="mt-4 overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-stone-200 text-xs uppercase tracking-wide text-zinc-400">
              <tr><th className="px-2 py-3">Case</th><th className="px-2 py-3">Health</th><th className="px-2 py-3">Baseline reproduced</th><th className="px-2 py-3">Evidence summary</th><th className="px-2 py-3">Updated</th></tr>
            </thead>
            <tbody>
              {health.map((item) => (
                <tr key={`${item.caseId}@${item.caseVersion}`} className="border-b border-stone-100">
                  <td className="px-2 py-3 font-mono text-xs">{item.caseId}@{item.caseVersion}</td>
                  <td className="px-2 py-3"><StatusBadge value={item.healthStatus} /></td>
                  <td className="px-2 py-3">{item.baselineReproduced == null ? 'unknown' : item.baselineReproduced ? 'yes' : 'no'}</td>
                  <td className="px-2 py-3 text-zinc-500">{item.summary}</td>
                  <td className="px-2 py-3 text-zinc-500">{new Date(item.updatedAt).toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {health.length === 0 && <Empty text="No Case Health records match this filter." />}
        </div>
      </section>

      <section className="rounded-xl border border-stone-200 bg-stone-50 p-5" aria-label="Feedback loop">
        <h2 className="font-display text-xl font-semibold text-zinc-900">Regression feedback loop</h2>
        <p className="mt-1 text-sm text-zinc-500">Production identity stops at the reviewed Finding boundary. Published Cases retain sanitized provenance, then fixes are verified through immutable Eval Runs.</p>
        <div className="mt-4 grid gap-3 md:grid-cols-3">
          <FlowStep number="1" title="Finding" href="/admin/trace-findings" text="Review deterministic, semantic or visual findings." />
          <FlowStep number="2" title="Case & Dataset" href="/admin/eval-cases" text="Sanitize, dry-run, approve and publish a regression Case." />
          <FlowStep number="3" title="Fix & rerun" href="/admin/eval-runs" text="Compare candidate and baseline, evaluate Gate, then observe Canary." />
        </div>
      </section>
    </AdminShell>
  );
}

function Metric({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="rounded-lg bg-stone-50 px-2 py-2">
      <span className="block text-zinc-400">{label}</span>
      <span className="mt-0.5 block font-medium text-zinc-700">{value}</span>
    </div>
  );
}

function Readiness({ label, ready, detail }: { label: string; ready: boolean; detail?: string }) {
  return (
    <div className="rounded-xl border border-stone-200 p-3">
      <p className="text-xs text-zinc-400">{label}</p>
      <div className="mt-2"><StatusBadge value={ready ? 'PASS' : 'NO_DECISION'} label={ready ? 'Ready' : 'Needs evidence'} /></div>
      {detail && <p className="mt-2 font-mono text-[10px] text-zinc-400">{detail}</p>}
    </div>
  );
}

function Empty({ text }: { text: string }) {
  return <p className="py-6 text-center text-sm text-zinc-400">{text}</p>;
}

function FlowStep({ number, title, href, text }: { number: string; title: string; href: string; text: string }) {
  return (
    <Link href={href} className="rounded-xl border border-stone-200 bg-white p-4 transition hover:border-zinc-400">
      <span className="font-mono text-xs text-zinc-400">STEP {number}</span>
      <p className="mt-2 font-medium text-zinc-900">{title} →</p>
      <p className="mt-1 text-sm text-zinc-500">{text}</p>
    </Link>
  );
}
