import type { ReactNode } from 'react';

export function TraceWorkbench({
  showDiagramPreview,
  children,
}: {
  showDiagramPreview: boolean;
  children: ReactNode;
}) {
  // The layout is a component seam so the real workbench can be rendered in component tests.
  return (
    <div
      data-testid="trace-workbench"
      className={`grid grid-cols-1 items-start gap-4 ${
        showDiagramPreview
          ? 'xl:grid-cols-[minmax(260px,0.8fr)_minmax(420px,1.25fr)_minmax(320px,0.9fr)]'
          : 'xl:grid-cols-[minmax(280px,0.75fr)_minmax(520px,1.75fr)]'
      }`}
    >
      {children}
    </div>
  );
}
