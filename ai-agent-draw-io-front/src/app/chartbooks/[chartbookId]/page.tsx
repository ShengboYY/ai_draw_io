'use client';

import Link from 'next/link';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useParams } from 'next/navigation';
import { API_CONFIG } from '@/config/api-config';
import { createChartbookClient } from '@/api/chartbook';
import { createMaterialClient } from '@/api/material';
import { createMaterialCapabilitiesClient } from '@/api/material-capabilities';
import { MaterialUploader } from '@/features/materials/MaterialUploader';
import { materialAccessMessage, materialCapabilityMessage } from '@/features/materials/library-view';
import type { Chartbook, MaterialCapabilities, MaterialCatalogCard } from '@/features/materials/material-types';

export default function ChartbookDetailsPage() {
  const params = useParams<{ chartbookId: string }>();
  const chartbookId = params.chartbookId;
  const chartbookClient = useMemo(() => createChartbookClient({ baseUrl: API_CONFIG.BASE_URL }), []);
  const materialClient = useMemo(() => createMaterialClient({ baseUrl: API_CONFIG.BASE_URL }), []);
  const capabilitiesClient = useMemo(() => createMaterialCapabilitiesClient({ baseUrl: API_CONFIG.BASE_URL }), []);
  const [capabilities, setCapabilities] = useState<MaterialCapabilities | null>(null);
  const [chartbook, setChartbook] = useState<Chartbook | null>(null);
  const [materials, setMaterials] = useState<MaterialCatalogCard[]>([]);
  const [name, setName] = useState('');
  const [materialId, setMaterialId] = useState('');
  const [diagramId, setDiagramId] = useState('');
  const [message, setMessage] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const currentCapabilities = await capabilitiesClient.get();
      setCapabilities(currentCapabilities);
      if (currentCapabilities.catalog !== 'AVAILABLE') return;
      const [currentChartbook, materialPage] = await Promise.all([
        chartbookClient.details(chartbookId), materialClient.list({ lifecycleState: 'ACTIVE', limit: 100 }),
      ]);
      setChartbook(currentChartbook);
      setName(currentChartbook.name);
      setMaterials(materialPage.items);
      setMessage(null);
    } catch {
      setMessage(materialAccessMessage('HTTP_ERROR'));
    }
  }, [capabilitiesClient, chartbookClient, chartbookId, materialClient]);

  useEffect(() => {
    const timeoutId = window.setTimeout(() => { void load(); }, 0);
    return () => window.clearTimeout(timeoutId);
  }, [load]);

  const rename = async () => {
    if (!name.trim()) return;
    try { setChartbook(await chartbookClient.rename(chartbookId, name.trim())); } catch { setMessage(materialAccessMessage('HTTP_ERROR')); }
  };
  const addMaterial = async () => {
    if (!materialId) return;
    try { setChartbook(await chartbookClient.addMaterial(chartbookId, materialId)); setMaterialId(''); } catch { setMessage(materialAccessMessage('HTTP_ERROR')); }
  };
  const removeMaterial = async (id: string) => {
    try { setChartbook(await chartbookClient.removeMaterial(chartbookId, id)); } catch { setMessage(materialAccessMessage('HTTP_ERROR')); }
  };
  const assignDiagram = async () => {
    if (!diagramId.trim()) return;
    try { setChartbook(await chartbookClient.assignDiagram(diagramId.trim(), chartbookId)); setDiagramId(''); } catch { setMessage(materialAccessMessage('HTTP_ERROR')); }
  };
  const removeDiagram = async (id: string) => {
    try { setChartbook(await chartbookClient.removeDiagram(id).then(() => chartbookClient.details(chartbookId))); } catch { setMessage(materialAccessMessage('HTTP_ERROR')); }
  };

  const capabilityMessage = capabilities && materialCapabilityMessage(capabilities);
  const availableMaterials = materials.filter(material => !chartbook?.materialIds.includes(material.materialId));
  return (
    <main className="app-page min-h-screen bg-stone-50 text-zinc-800"><div className="mx-auto max-w-6xl px-5 py-7"><Link href="/chartbooks" className="text-sm font-medium text-zinc-600 hover:underline">← Back to Chartbooks</Link>
      {capabilityMessage && <p className="mt-5 rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">{capabilityMessage}</p>}{message && <p className="mt-5 rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-800">{message}</p>}
      {chartbook && <div className="mt-5 space-y-6"><header className="flex flex-wrap items-end gap-3"><div className="min-w-0 flex-1"><label htmlFor="chartbook-title" className="text-sm font-medium text-zinc-600">Chartbook name</label><input id="chartbook-title" value={name} onChange={event => setName(event.target.value)} className="mt-1 w-full rounded-lg border border-stone-300 bg-white px-3 py-2 text-2xl font-bold"/></div><button type="button" onClick={() => void rename()} className="theme-btn-secondary rounded-lg px-4 py-2 text-sm">Save name</button></header>
        {capabilities?.upload === 'AVAILABLE' && <MaterialUploader client={materialClient} target={{ scopeType: 'CHARTBOOK', scopeId: chartbookId, retentionClass: 'RETAINED' }} acceptedMimeTypes={capabilities.acceptedMimeTypes} onReady={() => void load()} />}
        <section className="grid gap-6 lg:grid-cols-2"><div className="rounded-xl border border-stone-200 bg-white p-4"><h2 className="font-semibold">Shared library items</h2><div className="mt-3 flex gap-2"><select value={materialId} onChange={event => setMaterialId(event.target.value)} className="min-w-0 flex-1 rounded-lg border border-stone-300 px-2 py-1.5 text-sm"><option value="">Choose an item from your library</option>{availableMaterials.map(material => <option key={material.materialId} value={material.materialId}>{material.displayName}</option>)}</select><button type="button" onClick={() => void addMaterial()} className="theme-btn-secondary rounded-lg px-3 text-sm">Add</button></div><ul className="mt-4 space-y-2">{chartbook.materialIds.map(id => <li key={id} className="flex items-center justify-between gap-2 rounded-lg bg-stone-50 p-2 text-sm"><Link href={`/library/${encodeURIComponent(id)}`} className="truncate underline">{materials.find(material => material.materialId === id)?.displayName || id}</Link><button type="button" onClick={() => void removeMaterial(id)} className="text-rose-700 underline">Remove</button></li>)}</ul></div>
          <div className="rounded-xl border border-stone-200 bg-white p-4"><h2 className="font-semibold">Diagrams</h2><p className="mt-1 text-sm text-zinc-500">Enter a diagram ID to move that diagram into this chartbook.</p><div className="mt-3 flex gap-2"><input value={diagramId} onChange={event => setDiagramId(event.target.value)} placeholder="Diagram ID" className="min-w-0 flex-1 rounded-lg border border-stone-300 px-2 py-1.5 text-sm"/><button type="button" onClick={() => void assignDiagram()} className="theme-btn-secondary rounded-lg px-3 text-sm">Move in</button></div><ul className="mt-4 space-y-2">{chartbook.diagramIds.map(id => <li key={id} className="flex items-center justify-between gap-2 rounded-lg bg-stone-50 p-2 text-sm"><Link href={`/drawio?diagramId=${encodeURIComponent(id)}`} className="truncate underline">{id}</Link><button type="button" onClick={() => void removeDiagram(id)} className="text-rose-700 underline">Remove</button></li>)}</ul></div>
        </section>
      </div>}
    </div></main>
  );
}
