import type { ReactNode } from 'react';

export type EvaluationStep = 'overview' | 'cases' | 'datasets' | 'runs';

interface EvaluationWorkspaceProps {
  active: EvaluationStep;
  title?: string;
  description?: string;
  action?: ReactNode;
}

// AdminShell owns the active step; this component keeps page headings consistent.
export function EvaluationWorkspace({ title, description, action }: EvaluationWorkspaceProps) {
  return (
    <header className="mb-7">
      {title && description && (
        <div className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
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
