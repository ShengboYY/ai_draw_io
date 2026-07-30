'use client';

import { Suspense, useEffect, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { agentApi } from '@/api/agent';
import type { EvalCaseWorkingCopyDTO, EvaluationTarget, TraceFindingViewDTO } from '@/types/api';
import { AdminShell } from '../../admin-shell';
import { EvaluationWorkspace } from '../../evaluation-workspace';
import { Btn, ErrorNote, inputCls, StatusBadge } from '../../eval-ui';

function EvalCaseStudioContent() {
  const workingCopyId = useSearchParams().get('workingCopyId') || '';
  const [item, setItem] = useState<EvalCaseWorkingCopyDTO | null>(null);
  const [definition, setDefinition] = useState('');
  const [selectedTarget, setSelectedTarget] = useState<EvaluationTarget | ''>('');
  const [evidence, setEvidence] = useState<string[]>([]);
  const [artifacts, setArtifacts] = useState<{ before?: string; after?: string; trace?: string }>({});
  const [reviewReason, setReviewReason] = useState('');
  const [sourceFinding, setSourceFinding] = useState<TraceFindingViewDTO | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    agentApi.adminGetEvalCaseWorkingCopy(workingCopyId).then(({ data }) => {
      setItem(data); setDefinition(JSON.stringify(data.definition, null, 2));
      setSelectedTarget(data.evaluationTarget || '');
    }).catch((reason) => setError(reason instanceof Error ? reason.message : 'Load failed'));
  }, [workingCopyId]);

  useEffect(() => {
    agentApi.adminGetEvalCaseSourceFinding(workingCopyId)
      .then(({ data }) => setSourceFinding(data))
      // Manual/imported Cases intentionally have no restricted Trace backlink.
      .catch(() => setSourceFinding(null));
  }, [workingCopyId]);

  const update = (next: EvalCaseWorkingCopyDTO) => {
    setItem(next); setDefinition(JSON.stringify(next.definition, null, 2));
    setSelectedTarget(next.evaluationTarget || '');
  };
  const showError = (reason: unknown) => setError(reason instanceof Error ? reason.message : 'Operation failed');
  const run = (operation: Promise<{ data: EvalCaseWorkingCopyDTO }>) => operation.then(({ data }) => update(data)).catch(showError);

  const save = () => {
    if (!item) return;
    try {
      run(agentApi.adminUpdateEvalCaseWorkingCopy(item.id, item.revision, JSON.parse(definition)));
    } catch { setError('Definition must be valid JSON'); }
  };

  const selectTarget = (target: EvaluationTarget) => {
    try {
      const value = JSON.parse(definition) as Record<string, unknown>;
      value.evaluationTarget = target;
      setSelectedTarget(target);
      setDefinition(JSON.stringify(value, null, 2));
    } catch { setError('Definition must be valid JSON before changing its Target'); }
  };

  const validate = () => item && agentApi.adminValidateEvalCase(item.id).then(({ data }) => {
    update(data.workingCopy);
    setEvidence(data.passed
      ? ['Validation PASS · schema, replay, graph, version, and privacy checks passed.']
      : ['Validation FAIL', ...data.evidence]);
  }).catch(showError);

  const dryRun = () => item && agentApi.adminDryRunEvalCase(item.id).then(({ data }) => {
    const outcome = data.result.status === 'PASS' ? 'Eval PASS'
      : data.result.status === 'FAIL' ? 'Eval FAIL · assertions did not pass'
        : `Infrastructure ${data.result.status} · execution was not graded as a product failure`;
    update(data.workingCopy);
    setEvidence([outcome, ...((data.result.graders || []).flatMap((g) => `${g.graderName}: ${g.passed ? 'PASS' : 'FAIL'}`))]);
    setArtifacts({ before: data.initialCanvasXml, after: data.finalCanvasXml, trace: JSON.stringify(data.trace, null, 2) });
  }).catch(showError);

  const decide = (decision: 'approve' | 'reject') => {
    if (!item || !reviewReason.trim()) return;
    run(agentApi.adminDecideEvalCase(item.id, decision, reviewReason.trim()));
    setReviewReason('');
  };

  return (
    <AdminShell active="cases">
      <EvaluationWorkspace active="cases"
        title={item?.caseId || 'Loading case'}
        description={item ? `Case Studio · version ${item.caseVersion} · revision ${item.revision} · source ${item.sourceType.toLowerCase()}` : 'Loading canonical definition…'}
        action={item && <StatusBadge value={item.status} />}
      />
      <ErrorNote message={error} />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,2fr)_minmax(18rem,1fr)]">
        <section>
          <label className="mb-4 block max-w-sm text-xs font-medium uppercase tracking-wide text-zinc-500">
            Evaluation target
            <select
              value={selectedTarget}
              onChange={(event) => event.target.value && selectTarget(event.target.value as EvaluationTarget)}
              className={`${inputCls} mt-2`}
            >
              <option value="">Select target…</option>
              {(['FULL_AGENT', 'INTENT_ROUTER', 'DRAWING_QUALITY', 'VISUAL_REVIEW'] as EvaluationTarget[]).map((value) => (
                <option key={value} value={value}>{value.replaceAll('_', ' ')}</option>
              ))}
            </select>
            {item?.targetMigrationStatus === 'AMBIGUOUS' && <span className="mt-1 block normal-case text-amber-700">Confirm and save a Target before validation.</span>}
          </label>
          <label className="mb-2 block text-xs font-medium uppercase tracking-wide text-zinc-500">Canonical definition (JSON editor)</label>
          <textarea value={definition} onChange={(event) => setDefinition(event.target.value)} className="min-h-[32rem] w-full rounded-xl border border-stone-200 bg-zinc-950 p-4 font-mono text-xs text-zinc-100 focus:border-zinc-400 focus:outline-none" />
        </section>
        <aside className="space-y-4">
          {sourceFinding && (
            <div className="rounded-xl border border-violet-200 bg-violet-50 p-4 shadow-sm">
              <h2 className="text-sm font-semibold text-violet-900">Source Finding (restricted)</h2>
              <p className="mt-1 text-xs leading-5 text-violet-800">Compare this synthetic Case with the original analysis evidence before approval. This backlink is never copied into the published artifact.</p>
              <div className="mt-2 text-xs text-violet-800">{sourceFinding.analyzerType} · {sourceFinding.failureFamily} · {sourceFinding.analysisSummary}</div>
              <a href={`/admin/runs?run=${encodeURIComponent(sourceFinding.sourceRunId)}`} className="mt-2 inline-flex text-xs font-semibold text-violet-800 hover:underline">Open audited source Trace →</a>
            </div>
          )}
          <div className="rounded-xl border border-stone-200 bg-white p-4 shadow-sm">
            <h2 className="text-sm font-semibold text-zinc-900">Qualify this Case</h2>
            <p className="mt-1 text-xs leading-5 text-zinc-500">Move from an editable definition to reproducible evidence. Publishing is the only irreversible step.</p>
            <div className="mt-4 space-y-3">
              <QualificationStep number="1" title="Define & validate" text="Save the JSON, then check schema, privacy and replay inputs.">
                <Btn onClick={save}>Save</Btn>
                <Btn variant="secondary" onClick={validate}>Validate</Btn>
              </QualificationStep>
              <QualificationStep number="2" title="Replay the Agent" text="Run the real deterministic chain with recorded model responses.">
                <Btn variant="secondary" onClick={dryRun}>Recorded dry run (Mode B)</Btn>
              </QualificationStep>
              <QualificationStep number="3" title="Review evidence" text="Submit the result, then approve it or send it back — a written reason is required.">
                <Btn variant="secondary" onClick={() => item && run(agentApi.adminSubmitEvalCaseReview(item.id))}>Submit for review</Btn>
                <input
                  aria-label="Review reason"
                  value={reviewReason}
                  onChange={(event) => setReviewReason(event.target.value)}
                  placeholder="Review reason…"
                  className={`${inputCls} text-xs`}
                />
                <Btn disabled={!reviewReason.trim()} onClick={() => decide('approve')}>Approve</Btn>
                <Btn variant="danger" disabled={!reviewReason.trim()} onClick={() => decide('reject')}>Reject</Btn>
              </QualificationStep>
              <QualificationStep number="4" title="Publish" text="Freeze this exact version so a Dataset can reference it.">
                {item?.status === 'APPROVED'
                  ? (
                    <Btn onClick={() => window.confirm(`Publish immutable ${item.caseId}@${item.caseVersion}? This cannot be undone.`)
                      && agentApi.adminPublishEvalCase(item.id).then(() => agentApi.adminGetEvalCaseWorkingCopy(item.id)
                        .then(({ data }) => update(data))).catch(showError)}>Publish immutable version</Btn>
                  )
                  : <span className="text-[11px] text-zinc-400">Available once the Case is approved</span>}
              </QualificationStep>
            </div>
          </div>
          <div className="rounded-xl border border-stone-200 bg-white p-4 shadow-sm">
            <h2 className="text-sm font-semibold text-zinc-900">Evidence</h2>
            {evidence.length === 0 && <p className="mt-2 text-xs text-zinc-400">Run Validate or a dry run to collect evidence.</p>}
            <ul className="mt-2 space-y-2 text-xs text-zinc-600">{evidence.map((value) => <li key={value}>{value}</li>)}</ul>
          </div>
          <div className="rounded-xl border border-stone-200 bg-white p-4 shadow-sm">
            <h2 className="text-sm font-semibold text-zinc-900">YAML preview</h2>
            <pre className="mt-2 max-h-64 overflow-auto whitespace-pre-wrap rounded-lg bg-stone-50 p-3 font-mono text-[10px] text-zinc-600">{toYamlPreview(item?.definition)}</pre>
          </div>
        </aside>
      </div>
      {(artifacts.before || artifacts.after) && (
        <section className="mt-5 grid gap-4 lg:grid-cols-2">
          <Artifact title="Canvas before" value={artifacts.before} />
          <Artifact title="Canvas after" value={artifacts.after} />
          <div className="lg:col-span-2"><Artifact title="Normalized Eval Trace" value={artifacts.trace} /></div>
        </section>
      )}
    </AdminShell>
  );
}

function Artifact({ title, value }: { title: string; value?: string }) {
  return (
    <div className="rounded-xl border border-stone-200 bg-white p-4 shadow-sm">
      <h2 className="text-sm font-semibold text-zinc-900">{title}</h2>
      <pre className="mt-2 max-h-64 overflow-auto whitespace-pre-wrap break-all rounded-lg bg-stone-50 p-3 font-mono text-[10px] text-zinc-600">{value || 'Unavailable'}</pre>
    </div>
  );
}

function QualificationStep({ number, title, text, children }: { number: string; title: string; text: string; children: React.ReactNode }) {
  return (
    <div className="grid grid-cols-[1.75rem_1fr] gap-2 border-t border-stone-100 pt-3 first:border-t-0 first:pt-0">
      <span className="flex h-7 w-7 items-center justify-center rounded-full bg-stone-100 font-mono text-[10px] text-zinc-500">{number}</span>
      <div>
        <p className="text-xs font-semibold text-zinc-800">{title}</p>
        <p className="mt-0.5 text-[11px] leading-4 text-zinc-500">{text}</p>
        <div className="mt-2 flex flex-wrap gap-2">{children}</div>
      </div>
    </div>
  );
}

// Preview only: the backend remains the authority for parsing and canonical publication.
function toYamlPreview(value: unknown, indent = 0): string {
  const padding = ' '.repeat(indent);
  if (Array.isArray(value)) return value.map((entry) => `${padding}- ${toYamlPreview(entry, indent + 2).trimStart()}`).join('\n');
  if (value && typeof value === 'object') return Object.entries(value as Record<string, unknown>).map(([key, entry]) => {
    if (entry && typeof entry === 'object') return `${padding}${key}:\n${toYamlPreview(entry, indent + 2)}`;
    return `${padding}${key}: ${yamlScalar(entry)}`;
  }).join('\n');
  return `${padding}${yamlScalar(value)}`;
}

function yamlScalar(value: unknown): string {
  if (value == null) return 'null';
  if (typeof value === 'string') return JSON.stringify(value);
  return String(value);
}

export default function EvalCaseStudioPage() {
  return (
    <Suspense fallback={<main className="min-h-screen bg-stone-50" />}>
      <EvalCaseStudioContent />
    </Suspense>
  );
}
