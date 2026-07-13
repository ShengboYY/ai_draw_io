import Link from 'next/link';
import type { ReactNode } from 'react';

export type EvaluationStep = 'overview' | 'cases' | 'datasets' | 'runs';

interface WorkflowTab {
  id: EvaluationStep;
  label: string;
  sub: string;
  hint: string;
  href: string;
}

// Evaluation is the repeatable validation path. Trace discovery has its own workspace.
const evaluationTabs: WorkflowTab[] = [
  { id: 'overview', label: 'Overview', sub: 'Choose where to start', hint: 'Evaluation capability overview', href: '/admin/evaluations' },
  { id: 'cases', label: 'Cases', sub: 'Define expected behavior', hint: 'Author or import synthetic Cases', href: '/admin/eval-cases' },
  { id: 'datasets', label: 'Datasets', sub: 'Freeze Case versions', hint: 'Pin exact published Case versions', href: '/admin/eval-datasets' },
  { id: 'runs', label: 'Runs', sub: 'Execute and inspect', hint: 'Run a Dataset and inspect results', href: '/admin/eval-runs' },
];

interface EvaluationWorkspaceProps {
  active: EvaluationStep;
  title?: string;
  description?: string;
  action?: ReactNode;
}

// Page title remains optional during the R1 compatibility window because older pages
// still render AdminPageHeading above this shared navigation.
export function EvaluationWorkspace({ active, title, description, action }: EvaluationWorkspaceProps) {
  return (
    <header className="mb-7">
      <nav
        aria-label="Evaluation workspace"
        className="flex flex-wrap items-center gap-x-2 gap-y-2 rounded-xl border border-stone-200 bg-white p-1.5 shadow-sm"
      >
        <TabGroup label="Evaluation" tabs={evaluationTabs} active={active} separator="then" />
        <span className="ml-auto hidden pr-3 text-[11px] text-zinc-400 xl:block">Versioned Cases → Dataset → repeatable Run</span>
      </nav>

      {title && description && (
        <div className="mt-6 flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
          <div className="min-w-0">
            <h1 className="font-display text-2xl font-semibold text-zinc-900 sm:text-3xl">{title}</h1>
            <p className="mt-1.5 max-w-2xl text-sm leading-6 text-zinc-500">{description}</p>
          </div>
          {action && <div className="flex shrink-0 items-center gap-2">{action}</div>}
        </div>
      )}
    </header>
  );
}

function TabGroup({ label, tabs, active, separator }: { label: string; tabs: WorkflowTab[]; active: EvaluationStep; separator: string }) {
  return (
    <div role="group" aria-label={label} className="flex flex-wrap items-center gap-1 rounded-lg bg-stone-50 p-1 ring-1 ring-inset ring-stone-100">
      <span className="px-2 text-[10px] font-semibold uppercase tracking-[0.1em] text-zinc-400">{label}</span>
      {tabs.map((tab, index) => {
        const current = tab.id === active;
        return (
          <span key={tab.id} className="flex items-center gap-1">
            {index > 0 && <span aria-hidden="true" className="px-0.5 text-[10px] italic text-zinc-400">{separator}</span>}
            <Link
              href={tab.href}
              title={tab.hint}
              aria-current={current ? 'step' : undefined}
              className={`block rounded-md px-3 py-1.5 transition ${
                current ? 'bg-zinc-900 text-white shadow-sm' : 'text-zinc-600 hover:bg-white hover:text-zinc-900'
              }`}
            >
              <span className="block text-xs font-semibold leading-4">{tab.label}</span>
              <span className={`block text-[10px] leading-4 ${current ? 'text-zinc-300' : 'text-zinc-400'}`}>{tab.sub}</span>
            </Link>
          </span>
        );
      })}
    </div>
  );
}
