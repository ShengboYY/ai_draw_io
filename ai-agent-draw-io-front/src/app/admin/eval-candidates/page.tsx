'use client';

import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { agentApi, ApiResponseError } from '@/api/agent';
import type { EvalCaseCandidateDTO, TraceAnalysisJobDTO, TraceFindingViewDTO } from '@/types/api';
import { evalCaseDetailsHref } from '@/utils/app-routes';
import { buildLoginHref } from '@/utils/login-form';
import { formatTime } from '../admin-shared';
import { AdminShell } from '../admin-shell';
import { TraceAnalysisWorkspace } from '../trace-analysis-workspace';
import { acceptCandidateForDraft } from './candidate-review-workflow';

const STATUS_FILTERS = ['', 'DETECTED', 'TRIAGED', 'DRAFT_READY', 'NEEDS_MANUAL_RECONSTRUCTION', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'PUBLISHED'];
const RISK_FILTERS = ['', 'critical', 'high', 'medium', 'low'];

export default function AdminEvalCandidatesPage() {
  const pathname = usePathname();
  const router = useRouter();
  const [candidates, setCandidates] = useState<EvalCaseCandidateDTO[]>([]);
  const [status, setStatus] = useState('');
  const [risk, setRisk] = useState('');
  const [analyzerFilter, setAnalyzerFilter] = useState('');
  const [routeFilter, setRouteFilter] = useState('');
  const [agentFilter, setAgentFilter] = useState('');
  const [minLatencyMs, setMinLatencyMs] = useState('');
  const [maxLatencyMs, setMaxLatencyMs] = useState('');
  const [discoveredFrom, setDiscoveredFrom] = useState('');
  const [discoveredTo, setDiscoveredTo] = useState('');
  const [loading, setLoading] = useState(true);
  const [forbidden, setForbidden] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [draftNotes, setDraftNotes] = useState<Record<string, string>>({});
  const [analysisJobs, setAnalysisJobs] = useState<TraceAnalysisJobDTO[]>([]);
  const [batchAnalyzer, setBatchAnalyzer] = useState<'DETERMINISTIC' | 'LLM' | 'VLM'>('LLM');
  const [findingViews, setFindingViews] = useState<Record<string, TraceFindingViewDTO>>({});
  const [samplingPolicy, setSamplingPolicy] = useState<'LATEST' | 'TARGETED'>('LATEST');
  const [sampleLimit, setSampleLimit] = useState(20);
  const [scanCompletedFrom, setScanCompletedFrom] = useState('');
  const [scanCompletedTo, setScanCompletedTo] = useState('');
  const [mining, setMining] = useState(false);
  const [busyCandidateId, setBusyCandidateId] = useState<string | null>(null);
  const [caseIdentities, setCaseIdentities] = useState<Record<string, { caseId: string; caseVersion: string }>>({});
  const [visualRunId, setVisualRunId] = useState('');
  const [visualMining, setVisualMining] = useState(false);
  const candidateRequestId = useRef(0);

  const refreshCandidates = useCallback(() => {
    const requestId = ++candidateRequestId.current;
    return agentApi.adminListTraceFindings({
      status: status || undefined, risk: risk || undefined, analyzer: analyzerFilter || undefined,
      routeType: routeFilter || undefined, agentId: agentFilter || undefined,
      minLatencyMs: minLatencyMs ? Number(minLatencyMs) : undefined,
      maxLatencyMs: maxLatencyMs ? Number(maxLatencyMs) : undefined,
      discoveredFrom: toIso(discoveredFrom), discoveredTo: toIso(discoveredTo), limit: 100,
    })
      .then((response) => {
        if (requestId === candidateRequestId.current) {
          const views = response.data || [];
          setCandidates(views.map(findingToCandidate));
          setFindingViews(Object.fromEntries(views.map((finding) => [finding.candidateId, finding])));
        }
      })
      .catch((reason) => {
        if (requestId !== candidateRequestId.current) return;
        if (reason instanceof ApiResponseError && reason.code === 'AUTH_FORBIDDEN') {
          agentApi.me().then(({ data }) => data.status === 'SUCCESS'
            ? setForbidden(true)
            : router.replace(buildLoginHref(pathname)));
        } else {
          setError(reason instanceof Error ? reason.message : 'Failed to load Findings');
        }
      })
      .finally(() => {
        if (requestId === candidateRequestId.current) setLoading(false);
      });
  }, [agentFilter, analyzerFilter, discoveredFrom, discoveredTo, maxLatencyMs, minLatencyMs, pathname, risk, routeFilter, router, status]);

  useEffect(() => {
    void refreshCandidates();
    return () => { candidateRequestId.current += 1; };
  }, [refreshCandidates]);

  const refreshAnalysisJobs = useCallback(() => agentApi.adminListTraceAnalysisJobs(10)
    .then(({ data }) => setAnalysisJobs(data || []))
    .catch((failure) => setError(failure instanceof Error ? failure.message : 'Failed to load analysis jobs')), []);

  useEffect(() => { void refreshAnalysisJobs(); }, [refreshAnalysisJobs]);

  const hasActiveDiscovery = analysisJobs.some((job) => ['QUEUED', 'RUNNING'].includes(job.status));
  useEffect(() => {
    if (!hasActiveDiscovery) return;
    // Poll only while a bounded discovery job is active, then refresh the human review inbox.
    const timer = window.setInterval(() => {
      void refreshAnalysisJobs().then(() => refreshCandidates());
    }, 2000);
    return () => window.clearInterval(timer);
  }, [hasActiveDiscovery, refreshAnalysisJobs, refreshCandidates]);

  const startTraceAnalysisBatch = () => {
    if (mining || hasActiveDiscovery) return;
    if (!window.confirm('Analyze a bounded sample of sanitized, de-identified traces? Model findings only enter human review.')) return;
    setMining(true); setError(null);
    agentApi.adminStartTraceAnalysisBatch({
      analyzerType: batchAnalyzer, samplingPolicy, limit: sampleLimit,
      completedFrom: toIso(scanCompletedFrom), completedTo: toIso(scanCompletedTo),
    })
      .then(({ data }) => {
        setAnalysisJobs((current) => [data.job, ...current.filter((job) => job.id !== data.job.id)]);
        return refreshCandidates();
      })
      .catch((failure) => setError(failure instanceof Error ? failure.message : 'Trace discovery failed'))
      .finally(() => setMining(false));
  };

  const analyzeVisualRun = () => {
    if (!visualRunId.trim() || visualMining) return;
    if (!window.confirm('Render this production run for one visual analysis? Pixels are used in memory only and the access is audited.')) return;
    setVisualMining(true); setError(null);
    // Keep purpose confirmation explicit at the API boundary; the server also rejects false.
    agentApi.adminStartTraceAnalysis(visualRunId.trim(), 'VLM')
      .then(({ data }) => {
        setAnalysisJobs((current) => [data.job, ...current.filter((job) => job.id !== data.job.id)]);
        return refreshCandidates();
      })
      .catch((failure) => setError(failure instanceof Error ? failure.message : 'Visual discovery failed'))
      .finally(() => setVisualMining(false));
  };

  const acceptAndPrepareDraft = async (candidate: EvalCaseCandidateDTO) => {
    if (busyCandidateId) return;
    if (!window.confirm('Accept this finding and send its metadata plus any authorized short-lived Debug Trace through deterministic sanitization to the Draft LLM? This does not approve or publish a Case.')) return;
    setBusyCandidateId(candidate.id); setError(null);
    try {
      // Debug capture access remains explicit even though the UI hides the internal TRIAGED transition.
      const data = await acceptCandidateForDraft(agentApi, candidate);
      setCandidates((current) => current.map((item) => item.id === candidate.id ? { ...item, status: data.status } : item));
      setDraftNotes((current) => ({
        ...current,
        [candidate.id]: data.draft
          ? `${data.draft.failureSummary} · human review required`
          : `Manual reconstruction required · ${data.sanitizerEvidence.join(', ')}`,
      }));
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Failed to accept Finding and prepare Draft');
      await refreshCandidates();
    } finally {
      setBusyCandidateId(null);
    }
  };

  const dismissCandidate = async (candidate: EvalCaseCandidateDTO) => {
    if (busyCandidateId || !window.confirm('Dismiss this Finding from the review inbox?')) return;
    setBusyCandidateId(candidate.id); setError(null);
    try {
      const { data } = await agentApi.adminTransitionEvalCandidate(candidate.id, {
        status: 'REJECTED', reason: 'Dismissed by administrator from Review Inbox',
      });
      setCandidates((current) => current
        .map((item) => item.id === data.id ? data : item)
        .filter((item) => !status || item.status === status));
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Failed to dismiss Finding');
    } finally {
      setBusyCandidateId(null);
    }
  };

  const createWorkingCopy = async (candidate: EvalCaseCandidateDTO) => {
    if (busyCandidateId) return;
    const identity = caseIdentities[candidate.id] || defaultCaseIdentity(candidate);
    if (!identity.caseId.trim() || !identity.caseVersion.trim()) {
      setError('Case ID and version are required'); return;
    }
    setBusyCandidateId(candidate.id); setError(null);
    try {
      const { data } = await agentApi.adminCreateEvalCaseWorkingCopyFromDraft(
        candidate.id, identity.caseId.trim(), identity.caseVersion.trim(),
      );
      router.push(evalCaseDetailsHref(data.workingCopyId));
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Failed to create Case working copy');
      setBusyCandidateId(null);
    }
  };

  if (forbidden) {
    return <AdminShell active="candidates"><div className="py-20 text-center text-sm text-zinc-500">Admin access required.</div></AdminShell>;
  }

  return (
    <AdminShell active="candidates">
      <TraceAnalysisWorkspace active="findings"
        title="Findings"
        description="Screen development Traces with deterministic rules, an LLM or a VLM, then review the evidence before optionally promoting a sanitized regression Draft."
      />

      <section className="mb-6 grid gap-3 sm:grid-cols-2" aria-label="Trace discovery">
        <article className="rounded-xl border border-stone-200 bg-white p-4 shadow-sm">
          <h2 className="text-sm font-semibold text-zinc-900">Recent Trace discovery</h2>
          <p className="mt-1 text-xs leading-5 text-zinc-500">Scan the latest completed Traces by default, or prioritize suspicious ones. Findings remain hypotheses for human review and never alter a release Gate.</p>
          <div className="mt-3 flex flex-wrap items-end gap-2">
            <Field label="Analyzer">
              <select aria-label="Trace analyzer" value={batchAnalyzer} onChange={(event) => setBatchAnalyzer(event.target.value as typeof batchAnalyzer)} className={`${inputCls} w-36`}>
                <option value="DETERMINISTIC">Rules</option><option value="LLM">LLM semantic</option><option value="VLM">VLM visual</option>
              </select>
            </Field>
            <Field label="Order">
              <select aria-label="Semantic sampling policy" value={samplingPolicy} onChange={(event) => setSamplingPolicy(event.target.value as typeof samplingPolicy)} className={`${inputCls} w-32`}>
                <option value="LATEST">Latest</option>
                <option value="TARGETED">Targeted</option>
              </select>
            </Field>
            <Field label="Trace count">
              <input aria-label="Trace sample limit" type="number" min={1} max={50} value={sampleLimit} onChange={(event) => setSampleLimit(Math.max(1, Math.min(50, Number(event.target.value) || 1)))} className={`${inputCls} w-20`} />
            </Field>
            <Btn disabled={mining || hasActiveDiscovery} onClick={startTraceAnalysisBatch}>{mining ? 'Starting…' : hasActiveDiscovery ? 'Discovery running…' : `Scan ${sampleLimit} traces`}</Btn>
            <Btn variant="secondary" onClick={() => { void refreshAnalysisJobs(); void refreshCandidates(); }}>Refresh</Btn>
          </div>
          <details className="mt-3 rounded-lg border border-stone-200 bg-stone-50 px-3 py-2 text-xs text-zinc-600">
            <summary className="cursor-pointer font-medium text-zinc-700">Filter Traces by completion time</summary>
            <div className="mt-3 grid gap-3 sm:grid-cols-2">
              <Field label="Completed from">
                <input type="datetime-local" value={scanCompletedFrom} onChange={(event) => setScanCompletedFrom(event.target.value)} className={inputCls} />
              </Field>
              <Field label="Completed to">
                <input type="datetime-local" value={scanCompletedTo} onChange={(event) => setScanCompletedTo(event.target.value)} className={inputCls} />
              </Field>
            </div>
          </details>
          {hasActiveDiscovery && <div className="mt-3 rounded-lg bg-blue-50 px-3 py-2 text-xs text-blue-700">Discovery is running — progress and new Findings refresh automatically.</div>}
          {analysisJobs.length > 0 && (
            <div className="mt-3 space-y-2">
              {analysisJobs.slice(0, 4).map((job) => (
                <div key={job.id} className="rounded-lg bg-stone-50 px-3 py-2 text-xs text-zinc-600">
                  <span className="font-mono text-[10px] text-zinc-400">{job.id}</span> · {job.analyzerType} · <StatusBadge value={job.status} /> · {job.succeededItems}/{job.totalItems} analyzed · {job.failedItems} isolated errors · ${job.actualCost.toFixed(4)}
                </div>
              ))}
            </div>
          )}
        </article>

        <article className="rounded-xl border border-stone-200 bg-white p-4 shadow-sm">
          <h2 className="text-sm font-semibold text-zinc-900">Visual anomaly discovery</h2>
          <p className="mt-1 text-xs leading-5 text-zinc-500">Analyze one administrator-selected run visually. Pixels are inline, short-lived in memory, audited, and never copied into a Dataset.</p>
          <div className="mt-3 flex flex-wrap items-end gap-2">
            <Field label="Agent run id" className="min-w-48 flex-1">
              <input aria-label="Source run id for visual analysis" value={visualRunId} onChange={(event) => setVisualRunId(event.target.value)} placeholder="run_…" className={inputCls} />
            </Field>
            <Btn disabled={!visualRunId.trim() || visualMining} onClick={analyzeVisualRun}>{visualMining ? 'Analyzing…' : 'Analyze pixels'}</Btn>
          </div>
        </article>
      </section>

      <section aria-label="Review inbox">
        <div className="mb-4 flex flex-col gap-3 border-b border-stone-200 pb-4 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <h2 className="font-display text-lg font-semibold text-zinc-900">Review Inbox</h2>
            <p className="mt-0.5 text-xs text-zinc-500">A Finding is a rule or model signal — not a confirmed Agent failure. Accepting one only creates a reviewable Draft.</p>
          </div>
          <div className="flex flex-wrap gap-3">
            <Field label="Analyzer">
              <select value={analyzerFilter} onChange={(event) => { setLoading(true); setAnalyzerFilter(event.target.value); }} className={`${inputCls} w-36`}>
                <option value="">All analyzers</option><option value="DETERMINISTIC">Rules</option><option value="LLM">LLM</option><option value="VLM">VLM</option>
              </select>
            </Field>
            <Field label="Status">
              <select value={status} onChange={(event) => { setLoading(true); setError(null); setStatus(event.target.value); }} className={`${inputCls} w-44`}>
                <option value="">All statuses</option>
                {STATUS_FILTERS.filter(Boolean).map((option) => <option key={option} value={option}>{statusLabel(candidateStatusLabel(option))}</option>)}
              </select>
            </Field>
            <Field label="Risk">
              <select value={risk} onChange={(event) => { setLoading(true); setError(null); setRisk(event.target.value); }} className={`${inputCls} w-32`}>
                <option value="">All risks</option>
                {RISK_FILTERS.filter(Boolean).map((option) => <option key={option} value={option}>{statusLabel(option)}</option>)}
              </select>
            </Field>
          </div>
        </div>

        <details className="mb-4 rounded-lg border border-stone-200 bg-stone-50 px-3 py-2 text-xs text-zinc-600">
          <summary className="cursor-pointer font-medium text-zinc-700">More filters</summary>
          <div className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <Field label="Route"><input value={routeFilter} onChange={(event) => setRouteFilter(event.target.value)} placeholder="edit_existing" className={inputCls} /></Field>
            <Field label="Agent"><input value={agentFilter} onChange={(event) => setAgentFilter(event.target.value)} placeholder="drawing-agent" className={inputCls} /></Field>
            <Field label="Min latency (ms)"><input type="number" min={0} value={minLatencyMs} onChange={(event) => setMinLatencyMs(event.target.value)} className={inputCls} /></Field>
            <Field label="Max latency (ms)"><input type="number" min={0} value={maxLatencyMs} onChange={(event) => setMaxLatencyMs(event.target.value)} className={inputCls} /></Field>
            <Field label="Discovered after"><input type="datetime-local" value={discoveredFrom} onChange={(event) => setDiscoveredFrom(event.target.value)} className={inputCls} /></Field>
            <Field label="Discovered before"><input type="datetime-local" value={discoveredTo} onChange={(event) => setDiscoveredTo(event.target.value)} className={inputCls} /></Field>
            <div className="flex items-end"><Btn variant="secondary" onClick={() => { setLoading(true); void refreshCandidates(); }}>Apply filters</Btn></div>
          </div>
        </details>

        <ErrorNote message={error} />
        {loading && <div className="py-16 text-center text-sm text-zinc-400">Loading candidates…</div>}
        {!loading && !error && candidates.length === 0 && (
          <EmptyState
            title="No Findings need review"
            hint="Run the Agent during development, then start a bounded discovery scan above. You can also skip Trace discovery and create a synthetic Case manually."
            action={<Link href="/admin/eval-cases/new" className="text-sm font-semibold text-zinc-700 hover:underline">Create a Case instead →</Link>}
          />
        )}

        <div className="space-y-3">
          {candidates.map((candidate) => {
            const finding = findingViews[candidate.id];
            return (
            <article key={candidate.id} className="rounded-xl border border-stone-200 bg-white p-4 shadow-sm">
              <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <StatusBadge value={candidate.status} label={statusLabel(candidateStatusLabel(candidate.status))} />
                    <StatusBadge value={candidate.risk} />
                    <span className="rounded-full bg-stone-100 px-2.5 py-0.5 text-[11px] font-medium text-zinc-600 ring-1 ring-inset ring-stone-200">{candidate.failureFamily}</span>
                    <span className="rounded-full bg-violet-50 px-2.5 py-0.5 text-[11px] font-medium text-violet-700 ring-1 ring-inset ring-violet-200">{analyzerLabel(finding?.analyzerType)}</span>
                  </div>
                  <div className="mt-3 text-sm font-medium text-zinc-900">{candidate.ruleId}</div>
                  <p className="mt-1 text-sm leading-6 text-zinc-600">{candidate.evidenceSummary}</p>
                  {finding?.recommendation && <div className="mt-2 rounded-lg bg-blue-50 px-3 py-2 text-xs text-blue-800"><span className="font-semibold">{finding.recommendation.suspectedLayer} experiment:</span> {finding.recommendation.suggestedExperiment}<div className="mt-1 text-blue-600">Expected impact: {finding.recommendation.expectedImpact}</div></div>}
                  {finding && (
                    <div className="mt-2 rounded-lg bg-violet-50 px-3 py-2 text-xs text-violet-800">
                      <div className="font-medium">{finding.analyzerType === 'DETERMINISTIC' ? 'Rule judgment' : 'Model judgment'} · confidence {finding.confidence?.toFixed(2) || 'not reported'}</div>
                      <ul className="mt-1 list-disc pl-4">{finding.analysisEvidence.map((evidence) => <li key={evidence}>{evidence}</li>)}</ul>
                      <div className="mt-1 font-mono text-[10px] text-violet-500">{finding.analyzerVersion}</div>
                    </div>
                  )}
                  {finding && <div className="mt-2 rounded-lg border border-stone-200 bg-stone-50 px-3 py-2 text-xs text-zinc-700"><div className="font-medium">Original Trace evidence</div><div className="mt-1 flex flex-wrap gap-2">{finding.evidenceRefs.map((ref) => <Link key={ref} href={`/admin/runs?run=${encodeURIComponent(finding.sourceRunId)}`} className="font-mono text-[10px] text-indigo-700 hover:underline">{ref}</Link>)}</div></div>}
                  {draftNotes[candidate.id] && <p className="mt-2 rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-800">{draftNotes[candidate.id]}</p>}
                  {candidate.status === 'DRAFT_READY' && (
                    <DraftCaseIdentity
                      value={caseIdentities[candidate.id] || defaultCaseIdentity(candidate)}
                      busy={busyCandidateId === candidate.id}
                      onChange={(value) => setCaseIdentities((current) => ({ ...current, [candidate.id]: value }))}
                      onCreate={() => void createWorkingCopy(candidate)}
                    />
                  )}
                  <div className="mt-3 flex flex-wrap gap-x-4 gap-y-1 text-[11px] text-zinc-400">
                    <Link className="font-medium text-zinc-600 hover:underline" href={`/admin/runs?run=${encodeURIComponent(candidate.sourceRunId)}`}>Open source trace →</Link>
                    <span>{finding?.routeType || 'unknown route'} · {finding?.sourceAgentId || 'unknown agent'} · {finding?.sourceLatencyMs != null ? `${finding.sourceLatencyMs}ms` : 'latency unavailable'}</span>
                    <span>{formatTime(candidate.discoveredAt)}</span>
                    {finding?.reviewedBy && <span>Reviewed by {finding.reviewedBy} · {formatTime(finding.reviewedAt)}</span>}
                    <span className="font-mono">{candidate.id}</span>
                    <span className="font-mono">{candidate.policyVersion}</span>
                  </div>
                </div>
                <div className="flex shrink-0 flex-wrap gap-2">
                  {['DETECTED', 'TRIAGED'].includes(candidate.status)
                    && <Btn disabled={busyCandidateId !== null} onClick={() => void acceptAndPrepareDraft(candidate)}>{busyCandidateId === candidate.id ? 'Preparing…' : 'Accept & generate draft'}</Btn>}
                  {candidate.status === 'NEEDS_MANUAL_RECONSTRUCTION'
                    && <Link href="/admin/eval-cases/new" className="inline-flex h-8 items-center rounded-lg bg-zinc-900 px-3 text-xs font-medium text-white hover:bg-zinc-700">Create Case manually</Link>}
                  {!['APPROVED', 'REJECTED', 'PUBLISHED', 'EXPIRED', 'PURGED'].includes(candidate.status)
                    && <Btn variant="danger" disabled={busyCandidateId !== null} onClick={() => void dismissCandidate(candidate)}>Dismiss</Btn>}
                </div>
              </div>
            </article>
          ); })}
        </div>
      </section>
    </AdminShell>
  );
}

function candidateStatusLabel(status: string) {
  return ({
    DETECTED: 'AWAITING_REVIEW',
    TRIAGED: 'DRAFT_RETRY_READY',
    DRAFT_READY: 'DRAFT_READY',
    NEEDS_MANUAL_RECONSTRUCTION: 'MANUAL_REVIEW_NEEDED',
    UNDER_REVIEW: 'IN_REVIEW',
  } as Record<string, string>)[status] || status;
}

function DraftCaseIdentity({ value, busy, onChange, onCreate }: {
  value: { caseId: string; caseVersion: string };
  busy: boolean;
  onChange: (value: { caseId: string; caseVersion: string }) => void;
  onCreate: () => void;
}) {
  return (
    <div className="mt-3 rounded-lg border border-emerald-200 bg-emerald-50 p-3">
      <div className="text-xs font-semibold text-emerald-900">Draft ready for human review</div>
      <p className="mt-1 text-xs leading-5 text-emerald-800">Choose the synthetic Case identity, then inspect the LLM suggestions and translate them into executable expectations in Case Studio. This still does not publish the Case.</p>
      <div className="mt-3 flex flex-wrap items-end gap-2">
        <label className="text-[11px] font-medium text-emerald-900">Case ID
          <input aria-label="Draft Case ID" value={value.caseId} onChange={(event) => onChange({ ...value, caseId: event.target.value })} className="mt-1 block w-64 rounded-lg border border-emerald-200 bg-white px-2.5 py-1.5 text-xs text-zinc-700 focus:border-emerald-400 focus:outline-none" />
        </label>
        <label className="text-[11px] font-medium text-emerald-900">Version
          <input aria-label="Draft Case version" value={value.caseVersion} onChange={(event) => onChange({ ...value, caseVersion: event.target.value })} className="mt-1 block w-24 rounded-lg border border-emerald-200 bg-white px-2.5 py-1.5 text-xs text-zinc-700 focus:border-emerald-400 focus:outline-none" />
        </label>
        <Btn disabled={busy} onClick={onCreate}>{busy ? 'Opening…' : 'Open in Case Studio'}</Btn>
      </div>
    </div>
  );
}

function defaultCaseIdentity(candidate: EvalCaseCandidateDTO) {
  const suffix = candidate.id.replace(/^ecc_/, '').replace(/[^a-zA-Z0-9]+/g, '-').slice(-12).toLowerCase();
  const family = candidate.failureFamily.replace(/[^a-zA-Z0-9]+/g, '-').toLowerCase();
  return { caseId: `trace-${family}-${suffix}`, caseVersion: '1' };
}

/** Keep Candidate as the write DTO while the Inbox reads the richer Finding projection. */
function findingToCandidate(finding: TraceFindingViewDTO): EvalCaseCandidateDTO {
  return {
    id: finding.candidateId,
    sourceRunId: finding.sourceRunId,
    failureFamily: finding.failureFamily,
    ruleId: finding.analyzerType,
    evidenceSummary: finding.analysisSummary,
    risk: finding.risk,
    discoveredAt: finding.discoveredAt,
    policyVersion: finding.analyzerVersion || 'unknown',
    status: finding.candidateStatus,
    createdBy: 'trace-analysis',
    detectionSource: finding.analyzerType === 'DETERMINISTIC' ? 'RULE_DETECTED' : 'MODEL_DETECTED',
    modelVersion: finding.analyzerVersion,
    modelConfidence: finding.confidence,
    modelEvidence: finding.analysisEvidence,
  };
}

function analyzerLabel(analyzer?: TraceFindingViewDTO['analyzerType']) {
  if (analyzer === 'VLM') return 'Found by VLM';
  if (analyzer === 'LLM') return 'Found by LLM';
  return 'Found by rule';
}

function toIso(value: string) {
  if (!value) return undefined;
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? undefined : parsed.toISOString();
}

// R6 keeps these controls local so the Findings workspace has no dependency on the broader UI redesign.
const inputCls = 'w-full rounded-lg border border-stone-200 bg-white px-2.5 py-2 text-sm text-zinc-800 placeholder:text-zinc-400 focus:border-zinc-400 focus:outline-none disabled:opacity-50';

function Field({ label, children, className = '' }: { label: string; children: ReactNode; className?: string }) {
  return <label className={`block text-xs font-medium text-zinc-500 ${className}`}>{label}<span className="mt-1 block font-normal">{children}</span></label>;
}

function Btn({ children, onClick, variant = 'primary', disabled = false }: {
  children: ReactNode;
  onClick?: () => void;
  variant?: 'primary' | 'secondary' | 'danger';
  disabled?: boolean;
}) {
  const tones = {
    primary: 'bg-zinc-900 text-white hover:bg-zinc-700',
    secondary: 'border border-stone-300 bg-white text-zinc-700 hover:bg-stone-50',
    danger: 'border border-rose-200 bg-white text-rose-700 hover:bg-rose-50',
  };
  return <button type="button" disabled={disabled} onClick={onClick} className={`inline-flex h-8 shrink-0 items-center justify-center rounded-lg px-3 text-xs font-medium transition disabled:cursor-not-allowed disabled:opacity-45 ${tones[variant]}`}>{children}</button>;
}

function ErrorNote({ message }: { message: string | null }) {
  return message ? <p className="mb-4 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{message}</p> : null;
}

function EmptyState({ title, hint, action }: { title: string; hint: string; action?: ReactNode }) {
  return <div className="rounded-xl border border-dashed border-stone-300 bg-stone-50 px-5 py-12 text-center"><p className="text-sm font-medium text-zinc-700">{title}</p><p className="mx-auto mt-1 max-w-lg text-xs leading-5 text-zinc-500">{hint}</p>{action && <div className="mt-4 flex justify-center">{action}</div>}</div>;
}

function StatusBadge({ value, label }: { value: string; label?: string }) {
  const tone = ['FAILED', 'REJECTED', 'critical'].includes(value)
    ? 'bg-rose-50 text-rose-700 ring-rose-200'
    : ['APPROVED', 'PUBLISHED', 'DRAFT_READY', 'SUCCEEDED'].includes(value)
      ? 'bg-emerald-50 text-emerald-700 ring-emerald-200'
      : ['RUNNING', 'DETECTED'].includes(value)
        ? 'bg-blue-50 text-blue-700 ring-blue-200'
        : 'bg-stone-100 text-zinc-600 ring-stone-200';
  return <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-[11px] font-medium ring-1 ring-inset ${tone}`}>{label || statusLabel(value)}</span>;
}

function statusLabel(value: string) {
  return value.toLowerCase().replaceAll('_', ' ').replace(/^./, (character) => character.toUpperCase());
}
