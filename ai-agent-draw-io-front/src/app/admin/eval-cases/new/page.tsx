'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { agentApi } from '@/api/agent';
import { AdminPageHeading, AdminShell } from '../../admin-shell';

export default function NewEvalCasePage() {
  const router = useRouter();
  const [yaml, setYaml] = useState('');
  const [error, setError] = useState<string | null>(null);
  const submit = () => agentApi.adminImportEvalCaseYaml(yaml)
    .then(({ data }) => router.push(`/admin/eval-cases/${encodeURIComponent(data.id)}`))
    .catch((reason) => setError(reason instanceof Error ? reason.message : 'Import failed'));
  return <AdminShell active="cases"><AdminPageHeading eyebrow="Case Studio" title="Import Eval Case"
    description="Paste a canonical synthetic Eval Case YAML. Validation and Mode B replay happen after creation." />
    {error && <p className="mb-4 rounded-lg bg-rose-50 p-3 text-sm text-rose-700">{error}</p>}
    <textarea aria-label="Eval Case YAML" value={yaml} onChange={(event) => setYaml(event.target.value)}
      className="min-h-[32rem] w-full rounded-lg border border-stone-200 bg-zinc-950 p-4 font-mono text-xs text-zinc-100" />
    <button type="button" disabled={!yaml.trim()} onClick={submit} className="mt-4 rounded-lg bg-zinc-800 px-4 py-2 text-sm font-medium text-white disabled:opacity-40">Create working copy</button>
  </AdminShell>;
}
