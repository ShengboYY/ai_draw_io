'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { agentApi } from '@/api/agent';
import { AdminShell } from '../../admin-shell';
import { EvaluationWorkspace } from '../../evaluation-workspace';
import { Btn, ErrorNote } from '../../eval-ui';
import type { EvaluationTarget } from '@/types/api';

const TARGETS: EvaluationTarget[] = ['FULL_AGENT', 'INTENT_ROUTER', 'DRAWING_QUALITY', 'VISUAL_REVIEW'];

export default function NewEvalCasePage() {
  const router = useRouter();
  const [target, setTarget] = useState<EvaluationTarget>('FULL_AGENT');
  const [yaml, setYaml] = useState('evaluationTarget: FULL_AGENT\n');
  const [error, setError] = useState<string | null>(null);
  const selectTarget = (value: EvaluationTarget) => {
    setTarget(value);
    setYaml((current) => /^evaluationTarget:.*$/m.test(current)
      ? current.replace(/^evaluationTarget:.*$/m, `evaluationTarget: ${value}`)
      : `evaluationTarget: ${value}\n${current}`);
  };
  const submit = () => agentApi.adminImportEvalCaseYaml(yaml)
    .then(({ data }) => router.push(`/admin/eval-cases/${encodeURIComponent(data.id)}`))
    .catch((reason) => setError(reason instanceof Error ? reason.message : 'Import failed'));
  return (
    <AdminShell active="cases">
      <EvaluationWorkspace active="cases"
        title="Import an Eval Case"
        description="Paste a canonical synthetic Eval Case YAML to create an editable working copy."
      />
      <div className="mb-4 rounded-xl border border-blue-200 bg-blue-50 px-4 py-3 text-xs leading-5 text-blue-900">
        <strong>What happens next:</strong> this creates an editable working copy — nothing is published yet. You will still validate it, run a recorded-model replay, review the evidence, and explicitly publish an immutable version.
      </div>
      <ErrorNote message={error} />
      <label className="mb-4 block max-w-sm text-xs font-medium uppercase tracking-wide text-zinc-500">
        Evaluation target
        <select value={target} onChange={(event) => selectTarget(event.target.value as EvaluationTarget)} className="mt-2 h-10 w-full rounded-lg border border-stone-200 bg-white px-3 text-sm text-zinc-800">
          {TARGETS.map((value) => <option key={value} value={value}>{value.replaceAll('_', ' ')}</option>)}
        </select>
      </label>
      <textarea
        aria-label="Eval Case YAML"
        value={yaml}
        onChange={(event) => setYaml(event.target.value)}
        placeholder={'caseId: my-first-case\ncaseVersion: "1"\n…'}
        className="min-h-[32rem] w-full rounded-xl border border-stone-200 bg-zinc-950 p-4 font-mono text-xs text-zinc-100 placeholder:text-zinc-500 focus:border-zinc-400 focus:outline-none"
      />
      <div className="mt-4">
        <Btn size="md" disabled={!yaml.trim()} onClick={submit}>Create working copy</Btn>
      </div>
    </AdminShell>
  );
}
