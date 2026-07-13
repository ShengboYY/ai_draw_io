'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { agentApi } from '@/api/agent';
import type { EvalCaseWorkingCopyDTO, PublishedEvalCaseDTO } from '@/types/api';
import { AdminPageHeading, AdminShell } from '../admin-shell';

export default function AdminEvalCasesPage() {
  const router = useRouter();
  const [cases, setCases] = useState<EvalCaseWorkingCopyDTO[]>([]);
  const [published, setPublished] = useState<PublishedEvalCaseDTO[]>([]);
  const [status, setStatus] = useState('');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    agentApi.adminListEvalCaseWorkingCopies(status || undefined)
      .then(({ data }) => setCases(data || []))
      .catch((reason) => setError(reason instanceof Error ? reason.message : 'Failed to load cases'));
  }, [status]);
  useEffect(() => { agentApi.adminListPublishedEvalCases().then(({ data }) => setPublished(data || [])).catch(() => undefined); }, []);
  const clonePublished = (item: PublishedEvalCaseDTO) => { const newCaseId = window.prompt('New Case ID:', `${item.caseId}-variant`); const newVersion = window.prompt('New Case version:', '1'); if (!newCaseId || !newVersion) return; agentApi.adminClonePublishedEvalCase(item.caseId, item.caseVersion, newCaseId, newVersion).then(({ data }) => router.push(`/admin/eval-cases/${encodeURIComponent(data.id)}`)).catch((reason) => setError(reason instanceof Error ? reason.message : 'Clone failed')); };
  const retire = (item: PublishedEvalCaseDTO) => window.confirm(`Retire ${item.caseId}@${item.caseVersion}?`) && agentApi.adminRetirePublishedEvalCase(item.caseId, item.caseVersion).then(({ data }) => setPublished((all) => all.map((entry) => entry.caseId === data.caseId && entry.caseVersion === data.caseVersion ? data : entry))).catch((reason) => setError(reason instanceof Error ? reason.message : 'Retire failed'));

  return <AdminShell active="cases">
    <AdminPageHeading eyebrow="Evaluation Control Plane" title="Eval Cases"
      description="Create, qualify, and review synthetic cases before immutable publication."
      action={<Link href="/admin/eval-cases/new" className="rounded-lg bg-zinc-800 px-4 py-2 text-sm font-medium text-white">New case</Link>} />
    <label className="mb-5 block text-xs font-medium text-zinc-500">Status
      <select value={status} onChange={(event) => setStatus(event.target.value)} className="ml-2 rounded-md border border-stone-200 bg-white px-2 py-1.5 text-sm">
        <option value="">All</option>{['DRAFT', 'VALIDATED', 'DRY_RUN_PASSED', 'UNDER_REVIEW', 'APPROVED', 'REJECTED'].map((value) => <option key={value}>{value}</option>)}
      </select>
    </label>
    {error && <p className="mb-4 rounded-lg bg-rose-50 p-3 text-sm text-rose-700">{error}</p>}
    <div className="space-y-3">{cases.map((item) => <Link key={item.id} href={`/admin/eval-cases/${encodeURIComponent(item.id)}`}
      className="flex items-center justify-between rounded-lg border border-stone-200 bg-white p-4 shadow-sm hover:border-stone-300">
      <div><div className="font-medium text-zinc-900">{item.caseId} <span className="text-zinc-400">v{item.caseVersion}</span></div>
        <div className="mt-1 font-mono text-xs text-zinc-400">{item.sourceType} · revision {item.revision} · {item.ownerUserId}</div></div>
      <span className="rounded-full bg-stone-100 px-2 py-1 font-mono text-[10px] text-zinc-600">{item.status}</span>
    </Link>)}</div>
    <h2 className="mb-3 mt-9 text-lg font-semibold text-zinc-900">Published versions</h2>
    <div className="overflow-hidden rounded-lg border border-stone-200 bg-white">{published.map((item) => <div key={`${item.caseId}:${item.caseVersion}`} className="grid grid-cols-[1fr_auto] gap-3 border-b border-stone-100 p-4 last:border-b-0">
      <div><span className="font-medium">{item.caseId}@{item.caseVersion}</span><div className="mt-1 font-mono text-[10px] text-zinc-400">{item.contentHash}</div></div>
      <div className="flex items-center gap-2"><button onClick={() => clonePublished(item)} className="text-xs text-zinc-600 hover:underline">Clone</button>{!item.retiredAt && <button onClick={() => retire(item)} className="text-xs text-rose-600 hover:underline">Retire</button>}<span className="text-xs text-zinc-500">{item.retiredAt ? 'RETIRED' : 'PUBLISHED'}</span></div>
    </div>)}</div>
  </AdminShell>;
}
