'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { agentApi } from '@/api/agent';
import { evalCaseDetailsHref } from '@/utils/app-routes';
import type { EvalCaseWorkingCopyDTO, EvaluationTarget, PublishedEvalCaseDTO } from '@/types/api';
import { AdminShell } from '../admin-shell';
import { EvaluationWorkspace } from '../evaluation-workspace';
import { Btn, EmptyState, ErrorNote, Field, inputCls, MetricTile, StatusBadge, statusLabel } from '../eval-ui';

const CASE_STATUSES = ['DRAFT', 'VALIDATED', 'DRY_RUN_PASSED', 'UNDER_REVIEW', 'APPROVED', 'REJECTED'];
type OriginFilter = '' | 'offline' | 'trace';

export default function AdminEvalCasesPage() {
  const router = useRouter();
  const [cases, setCases] = useState<EvalCaseWorkingCopyDTO[]>([]);
  const [published, setPublished] = useState<PublishedEvalCaseDTO[]>([]);
  const [status, setStatus] = useState('');
  const [origin, setOrigin] = useState<OriginFilter>('');
  const [target, setTarget] = useState<EvaluationTarget | ''>('');
  const [error, setError] = useState<string | null>(null);
  const [cloneSource, setCloneSource] = useState<PublishedEvalCaseDTO | null>(null);
  const [cloneDraft, setCloneDraft] = useState({ caseId: '', caseVersion: '1' });

  useEffect(() => {
    agentApi.adminListEvalCaseWorkingCopies(status || undefined)
      .then(({ data }) => setCases(data || []))
      .catch((reason) => setError(reason instanceof Error ? reason.message : 'Failed to load cases'));
  }, [status]);

  useEffect(() => {
    agentApi.adminListPublishedEvalCases().then(({ data }) => setPublished(data || [])).catch(() => undefined);
  }, []);

  const activePublished = published.filter((item) => !item.retiredAt);
  // Route A produces MANUAL/IMPORTED/clone copies; Route B produces TRACE_DRAFT copies.
  const visibleCases = cases
    .filter((item) => origin === '' || (origin === 'trace') === (item.sourceType === 'TRACE_DRAFT'))
    .filter((item) => !target || item.evaluationTarget === target);
  const visiblePublished = published.filter((item) => !target || item.evaluationTarget === target);

  const openCloneForm = (item: PublishedEvalCaseDTO) => {
    setCloneSource(item);
    setCloneDraft({ caseId: `${item.caseId}-variant`, caseVersion: '1' });
  };

  const submitClone = () => {
    if (!cloneSource || !cloneDraft.caseId.trim() || !cloneDraft.caseVersion.trim()) return;
    agentApi.adminClonePublishedEvalCase(cloneSource.caseId, cloneSource.caseVersion, cloneDraft.caseId.trim(), cloneDraft.caseVersion.trim())
      .then(({ data }) => router.push(evalCaseDetailsHref(data.id)))
      .catch((reason) => setError(reason instanceof Error ? reason.message : 'Clone failed'));
  };

  const retire = (item: PublishedEvalCaseDTO) => window.confirm(`Retire ${item.caseId}@${item.caseVersion}? Datasets can no longer add it.`)
    && agentApi.adminRetirePublishedEvalCase(item.caseId, item.caseVersion)
      .then(({ data }) => setPublished((all) => all.map((entry) => entry.caseId === data.caseId && entry.caseVersion === data.caseVersion ? data : entry)))
      .catch((reason) => setError(reason instanceof Error ? reason.message : 'Retire failed'));

  return (
    <AdminShell active="cases">
      <EvaluationWorkspace active="cases"
        title="Build regression Cases"
        description="Route A — author a synthetic Case offline. It also collects Route B output: every Case, hand-written or trace-born, is qualified and published here."
        action={<Link href="/admin/eval-cases/new" className="inline-flex h-9 items-center rounded-lg bg-zinc-900 px-4 text-sm font-medium text-white transition hover:bg-zinc-700">+ New case</Link>}
      />

      <section className="mb-6 grid gap-3 sm:grid-cols-3" aria-label="Case pipeline summary">
        <MetricTile label="Working copies" value={cases.length} hint="Editable drafts — publish to freeze one" />
        <MetricTile label="Published versions" value={activePublished.length} hint="Immutable — ready to pin in a Dataset" />
        <MetricTile
          label="Suggested next step"
          value={activePublished.length > 0 ? 'Curate a Dataset' : 'Publish a Case'}
          hint={activePublished.length > 0 ? 'Group published Cases into a test suite' : 'Validate, dry-run and publish a working copy'}
        />
      </section>

      <ErrorNote message={error} />

      <section className="mb-9" aria-label="Working copies">
        <div className="mb-3 flex flex-wrap items-end justify-between gap-3">
          <div>
            <h2 className="font-display text-lg font-semibold text-zinc-900">Working copies</h2>
            <p className="mt-0.5 text-xs text-zinc-500">Editable drafts. Open one to validate, dry-run, review and publish it.</p>
          </div>
          <div className="flex flex-wrap gap-3">
            <Field label="Target">
              <select value={target} onChange={(event) => setTarget(event.target.value as EvaluationTarget | '')} className={`${inputCls} w-48`}>
                <option value="">All targets</option>
                {(['FULL_AGENT', 'INTENT_ROUTER', 'DRAWING_QUALITY', 'VISUAL_REVIEW'] as EvaluationTarget[]).map((value) => <option key={value} value={value}>{value.replaceAll('_', ' ')}</option>)}
              </select>
            </Field>
            <Field label="Origin route">
              <select value={origin} onChange={(event) => setOrigin(event.target.value as OriginFilter)} className={`${inputCls} w-48`}>
                <option value="">Both routes</option>
                <option value="offline">Route A · authored offline</option>
                <option value="trace">Route B · from trace drafts</option>
              </select>
            </Field>
            <Field label="Status">
              <select value={status} onChange={(event) => setStatus(event.target.value)} className={`${inputCls} w-44`}>
                <option value="">All statuses</option>
                {CASE_STATUSES.map((value) => <option key={value} value={value}>{statusLabel(value)}</option>)}
              </select>
            </Field>
          </div>
        </div>

        {!error && visibleCases.length === 0 && (
          <EmptyState
            title={status || origin ? 'No working Cases match these filters' : 'No working Cases yet'}
            hint="Start with a small synthetic task you expect the Agent to handle every time, or promote a reviewed Finding from Trace Analysis."
            action={<Link href="/admin/eval-cases/new" className="text-sm font-semibold text-zinc-700 hover:underline">Create a Case →</Link>}
          />
        )}

        <div className="space-y-2">
          {visibleCases.map((item) => (
            <Link
              key={item.id}
              href={evalCaseDetailsHref(item.id)}
              className="flex items-center justify-between gap-4 rounded-xl border border-stone-200 bg-white px-4 py-3.5 shadow-sm transition hover:border-zinc-400"
            >
              <div className="min-w-0">
                <div className="truncate font-medium text-zinc-900">
                  {item.caseId} <span className="font-normal text-zinc-400">v{item.caseVersion}</span>
                </div>
                <div className="mt-1 truncate text-xs text-zinc-500">
                  {item.evaluationTarget?.replaceAll('_', ' ') || 'Target unresolved'} · source {item.sourceType === 'TRACE_DRAFT' ? 'trace draft' : item.sourceType.toLowerCase()} · revision {item.revision} · owner {item.ownerUserId}
                </div>
              </div>
              <div className="flex shrink-0 items-center gap-2">
                {item.sourceType === 'TRACE_DRAFT' && <StatusBadge value="TRACE_DRAFT" />}
                <StatusBadge value={item.status} />
              </div>
            </Link>
          ))}
        </div>
      </section>

      <section aria-label="Published versions">
        <div className="mb-3">
          <h2 className="font-display text-lg font-semibold text-zinc-900">Published versions</h2>
          <p className="mt-0.5 text-xs text-zinc-500">Immutable snapshots that Datasets pin by exact version. Clone one to start a variant.</p>
        </div>
        {visiblePublished.length === 0
          ? <EmptyState title={target ? 'No published Cases match this target' : 'Nothing published yet'} hint="Publishing happens from a working copy after it is approved." />
          : (
            <div className="overflow-hidden rounded-xl border border-stone-200 bg-white shadow-sm">
              {visiblePublished.map((item) => {
                const key = `${item.caseId}:${item.caseVersion}`;
                const cloning = cloneSource && `${cloneSource.caseId}:${cloneSource.caseVersion}` === key;
                return (
                  <div key={key} className="border-b border-stone-100 last:border-b-0">
                    <div className="flex flex-wrap items-center gap-3 px-4 py-3.5">
                      <div className="min-w-0 flex-1">
                        <span className="font-medium text-zinc-900">{item.caseId}<span className="text-zinc-400">@{item.caseVersion}</span></span>
                        <span className="ml-2 text-[10px] font-medium text-zinc-500">{item.evaluationTarget?.replaceAll('_', ' ') || 'Target unresolved'}</span>
                        <div className="mt-0.5 truncate font-mono text-[10px] text-zinc-400" title="Content hash">{item.contentHash}</div>
                      </div>
                      <StatusBadge value={item.retiredAt ? 'RETIRED' : 'PUBLISHED'} />
                      <div className="flex items-center gap-2">
                        <Btn variant="secondary" onClick={() => (cloning ? setCloneSource(null) : openCloneForm(item))}>{cloning ? 'Cancel' : 'Clone'}</Btn>
                        {!item.retiredAt && <Btn variant="danger" onClick={() => retire(item)}>Retire</Btn>}
                      </div>
                    </div>
                    {cloning && (
                      <div className="flex flex-wrap items-end gap-3 border-t border-stone-100 bg-stone-50 px-4 py-3">
                        <Field label="New Case ID" className="w-64">
                          <input value={cloneDraft.caseId} onChange={(event) => setCloneDraft((draft) => ({ ...draft, caseId: event.target.value }))} className={inputCls} />
                        </Field>
                        <Field label="New version" className="w-24">
                          <input value={cloneDraft.caseVersion} onChange={(event) => setCloneDraft((draft) => ({ ...draft, caseVersion: event.target.value }))} className={inputCls} />
                        </Field>
                        <Btn onClick={submitClone} disabled={!cloneDraft.caseId.trim() || !cloneDraft.caseVersion.trim()}>Create working copy</Btn>
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}
      </section>
    </AdminShell>
  );
}
