'use client';

import Link from 'next/link';
import { useMemo, useState } from 'react';
import { API_CONFIG } from '@/config/api-config';
import { createMaterialClient } from '@/api/material';
import { ChartbookCreateDialog } from '@/features/chartbooks/ChartbookCreateDialog';
import { ChartbookFolderGrid } from '@/features/chartbooks/ChartbookFolderGrid';
import { applyChartbookShelfView } from '@/features/chartbooks/chartbook-shelf';
import { useChartbookShelf } from '@/features/chartbooks/use-chartbook-shelf';

// The diagrams workspace owns the primary chartbooks surface; this route stays as a
// direct entry point and renders the same shelf so both views cannot drift apart.
export default function ChartbooksPage() {
  const shelf = useChartbookShelf();
  const materialClient = useMemo(() => createMaterialClient({ baseUrl: API_CONFIG.BASE_URL }), []);
  const chartbooks = useMemo(() => applyChartbookShelfView(shelf.chartbooks), [shelf.chartbooks]);
  const [isNaming, setIsNaming] = useState(false);

  return (
    <main className="app-page min-h-screen bg-stone-50 text-zinc-800">
      <header className="border-b border-stone-200 bg-white"><div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-5 py-4"><div><Link href="/diagrams" className="text-sm font-medium text-zinc-600 hover:underline">← My diagrams</Link><h1 className="mt-1 text-2xl font-bold">Chartbooks</h1></div><Link href="/library" className="theme-btn-secondary rounded-lg px-4 py-2 text-sm">Library</Link></div></header>
      <div className="mx-auto max-w-6xl space-y-6 px-5 py-7">
        {shelf.capabilityMessage && <div className="rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">{shelf.capabilityMessage}</div>}
        {shelf.message && <div className="rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-800">{shelf.message}</div>}
        {shelf.requiresSignIn
          ? <div className="rounded-xl border border-dashed border-stone-300 bg-white p-10 text-center text-sm text-zinc-500">Sign in to use chartbooks. Chartbooks let diagrams and shared library items stay together by topic.</div>
          : <ChartbookFolderGrid
              chartbooks={chartbooks}
              onCreate={shelf.isAvailable ? () => setIsNaming(true) : undefined}
              onArchive={chartbook => void shelf.archive(chartbook)}
            />}
      </div>
      {isNaming && (
        <ChartbookCreateDialog
          hint="Keep diagrams and shared files together by topic."
          // Diagram picking lives on the workspace page, which already holds the diagram list.
          uploadTools={shelf.capabilities?.upload === 'AVAILABLE'
            ? { client: materialClient, acceptedMimeTypes: shelf.capabilities.acceptedMimeTypes }
            : undefined}
          ensureChartbook={shelf.create}
          onCancel={() => setIsNaming(false)}
          onFinish={() => setIsNaming(false)}
        />
      )}
    </main>
  );
}
