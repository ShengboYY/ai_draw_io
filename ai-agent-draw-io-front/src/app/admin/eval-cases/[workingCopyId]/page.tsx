'use client';

import { use, useEffect, useState } from 'react';
import { agentApi } from '@/api/agent';
import type { EvalCaseWorkingCopyDTO } from '@/types/api';
import { AdminPageHeading, AdminShell } from '../../admin-shell';

export default function EvalCaseStudioPage({ params }: { params: Promise<{ workingCopyId: string }> }) {
  const { workingCopyId } = use(params);
  const [item, setItem] = useState<EvalCaseWorkingCopyDTO | null>(null);
  const [definition, setDefinition] = useState('');
  const [evidence, setEvidence] = useState<string[]>([]);
  const [artifacts, setArtifacts] = useState<{ before?: string; after?: string; trace?: string }>({});
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { agentApi.adminGetEvalCaseWorkingCopy(workingCopyId).then(({ data }) => {
    setItem(data); setDefinition(JSON.stringify(data.definition, null, 2));
  }).catch((reason) => setError(reason instanceof Error ? reason.message : 'Load failed')); }, [workingCopyId]);
  const update = (next: EvalCaseWorkingCopyDTO) => { setItem(next); setDefinition(JSON.stringify(next.definition, null, 2)); };
  const run = (operation: Promise<{ data: EvalCaseWorkingCopyDTO }>) => operation.then(({ data }) => update(data)).catch(showError);
  const showError = (reason: unknown) => setError(reason instanceof Error ? reason.message : 'Operation failed');
  const save = () => { if (!item) return; try {
    run(agentApi.adminUpdateEvalCaseWorkingCopy(item.id, item.revision, JSON.parse(definition)));
  } catch { setError('Definition must be valid JSON'); } };
  const validate = () => item && agentApi.adminValidateEvalCase(item.id).then(({ data }) => {
    update(data.workingCopy); setEvidence(data.passed
      ? ['Validation PASS · schema, replay, graph, version, and privacy checks passed.']
      : ['Validation FAIL', ...data.evidence]);
  }).catch(showError);
  const dryRun = () => item && agentApi.adminDryRunEvalCase(item.id).then(({ data }) => {
    const outcome = data.result.status === 'PASS' ? 'Eval PASS'
      : data.result.status === 'FAIL' ? 'Eval FAIL · assertions did not pass'
        : `Infrastructure ${data.result.status} · execution was not graded as a product failure`;
    update(data.workingCopy); setEvidence([outcome, ...((data.result.graders || []).flatMap((g) => `${g.grader}: ${g.passed ? 'PASS' : 'FAIL'}`))]);
    setArtifacts({ before: data.initialCanvasXml, after: data.finalCanvasXml, trace: JSON.stringify(data.trace, null, 2) });
  }).catch(showError);
  const decide = (decision: 'approve' | 'reject') => { if (!item) return; const reason = window.prompt('Review reason:'); if (reason) run(agentApi.adminDecideEvalCase(item.id, decision, reason)); };
  return <AdminShell active="cases"><AdminPageHeading eyebrow="Case Studio" title={item?.caseId || 'Loading case'}
    description={item ? `${item.status} · revision ${item.revision} · source ${item.sourceType}` : 'Loading canonical definition…'} />
    {error && <p className="mb-4 rounded-lg bg-rose-50 p-3 text-sm text-rose-700">{error}</p>}
    <div className="grid gap-5 lg:grid-cols-[minmax(0,2fr)_minmax(18rem,1fr)]"><section>
      <label className="mb-2 block text-xs font-medium uppercase tracking-wide text-zinc-500">Canonical definition (JSON editor)</label>
      <textarea value={definition} onChange={(event) => setDefinition(event.target.value)} className="min-h-[32rem] w-full rounded-lg border border-stone-200 bg-zinc-950 p-4 font-mono text-xs text-zinc-100" />
    </section><aside className="space-y-4"><div className="rounded-lg border border-stone-200 bg-white p-4">
      <h2 className="text-sm font-semibold">Qualification actions</h2><div className="mt-3 flex flex-wrap gap-2">
        <Action onClick={save}>Save</Action><Action onClick={validate}>Validate</Action><Action onClick={dryRun}>Mode B dry run</Action>
        <Action onClick={() => item && run(agentApi.adminSubmitEvalCaseReview(item.id))}>Submit review</Action>
        <Action onClick={() => decide('approve')}>Approve</Action><Action onClick={() => decide('reject')}>Reject</Action>
      </div></div><div className="rounded-lg border border-stone-200 bg-white p-4"><h2 className="text-sm font-semibold">Evidence</h2>
        <ul className="mt-2 space-y-2 text-xs text-zinc-600">{evidence.map((value) => <li key={value}>{value}</li>)}</ul></div>
      <div className="rounded-lg border border-stone-200 bg-white p-4"><h2 className="text-sm font-semibold">YAML preview</h2>
        <pre className="mt-2 max-h-64 overflow-auto whitespace-pre-wrap rounded-md bg-stone-50 p-3 font-mono text-[10px] text-zinc-600">{toYamlPreview(item?.definition)}</pre></div>
    </aside></div>
    {(artifacts.before || artifacts.after) && <section className="mt-5 grid gap-4 lg:grid-cols-2">
      <Artifact title="Canvas before" value={artifacts.before} /><Artifact title="Canvas after" value={artifacts.after} />
      <div className="lg:col-span-2"><Artifact title="Normalized Eval Trace" value={artifacts.trace} /></div>
    </section>}
  </AdminShell>;
}

function Artifact({ title, value }: { title: string; value?: string }) {
  return <div className="rounded-lg border border-stone-200 bg-white p-4"><h2 className="text-sm font-semibold">{title}</h2>
    <pre className="mt-2 max-h-64 overflow-auto whitespace-pre-wrap break-all rounded-md bg-stone-50 p-3 font-mono text-[10px] text-zinc-600">{value || 'Unavailable'}</pre></div>;
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

function Action({ children, onClick }: { children: React.ReactNode; onClick: () => void }) {
  return <button type="button" onClick={onClick} className="rounded-md bg-zinc-800 px-3 py-1.5 text-xs font-medium text-white hover:bg-zinc-700">{children}</button>;
}
