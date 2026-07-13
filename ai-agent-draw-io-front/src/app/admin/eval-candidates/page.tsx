'use client';

import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { agentApi, ApiResponseError } from '@/api/agent';
import type { EvalCaseCandidateDTO, SemanticMinerRunDTO } from '@/types/api';
import { buildLoginHref } from '@/utils/login-form';
import { formatTime } from '../admin-shared';
import { AdminPageHeading, AdminShell } from '../admin-shell';
import { acceptCandidateForDraft, hasActiveSemanticDiscovery } from './candidate-review-workflow';

const STATUS_FILTERS = ['', 'DETECTED', 'TRIAGED', 'DRAFT_READY', 'NEEDS_MANUAL_RECONSTRUCTION', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'PUBLISHED'];
const RISK_FILTERS = ['', 'critical', 'high', 'medium', 'low'];

export default function AdminEvalCandidatesPage() {
  const pathname = usePathname();
  const router = useRouter();
  const [candidates, setCandidates] = useState<EvalCaseCandidateDTO[]>([]);
  const [status, setStatus] = useState('');
  const [risk, setRisk] = useState('');
  const [loading, setLoading] = useState(true);
  const [forbidden, setForbidden] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [draftNotes, setDraftNotes] = useState<Record<string, string>>({});
  const [minerRuns, setMinerRuns] = useState<SemanticMinerRunDTO[]>([]);
  const [samplingPolicy, setSamplingPolicy] = useState<'TARGETED' | 'RANDOM' | 'MIXED'>('TARGETED');
  const [sampleLimit, setSampleLimit] = useState(20);
  const [mining, setMining] = useState(false);
  const [busyCandidateId, setBusyCandidateId] = useState<string | null>(null);
  const [caseIdentities, setCaseIdentities] = useState<Record<string, { caseId: string; caseVersion: string }>>({});
  const [visualRunId, setVisualRunId] = useState('');
  const [visualMining, setVisualMining] = useState(false);
  const candidateRequestId = useRef(0);

  const refreshCandidates = useCallback(() => {
    const requestId = ++candidateRequestId.current;
    return agentApi.adminListEvalCandidates({ status: status || undefined, risk: risk || undefined, limit: 100 })
      .then((response) => {
        if (requestId === candidateRequestId.current) setCandidates(response.data || []);
      })
      .catch((reason) => {
        if (requestId !== candidateRequestId.current) return;
        if (reason instanceof ApiResponseError && reason.code === 'AUTH_FORBIDDEN') {
          agentApi.me().then(({ data }) => data.status === 'SUCCESS'
            ? setForbidden(true)
            : router.replace(buildLoginHref(pathname)));
        } else {
          setError(reason instanceof Error ? reason.message : 'Failed to load Eval Candidates');
        }
      })
      .finally(() => {
        if (requestId === candidateRequestId.current) setLoading(false);
      });
  }, [pathname, risk, router, status]);

  useEffect(() => {
    void refreshCandidates();
    return () => { candidateRequestId.current += 1; };
  }, [refreshCandidates]);

  const refreshMinerRuns = useCallback(() => agentApi.adminListSemanticMinerRuns(10)
    .then(({ data }) => setMinerRuns(data || []))
    .catch((failure) => setError(failure instanceof Error ? failure.message : 'Failed to load semantic scans')), []);

  useEffect(() => { void refreshMinerRuns(); }, [refreshMinerRuns]);

  const hasActiveDiscovery = hasActiveSemanticDiscovery(minerRuns);
  useEffect(() => {
    if (!hasActiveDiscovery) return;
    // Poll only while a bounded discovery job is active, then refresh the human review inbox.
    const timer = window.setInterval(() => {
      void refreshMinerRuns().then(() => refreshCandidates());
    }, 2000);
    return () => window.clearInterval(timer);
  }, [hasActiveDiscovery, refreshCandidates, refreshMinerRuns]);

  const startSemanticScan = () => {
    if (mining || hasActiveDiscovery) return;
    if (!window.confirm('Analyze a bounded sample of sanitized, de-identified traces? Model findings only enter human review.')) return;
    setMining(true); setError(null);
    agentApi.adminStartSemanticMinerRun({ samplingPolicy, limit: sampleLimit, purposeConfirmed: true })
      .then(({ data }) => {
        setMinerRuns((current) => [data, ...current.filter((run) => run.id !== data.id)]);
        return refreshCandidates();
      })
      .catch((failure) => setError(failure instanceof Error ? failure.message : 'Semantic discovery failed'))
      .finally(() => setMining(false));
  };

  const analyzeVisualRun = () => {
    if (!visualRunId.trim() || visualMining) return;
    if (!window.confirm('Render this production run for one visual analysis? Pixels are used in memory only and the access is audited.')) return;
    setVisualMining(true); setError(null);
    // Keep purpose confirmation explicit at the API boundary; the server also rejects false.
    agentApi.adminAnalyzeVisualRun(visualRunId.trim(), true)
      .then(({ data }) => { window.alert(data.status === 'CANDIDATE_CREATED' ? `Visual Candidate created: ${data.candidateId}` : `${data.status}${data.reason ? ` · ${data.reason}` : ''}`); window.location.reload(); })
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
      setError(failure instanceof Error ? failure.message : 'Failed to accept Candidate and prepare Draft');
      await refreshCandidates();
    } finally {
      setBusyCandidateId(null);
    }
  };

  const dismissCandidate = async (candidate: EvalCaseCandidateDTO) => {
    if (busyCandidateId || !window.confirm('Dismiss this Candidate from the review inbox?')) return;
    setBusyCandidateId(candidate.id); setError(null);
    try {
      const { data } = await agentApi.adminTransitionEvalCandidate(candidate.id, {
        status: 'REJECTED', reason: 'Dismissed by administrator from Review Inbox',
      });
      setCandidates((current) => current
        .map((item) => item.id === data.id ? data : item)
        .filter((item) => !status || item.status === status));
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Failed to dismiss Candidate');
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
      router.push(`/admin/eval-cases/${encodeURIComponent(data.id)}`);
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
      <AdminPageHeading
        eyebrow="Trace-to-Eval"
        title="Trace Discovery & Review"
        description="Let the model screen sanitized Trace projections, then keep every acceptance, Draft, and Case publication under human control."
      />

      <section className="mb-5 rounded-lg border border-stone-200 bg-white p-4 shadow-sm">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div><div className="text-sm font-semibold text-zinc-800">Autonomous LLM discovery</div><p className="mt-1 text-xs text-zinc-500">Choose a bounded sample. The model screens only sanitized, de-identified projections and sends findings to the Review Inbox; it cannot approve, publish, or block a release.</p></div>
          <div className="flex flex-wrap items-center gap-2">
            <select aria-label="Semantic sampling policy" value={samplingPolicy} onChange={(event) => setSamplingPolicy(event.target.value as typeof samplingPolicy)} className="rounded-md border border-stone-200 bg-white px-2 py-1.5 text-xs text-zinc-700"><option value="TARGETED">Targeted</option><option value="RANDOM">Random</option><option value="MIXED">Mixed</option></select>
            <label className="flex items-center gap-1 text-xs text-zinc-500">Sample<input aria-label="Trace sample limit" type="number" min={1} max={50} value={sampleLimit} onChange={(event) => setSampleLimit(Math.max(1, Math.min(50, Number(event.target.value) || 1)))} className="w-16 rounded-md border border-stone-200 px-2 py-1.5 text-zinc-700" /></label>
            <Action disabled={mining || hasActiveDiscovery} onClick={startSemanticScan}>{mining ? 'Starting…' : hasActiveDiscovery ? 'Discovery running…' : `Start discovery (${sampleLimit})`}</Action>
            <button type="button" onClick={() => { void refreshMinerRuns(); void refreshCandidates(); }} className="text-xs text-zinc-500 hover:underline">Refresh</button>
          </div>
        </div>
        {hasActiveDiscovery && <div className="mt-3 rounded-md bg-blue-50 px-3 py-2 text-xs text-blue-700">Discovery is running. Progress and new Candidates refresh automatically.</div>}
        {minerRuns.length > 0 && <div className="mt-3 grid gap-2 sm:grid-cols-2">{minerRuns.slice(0, 4).map((run) => <div key={run.id} className="rounded-md bg-stone-50 px-3 py-2 text-xs text-zinc-600"><span className="font-mono">{run.id}</span> · <strong>{run.status}</strong> · {run.candidateCount} candidates / {run.analyzedCount} analyzed · {run.errorCount} isolated errors · ${run.estimatedCostUsd.toFixed(4)}{run.availabilityReason && <div className="mt-1 text-amber-700">{run.availabilityReason}</div>}</div>)}</div>}
      </section>

      <section className="mb-5 rounded-lg border border-stone-200 bg-white p-4 shadow-sm">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between"><div><div className="text-sm font-semibold text-zinc-800">Visual anomaly discovery</div><p className="mt-1 text-xs text-zinc-500">Administrator-selected run only. Pixels are inline, short-lived in memory, audited, and never copied into a Dataset.</p></div><div className="flex gap-2"><input aria-label="Source run id for visual analysis" value={visualRunId} onChange={(event) => setVisualRunId(event.target.value)} placeholder="Agent run id" className="rounded-md border border-stone-200 px-2 py-1.5 text-xs"/><Action onClick={analyzeVisualRun}>{visualMining ? 'Analyzing…' : 'Analyze pixels'}</Action></div></div>
      </section>

      <div className="mb-5 flex flex-col gap-3 border-b border-stone-200 pb-4 sm:flex-row sm:items-end sm:justify-between">
        <div><h2 className="text-lg font-semibold text-zinc-900">Review Inbox</h2><p className="mt-1 text-xs text-zinc-500">A Candidate is a model or rule finding, not a confirmed Agent failure. Accepting it creates only a reviewable Draft.</p></div>
        <div className="flex flex-wrap gap-3"><Filter label="Status" value={status} options={STATUS_FILTERS} onChange={(value) => {
            setLoading(true); setError(null); setStatus(value);
          }} formatOption={candidateStatusLabel} />
          <Filter label="Risk" value={risk} options={RISK_FILTERS} onChange={(value) => {
            setLoading(true); setError(null); setRisk(value);
          }} /></div>
      </div>
      {error && <div className="mb-4 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{error}</div>}
      {loading && <div className="py-16 text-center text-sm text-zinc-400">Loading candidates…</div>}
      {!loading && candidates.length === 0 && <div className="py-16 text-center text-sm text-zinc-400">No matching candidates.</div>}

      <div className="space-y-3">
        {candidates.map((candidate) => (
          <article key={candidate.id} className="rounded-lg border border-stone-200 bg-white p-4 shadow-sm">
            <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-mono text-xs text-zinc-400">{candidate.id}</span>
                  <Pill value={candidateStatusLabel(candidate.status)} />
                  <Pill value={candidate.risk} />
                  <Pill value={candidate.failureFamily} />
                  <Pill value={candidate.detectionSource || 'RULE_DETECTED'} />
                </div>
                <div className="mt-3 text-sm font-medium text-zinc-800">{candidate.ruleId}</div>
                <p className="mt-1 text-sm text-zinc-600">{candidate.evidenceSummary}</p>
                {candidate.detectionSource === 'MODEL_DETECTED' && <div className="mt-2 rounded-md bg-violet-50 px-3 py-2 text-xs text-violet-800"><div className="font-medium">Model evidence · confidence {candidate.modelConfidence?.toFixed(2) || 'unknown'}</div><ul className="mt-1 list-disc pl-4">{candidate.modelEvidence?.map((evidence) => <li key={evidence}>{evidence}</li>)}</ul><div className="mt-1 font-mono text-[10px] text-violet-500">{candidate.modelVersion}</div></div>}
                {draftNotes[candidate.id] && <p className="mt-2 rounded-md bg-amber-50 px-3 py-2 text-xs text-amber-800">{draftNotes[candidate.id]}</p>}
                {candidate.status === 'DRAFT_READY' && <DraftCaseIdentity
                  value={caseIdentities[candidate.id] || defaultCaseIdentity(candidate)}
                  busy={busyCandidateId === candidate.id}
                  onChange={(value) => setCaseIdentities((current) => ({ ...current, [candidate.id]: value }))}
                  onCreate={() => void createWorkingCopy(candidate)}
                />}
                <div className="mt-3 flex flex-wrap gap-x-4 gap-y-1 font-mono text-[11px] text-zinc-400">
                  <Link className="text-zinc-600 hover:underline" href={`/admin/runs/${encodeURIComponent(candidate.sourceRunId)}`}>Open source trace</Link>
                  <span>{candidate.sourcePhase || 'unknown phase'} · {candidate.sourceAgentId || 'unknown agent'}</span>
                  <span>{formatTime(candidate.discoveredAt)}</span>
                  <span>{candidate.policyVersion}</span>
                </div>
              </div>
              <div className="flex shrink-0 flex-wrap gap-2">
                {['DETECTED', 'TRIAGED'].includes(candidate.status)
                  && <Action disabled={busyCandidateId !== null} onClick={() => void acceptAndPrepareDraft(candidate)}>{busyCandidateId === candidate.id ? 'Preparing…' : 'Accept & generate draft'}</Action>}
                {candidate.status === 'NEEDS_MANUAL_RECONSTRUCTION'
                  && <Link href="/admin/eval-cases/new" className="rounded-md bg-zinc-800 px-3 py-1.5 text-xs font-medium text-white hover:bg-zinc-700">Create Case manually</Link>}
                {!['APPROVED', 'REJECTED', 'PUBLISHED', 'EXPIRED', 'PURGED'].includes(candidate.status)
                  && <Action danger disabled={busyCandidateId !== null} onClick={() => void dismissCandidate(candidate)}>Dismiss</Action>}
              </div>
            </div>
          </article>
        ))}
      </div>
    </AdminShell>
  );
}

function Filter({ label, value, options, onChange, formatOption = (option) => option }: { label: string; value: string; options: string[]; onChange: (value: string) => void; formatOption?: (option: string) => string }) {
  return <label className="text-xs font-medium text-zinc-500">{label}<select value={value} onChange={(event) => onChange(event.target.value)} className="ml-2 rounded-md border border-stone-200 bg-white px-2 py-1.5 text-sm text-zinc-700"><option value="">All</option>{options.filter(Boolean).map((option) => <option key={option} value={option}>{formatOption(option)}</option>)}</select></label>;
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

function Pill({ value }: { value: string }) {
  return <span className="rounded-full bg-stone-100 px-2 py-0.5 font-mono text-[10px] uppercase text-zinc-600">{value}</span>;
}

function DraftCaseIdentity({ value, busy, onChange, onCreate }: {
  value: { caseId: string; caseVersion: string };
  busy: boolean;
  onChange: (value: { caseId: string; caseVersion: string }) => void;
  onCreate: () => void;
}) {
  return <div className="mt-3 rounded-md border border-emerald-200 bg-emerald-50 p-3">
    <div className="text-xs font-semibold text-emerald-900">Draft ready for human review</div>
    <p className="mt-1 text-xs text-emerald-800">Choose the synthetic Case identity, then inspect the LLM suggestions and translate them into executable expectations in Case Studio. This still does not approve or publish the Case.</p>
    <div className="mt-3 flex flex-wrap items-end gap-2">
      <label className="text-[11px] text-emerald-900">Case ID<input aria-label="Draft Case ID" value={value.caseId} onChange={(event) => onChange({ ...value, caseId: event.target.value })} className="mt-1 block w-64 rounded-md border border-emerald-200 bg-white px-2 py-1.5 text-xs text-zinc-700" /></label>
      <label className="text-[11px] text-emerald-900">Version<input aria-label="Draft Case version" value={value.caseVersion} onChange={(event) => onChange({ ...value, caseVersion: event.target.value })} className="mt-1 block w-24 rounded-md border border-emerald-200 bg-white px-2 py-1.5 text-xs text-zinc-700" /></label>
      <Action disabled={busy} onClick={onCreate}>{busy ? 'Opening…' : 'Open in Case Studio'}</Action>
    </div>
  </div>;
}

function defaultCaseIdentity(candidate: EvalCaseCandidateDTO) {
  const suffix = candidate.id.replace(/^ecc_/, '').replace(/[^a-zA-Z0-9]+/g, '-').slice(-12).toLowerCase();
  const family = candidate.failureFamily.replace(/[^a-zA-Z0-9]+/g, '-').toLowerCase();
  return { caseId: `trace-${family}-${suffix}`, caseVersion: '1' };
}

function Action({ children, onClick, danger, disabled }: { children: ReactNode; onClick: () => void; danger?: boolean; disabled?: boolean }) {
  return <button type="button" disabled={disabled} onClick={onClick} className={`rounded-md px-3 py-1.5 text-xs font-medium disabled:cursor-not-allowed disabled:opacity-50 ${danger ? 'border border-rose-200 text-rose-700 hover:bg-rose-50' : 'bg-zinc-800 text-white hover:bg-zinc-700'}`}>{children}</button>;
}
