'use client';

import { useEffect, useState, type ReactNode } from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { agentApi, ApiResponseError } from '@/api/agent';
import type { EvalCaseCandidateDTO } from '@/types/api';
import { buildLoginHref } from '@/utils/login-form';
import { formatTime } from '../admin-shared';
import { AdminPageHeading, AdminShell } from '../admin-shell';

const STATUS_FILTERS = ['', 'DETECTED', 'TRIAGED', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'PUBLISHED'];
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

  useEffect(() => {
    let alive = true;
    agentApi.adminListEvalCandidates({ status: status || undefined, risk: risk || undefined, limit: 100 })
      .then((response) => {
        if (alive) setCandidates(response.data || []);
      })
      .catch((reason) => {
        if (!alive) return;
        if (reason instanceof ApiResponseError && reason.code === 'AUTH_FORBIDDEN') {
          agentApi.me().then(({ data }) => data.status === 'SUCCESS'
            ? setForbidden(true)
            : router.replace(buildLoginHref(pathname)));
        } else {
          setError(reason instanceof Error ? reason.message : 'Failed to load Eval Candidates');
        }
      })
      .finally(() => alive && setLoading(false));
    return () => { alive = false; };
  }, [pathname, risk, router, status]);

  const transition = (candidate: EvalCaseCandidateDTO, target: string) => {
    const reason = window.prompt(`Reason for ${target.toLowerCase().replaceAll('_', ' ')}:`);
    if (reason == null) return;
    agentApi.adminTransitionEvalCandidate(candidate.id, { status: target, reason })
      .then(({ data }) => setCandidates((current) => current
        .map((item) => item.id === data.id ? data : item)
        .filter((item) => !status || item.status === status)))
      .catch((failure) => setError(failure instanceof Error ? failure.message : 'Transition failed'));
  };

  const prepareDraft = (candidate: EvalCaseCandidateDTO) => {
    if (!window.confirm('Use this run\'s short-lived debug capture to create a sanitized synthetic Eval Draft?')) return;
    setError(null);
    agentApi.adminPrepareEvalDraft(candidate.id)
      .then(({ data }) => {
        setCandidates((current) => current.map((item) => item.id === candidate.id ? { ...item, status: data.status } : item));
        setDraftNotes((current) => ({
          ...current,
          [candidate.id]: data.draft
            ? `${data.draft.failureSummary} · human review required`
            : `Manual reconstruction required · ${data.sanitizerEvidence.join(', ')}`,
        }));
      })
      .catch((failure) => setError(failure instanceof Error ? failure.message : 'Draft preparation failed'));
  };

  if (forbidden) {
    return <AdminShell active="candidates"><div className="py-20 text-center text-sm text-zinc-500">Admin access required.</div></AdminShell>;
  }

  return (
    <AdminShell active="candidates">
      <AdminPageHeading
        eyebrow="Trace-to-Eval"
        title="Eval Candidates"
        description="Metadata-only signals awaiting human triage. A candidate is evidence to review, not a confirmed Agent failure."
      />

      <div className="mb-5 flex flex-wrap gap-3 border-b border-stone-200 pb-4">
        <Filter label="Status" value={status} options={STATUS_FILTERS} onChange={(value) => {
          setLoading(true); setError(null); setStatus(value);
        }} />
        <Filter label="Risk" value={risk} options={RISK_FILTERS} onChange={(value) => {
          setLoading(true); setError(null); setRisk(value);
        }} />
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
                  <Pill value={candidate.status} />
                  <Pill value={candidate.risk} />
                  <Pill value={candidate.failureFamily} />
                </div>
                <div className="mt-3 text-sm font-medium text-zinc-800">{candidate.ruleId}</div>
                <p className="mt-1 text-sm text-zinc-600">{candidate.evidenceSummary}</p>
                {draftNotes[candidate.id] && <p className="mt-2 rounded-md bg-amber-50 px-3 py-2 text-xs text-amber-800">{draftNotes[candidate.id]}</p>}
                <div className="mt-3 flex flex-wrap gap-x-4 gap-y-1 font-mono text-[11px] text-zinc-400">
                  <Link className="text-zinc-600 hover:underline" href={`/admin/runs/${encodeURIComponent(candidate.sourceRunId)}`}>Open source trace</Link>
                  <span>{candidate.sourcePhase || 'unknown phase'} · {candidate.sourceAgentId || 'unknown agent'}</span>
                  <span>{formatTime(candidate.discoveredAt)}</span>
                  <span>{candidate.policyVersion}</span>
                </div>
              </div>
              <div className="flex shrink-0 flex-wrap gap-2">
                {candidate.status === 'DETECTED' && <Action onClick={() => transition(candidate, 'TRIAGED')}>Triage</Action>}
                {candidate.status === 'TRIAGED' && <Action onClick={() => prepareDraft(candidate)}>Prepare draft</Action>}
                {['TRIAGED', 'NEEDS_MANUAL_RECONSTRUCTION', 'DRAFT_READY'].includes(candidate.status)
                  && <Action onClick={() => transition(candidate, 'UNDER_REVIEW')}>Start review</Action>}
                {!['APPROVED', 'REJECTED', 'PUBLISHED', 'EXPIRED', 'PURGED'].includes(candidate.status)
                  && <Action danger onClick={() => transition(candidate, 'REJECTED')}>Reject</Action>}
              </div>
            </div>
          </article>
        ))}
      </div>
    </AdminShell>
  );
}

function Filter({ label, value, options, onChange }: { label: string; value: string; options: string[]; onChange: (value: string) => void }) {
  return <label className="text-xs font-medium text-zinc-500">{label}<select value={value} onChange={(event) => onChange(event.target.value)} className="ml-2 rounded-md border border-stone-200 bg-white px-2 py-1.5 text-sm text-zinc-700"><option value="">All</option>{options.filter(Boolean).map((option) => <option key={option} value={option}>{option}</option>)}</select></label>;
}

function Pill({ value }: { value: string }) {
  return <span className="rounded-full bg-stone-100 px-2 py-0.5 font-mono text-[10px] uppercase text-zinc-600">{value}</span>;
}

function Action({ children, onClick, danger }: { children: ReactNode; onClick: () => void; danger?: boolean }) {
  return <button type="button" onClick={onClick} className={`rounded-md px-3 py-1.5 text-xs font-medium ${danger ? 'border border-rose-200 text-rose-700 hover:bg-rose-50' : 'bg-zinc-800 text-white hover:bg-zinc-700'}`}>{children}</button>;
}
