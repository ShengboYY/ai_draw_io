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

const targets = [
  { name: 'Full Agent', target: 'FULL_AGENT', description: 'Test the complete router → tools → canvas → response chain.', profile: 'full-agent-smoke' },
  { name: 'Intent Router', target: 'INTENT_ROUTER', description: 'Measure route decisions without paying for drawing execution.', profile: 'router-deterministic' },
  { name: 'Drawing Quality', target: 'DRAWING_QUALITY', description: 'Score XML structure, graph semantics, preservation and visual quality.', profile: 'drawing-structure' },
  { name: 'Visual Review', target: 'VISUAL_REVIEW', description: 'Evaluate the production VLM reviewer against rendered PNG evidence.', profile: 'production-visual-review' },
];

export default function AdminEvaluationsPage() {
  return (
    <AdminShell active="evalOverview">
      <EvaluationWorkspace
        active="overview"
        title="Evaluation"
        description="Validate the Agent against versioned Cases and repeatable Datasets. Trace investigation lives in its own workspace."
        action={<Link href="/admin/eval-cases/new" className="theme-btn inline-flex h-10 items-center rounded-lg px-4 text-sm font-semibold transition">Create Case</Link>}
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

      <section className="mt-6" aria-label="Evaluation targets">
        <h2 className="font-display text-lg font-semibold text-zinc-900">Choose what you want to measure</h2>
        <p className="mt-1 text-sm text-zinc-500">Each Target has its own Case contract, execution adapter and metrics. Profiles define how it runs.</p>
        <div className="mt-3 grid gap-4 md:grid-cols-3">
          {targets.map((target) => (
            <article key={target.target} className="rounded-xl border border-stone-200 bg-white p-5 shadow-sm">
              <span className="font-mono text-[10px] text-zinc-400">{target.target}</span>
              <h3 className="mt-2 font-semibold text-zinc-900">{target.name}</h3>
              <p className="mt-2 min-h-12 text-sm leading-6 text-zinc-500">{target.description}</p>
              <p className="mt-3 text-xs text-zinc-400">Recommended first Profile: <span className="font-mono text-zinc-600">{target.profile}@1</span></p>
              <Link href="/admin/eval-runs" className="mt-4 inline-flex text-sm font-semibold text-zinc-700 hover:underline">Configure a Run →</Link>
            </article>
          ))}
        </div>
      </section>

      <section className="mt-5 rounded-xl border border-dashed border-stone-300 bg-stone-50 p-5">
        <h2 className="text-sm font-semibold text-zinc-800">No production traffic required</h2>
        <p className="mt-1 text-sm leading-6 text-zinc-500">Start with recorded-model Mode B Cases for deterministic regression coverage. Live-model evaluation can be added when you need to measure model behavior.</p>
      </section>
    </AdminShell>
  );
}
