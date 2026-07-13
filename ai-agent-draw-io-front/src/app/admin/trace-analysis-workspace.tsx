import Link from 'next/link';
import type { ReactNode } from 'react';

export type TraceAnalysisStep = 'runs' | 'findings';

const traceTabs: Array<{
  id: TraceAnalysisStep;
  label: string;
  description: string;
  href: string;
}> = [
  { id: 'runs', label: 'Trace Runs', description: 'Inspect execution evidence', href: '/admin/runs' },
  { id: 'findings', label: 'Findings', description: 'Review rule, LLM and VLM signals', href: '/admin/trace-findings' },
];

interface TraceAnalysisWorkspaceProps {
  active: TraceAnalysisStep;
  title?: string;
  description?: string;
  action?: ReactNode;
}

// Analysis Jobs and Recommendations stay inside Findings for the first workspace slice.
export function TraceAnalysisWorkspace({ active, title, description, action }: TraceAnalysisWorkspaceProps) {
  return (
    <header className="mb-7">
      <nav
        aria-label="Trace Analysis workspace"
        className="flex flex-wrap items-center gap-1 rounded-xl border border-stone-200 bg-white p-1.5 shadow-sm"
      >
        <span className="px-2 text-[10px] font-semibold uppercase tracking-[0.1em] text-zinc-400">Trace Analysis</span>
        {traceTabs.map((tab) => {
          const current = tab.id === active;
          return (
            <Link
              key={tab.id}
              href={tab.href}
              aria-current={current ? 'page' : undefined}
              className={`rounded-md px-3 py-1.5 transition ${
                current ? 'bg-zinc-900 text-white shadow-sm' : 'text-zinc-600 hover:bg-stone-50 hover:text-zinc-900'
              }`}
            >
              <span className="block text-xs font-semibold leading-4">{tab.label}</span>
              <span className={`block text-[10px] leading-4 ${current ? 'text-zinc-300' : 'text-zinc-400'}`}>{tab.description}</span>
            </Link>
          );
        })}
        <span className="ml-auto hidden pr-3 text-[11px] text-zinc-400 lg:block">Findings are evidence, not Evaluation failures</span>
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
