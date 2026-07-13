import Link from 'next/link';

export type EvaluationStep = 'overview' | 'discover' | 'cases' | 'datasets' | 'runs';

const steps: { id: Exclude<EvaluationStep, 'overview'>; label: string; hint: string; href: string }[] = [
  { id: 'cases', label: 'Build Cases', hint: 'Define expected behavior', href: '/admin/eval-cases' },
  { id: 'datasets', label: 'Curate Dataset', hint: 'Freeze coverage', href: '/admin/eval-datasets' },
  { id: 'runs', label: 'Run & Inspect', hint: 'Replay the Agent', href: '/admin/eval-runs' },
  { id: 'discover', label: 'Discover & Repeat', hint: 'Trace → next Case', href: '/admin/eval-candidates' },
];

export function EvaluationWorkspace({ active }: { active: EvaluationStep }) {
  return (
    <section className="mb-7 overflow-hidden rounded-2xl border border-stone-200 bg-white shadow-sm" aria-label="Evaluation workflow">
      <div className="flex flex-col gap-3 border-b border-stone-200 bg-stone-50/80 px-4 py-4 sm:flex-row sm:items-center sm:justify-between sm:px-5">
        <div>
          <Link href="/admin/evaluations" className="font-display text-base font-semibold text-zinc-900 hover:text-zinc-600">
            Evaluation workspace
          </Link>
          <p className="mt-0.5 text-xs text-zinc-500">Build evidence, inspect failures, then improve &amp; repeat.</p>
        </div>
        <Link href="/admin/eval-cases/new" className="inline-flex h-9 items-center justify-center rounded-lg bg-zinc-800 px-3 text-xs font-medium text-white transition hover:bg-zinc-700">
          + New Case
        </Link>
      </div>

      {/* This is workflow navigation, not completion state: operators may enter the loop at any step. */}
      <nav className="grid divide-y divide-stone-100 sm:grid-cols-4 sm:divide-x sm:divide-y-0" aria-label="Evaluation steps">
        {steps.map((step, index) => {
          const current = step.id === active;
          return (
            <Link
              key={step.id}
              href={step.href}
              aria-current={current ? 'step' : undefined}
              className={`group flex min-w-0 items-center gap-3 px-4 py-3.5 transition ${
                current ? 'bg-zinc-900 text-white' : 'text-zinc-600 hover:bg-stone-50 hover:text-zinc-900'
              }`}
            >
              <span className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-full font-mono text-[11px] ${current ? 'bg-white text-zinc-900' : 'bg-stone-100 text-zinc-500 group-hover:bg-stone-200'}`}>{index + 1}</span>
              <span className="min-w-0">
                <span className="block truncate text-sm font-semibold">{step.label}</span>
                <span className={`block truncate text-[11px] ${current ? 'text-zinc-300' : 'text-zinc-400'}`}>{step.hint}</span>
              </span>
            </Link>
          );
        })}
      </nav>
    </section>
  );
}
