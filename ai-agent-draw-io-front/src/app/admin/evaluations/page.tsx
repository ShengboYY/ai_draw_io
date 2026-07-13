import Link from 'next/link';
import { AdminShell } from '../admin-shell';
import { EvaluationWorkspace } from '../evaluation-workspace';

const steps = [
  {
    title: 'Build Cases',
    description: 'Create or import synthetic tasks with explicit expected behavior.',
    href: '/admin/eval-cases',
    action: 'Open Cases',
  },
  {
    title: 'Curate Datasets',
    description: 'Pin immutable Case versions into a repeatable test collection.',
    href: '/admin/eval-datasets',
    action: 'Open Datasets',
  },
  {
    title: 'Run and inspect',
    description: 'Execute a fixed Dataset and compare grader evidence without production traffic.',
    href: '/admin/eval-runs',
    action: 'Open Runs',
  },
];

export default function AdminEvaluationsPage() {
  return (
    <AdminShell active="evalOverview">
      <EvaluationWorkspace
        active="overview"
        title="Evaluation"
        description="Validate the Agent against versioned Cases and repeatable Datasets. Trace investigation lives in its own workspace."
        action={<Link href="/admin/eval-cases/new" className="theme-btn-primary inline-flex h-10 items-center rounded-lg px-4 text-sm font-semibold">Create Case</Link>}
      />

      <section className="grid gap-4 md:grid-cols-3" aria-label="Evaluation getting started">
        {steps.map((step, index) => (
          <article key={step.title} className="rounded-xl border border-stone-200 bg-white p-5 shadow-sm">
            <span className="font-mono text-[11px] font-semibold text-zinc-400">0{index + 1}</span>
            <h2 className="mt-3 font-display text-lg font-semibold text-zinc-900">{step.title}</h2>
            <p className="mt-2 min-h-12 text-sm leading-6 text-zinc-500">{step.description}</p>
            <Link href={step.href} className="mt-4 inline-flex text-sm font-semibold text-zinc-700 hover:underline">{step.action} →</Link>
          </article>
        ))}
      </section>

      <section className="mt-5 rounded-xl border border-dashed border-stone-300 bg-stone-50 p-5">
        <h2 className="text-sm font-semibold text-zinc-800">No production traffic required</h2>
        <p className="mt-1 text-sm leading-6 text-zinc-500">Start with recorded-model Mode B Cases for deterministic regression coverage. Live-model evaluation can be added when you need to measure model behavior.</p>
      </section>
    </AdminShell>
  );
}
