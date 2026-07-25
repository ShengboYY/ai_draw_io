'use client';

import Link from 'next/link';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { API_CONFIG } from '@/config/api-config';
import { createChartbookClient } from '@/api/chartbook';
import { createMaterialCapabilitiesClient } from '@/api/material-capabilities';
import { materialCapabilityMessage } from '@/features/materials/library-view';
import type { Chartbook, MaterialCapabilities } from '@/features/materials/material-types';

const idempotencyKey = () => globalThis.crypto.randomUUID();

export default function ChartbooksPage() {
  const chartbookClient = useMemo(() => createChartbookClient({ baseUrl: API_CONFIG.BASE_URL }), []);
  const capabilitiesClient = useMemo(() => createMaterialCapabilitiesClient({ baseUrl: API_CONFIG.BASE_URL }), []);
  const [capabilities, setCapabilities] = useState<MaterialCapabilities | null>(null);
  const [chartbooks, setChartbooks] = useState<Chartbook[]>([]);
  const [name, setName] = useState('');
  const [message, setMessage] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const currentCapabilities = await capabilitiesClient.get();
      setCapabilities(currentCapabilities);
      if (currentCapabilities.catalog !== 'AVAILABLE') return;
      setChartbooks(await chartbookClient.list());
      setMessage(null);
    } catch {
      setMessage('Unable to load chartbooks. Please try again later.');
    }
  }, [capabilitiesClient, chartbookClient]);

  useEffect(() => {
    const timeoutId = window.setTimeout(() => { void load(); }, 0);
    return () => window.clearTimeout(timeoutId);
  }, [load]);

  const create = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!name.trim()) return;
    try {
      const created = await chartbookClient.create(name.trim(), idempotencyKey());
      setChartbooks(previous => [created, ...previous]);
      setName('');
    } catch {
      setMessage('Unable to create the chartbook.');
    }
  };

  const archive = async (chartbook: Chartbook) => {
    if (!window.confirm(`Archive "${chartbook.name}"?`)) return;
    try {
      await chartbookClient.archive(chartbook.chartbookId);
      setChartbooks(previous => previous.filter(item => item.chartbookId !== chartbook.chartbookId));
    } catch {
      setMessage('This chartbook does not exist or you do not have access.');
    }
  };

  const capabilityMessage = capabilities && materialCapabilityMessage(capabilities);
  return (
    <main className="app-page min-h-screen bg-stone-50 text-zinc-800"><header className="border-b border-stone-200 bg-white"><div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-5 py-4"><div><Link href="/diagrams" className="text-sm font-medium text-zinc-600 hover:underline">← My diagrams</Link><h1 className="mt-1 text-2xl font-bold">Chartbooks</h1></div><Link href="/library" className="theme-btn-secondary rounded-lg px-4 py-2 text-sm">Library</Link></div></header>
      <div className="mx-auto max-w-6xl space-y-6 px-5 py-7">{capabilityMessage && <div className="rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">{capabilityMessage}</div>}{message && <div className="rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-800">{message}</div>}
        {capabilities?.catalog === 'AVAILABLE' && <form onSubmit={create} className="flex flex-wrap gap-3 rounded-xl border border-stone-200 bg-white p-4"><label className="sr-only" htmlFor="chartbook-name">Chartbook name</label><input id="chartbook-name" value={name} onChange={event => setName(event.target.value)} placeholder="New chartbook name" className="min-w-0 flex-1 rounded-lg border border-stone-300 px-3 py-2 text-sm"/><button className="theme-btn-primary rounded-lg px-4 py-2 text-sm">Create chartbook</button></form>}
        {capabilities?.catalog === 'AVAILABLE' && chartbooks.length === 0 && <div className="rounded-xl border border-dashed border-stone-300 bg-white p-10 text-center text-sm text-zinc-500">No chartbooks yet. Chartbooks let diagrams and shared library items stay together by topic.</div>}
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">{chartbooks.map(chartbook => <article key={chartbook.chartbookId} className="rounded-xl border border-stone-200 bg-white p-4 shadow-sm"><Link href={`/chartbooks/${encodeURIComponent(chartbook.chartbookId)}`}><h2 className="font-semibold text-zinc-900">{chartbook.name}</h2><p className="mt-2 text-sm text-zinc-500">{chartbook.diagramIds.length} {chartbook.diagramIds.length === 1 ? 'diagram' : 'diagrams'} · {chartbook.materialIds.length} shared {chartbook.materialIds.length === 1 ? 'item' : 'items'}</p></Link><button type="button" onClick={() => void archive(chartbook)} className="mt-4 text-sm font-medium text-rose-700 underline">Archive</button></article>)}</div>
      </div>
    </main>
  );
}
