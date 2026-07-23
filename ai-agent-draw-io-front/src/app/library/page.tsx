'use client';

import Link from 'next/link';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { API_CONFIG } from '@/config/api-config';
import { createMaterialCapabilitiesClient } from '@/api/material-capabilities';
import { createMaterialClient } from '@/api/material';
import { MaterialUploader } from '@/features/materials/MaterialUploader';
import { materialCapabilityMessage } from '@/features/materials/library-view';
import type { MaterialCapabilities, MaterialCatalogCard } from '@/features/materials/material-types';

const idempotencyKey = () => globalThis.crypto.randomUUID();

export default function LibraryPage() {
  const materialClient = useMemo(() => createMaterialClient({ baseUrl: API_CONFIG.BASE_URL }), []);
  const capabilitiesClient = useMemo(() => createMaterialCapabilitiesClient({ baseUrl: API_CONFIG.BASE_URL }), []);
  const [capabilities, setCapabilities] = useState<MaterialCapabilities | null>(null);
  const [materials, setMaterials] = useState<MaterialCatalogCard[]>([]);
  const [lifecycleState, setLifecycleState] = useState<'ACTIVE' | 'TRASHED'>('ACTIVE');
  const [queryInput, setQueryInput] = useState('');
  const [query, setQuery] = useState('');
  const [offset, setOffset] = useState(0);
  const [total, setTotal] = useState(0);
  const [message, setMessage] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const pageSize = 20;

  const load = useCallback(async (state = lifecycleState) => {
    setLoading(true);
    try {
      const currentCapabilities = await capabilitiesClient.get();
      setCapabilities(currentCapabilities);
      if (currentCapabilities.catalog !== 'AVAILABLE') {
        setMaterials([]);
        return;
      }
      const page = await materialClient.list({ query, lifecycleState: state, limit: pageSize, offset });
      setMaterials(page.items);
      setTotal(page.total);
      setMessage(null);
    } catch {
      setMessage('无法加载资料库。请稍后再试。');
    } finally {
      setLoading(false);
    }
  }, [capabilitiesClient, lifecycleState, materialClient, offset, query]);

  useEffect(() => { void load(); }, [load]);

  const remove = async (material: MaterialCatalogCard) => {
    if (!window.confirm(`将“${material.displayName}”移入回收站？`)) return;
    try {
      await materialClient.remove(material.materialId, idempotencyKey());
      setMaterials(previous => previous.filter(item => item.materialId !== material.materialId));
    } catch {
      setMessage('资料不存在或你无权访问。');
    }
  };

  const restore = async (material: MaterialCatalogCard) => {
    try {
      await materialClient.restore(material.materialId, idempotencyKey());
      setMaterials(previous => previous.filter(item => item.materialId !== material.materialId));
    } catch {
      setMessage('资料不存在或你无权访问。');
    }
  };

  const capabilityMessage = capabilities && materialCapabilityMessage(capabilities);
  const switchLifecycle = (state: 'ACTIVE' | 'TRASHED') => {
    setLifecycleState(state);
    setOffset(0);
  };
  const search = (event: React.FormEvent) => {
    event.preventDefault();
    setOffset(0);
    setQuery(queryInput.trim());
  };

  return (
    <main className="app-page min-h-screen bg-stone-50 text-zinc-800">
      <header className="border-b border-stone-200 bg-white"><div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-5 py-4">
        <div><Link href="/diagrams" className="text-sm font-medium text-zinc-600 hover:underline">← 我的图表</Link><h1 className="mt-1 text-2xl font-bold">资料库</h1></div>
        <Link href="/chartbooks" className="theme-btn-secondary rounded-lg px-4 py-2 text-sm">图表册</Link>
      </div></header>
      <div className="mx-auto max-w-6xl space-y-6 px-5 py-7">
        {capabilityMessage && <div className="rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">{capabilityMessage}</div>}
        {message && <div className="rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-800">{message}</div>}
        {capabilities?.catalog === 'AVAILABLE' && <MaterialUploader
          client={materialClient}
          target={{ scopeType: 'LIBRARY', scopeId: 'library', retentionClass: 'RETAINED' }}
          acceptedMimeTypes={capabilities.acceptedMimeTypes}
          disabled={capabilities.upload !== 'AVAILABLE'}
          onReady={() => void load()}
        />}
        <section>
          <div className="mb-4 flex flex-wrap items-center justify-between gap-3"><div className="flex gap-2">
            {(['ACTIVE', 'TRASHED'] as const).map(state => <button key={state} type="button" onClick={() => switchLifecycle(state)} className={`rounded-lg px-3 py-2 text-sm ${lifecycleState === state ? 'bg-zinc-900 text-white' : 'bg-white text-zinc-700 ring-1 ring-stone-200'}`}>{state === 'ACTIVE' ? '全部资料' : '回收站'}</button>)}
          </div><span className="text-sm text-zinc-500">{loading ? '正在加载…' : `${total} 项资料`}</span></div>
          <form onSubmit={search} className="mb-4 flex gap-2"><input value={queryInput} onChange={event => setQueryInput(event.target.value)} type="search" placeholder="搜索资料名称" className="min-w-0 flex-1 rounded-lg border border-stone-300 bg-white px-3 py-2 text-sm"/><button className="theme-btn-secondary rounded-lg px-4 text-sm">搜索</button></form>
          {!loading && !capabilityMessage && materials.length === 0 && <div className="rounded-xl border border-dashed border-stone-300 bg-white p-10 text-center text-sm text-zinc-500">{lifecycleState === 'TRASHED' ? '回收站为空。' : '还没有资料。上传 PDF 或图片即可开始。'}</div>}
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">{materials.map(material => <article key={material.materialId} className="rounded-xl border border-stone-200 bg-white p-4 shadow-sm">
            <Link href={`/library/${encodeURIComponent(material.materialId)}`} className="block"><h2 className="truncate font-semibold text-zinc-900">{material.displayName}</h2><p className="mt-1 text-sm text-zinc-500">{material.kind} · {material.processingStatus} · {material.progress}%</p></Link>
            <div className="mt-4 flex items-center justify-between gap-2"><span className="text-xs text-zinc-500">{material.lifecycleState}</span>
              {lifecycleState === 'TRASHED' ? <button type="button" onClick={() => void restore(material)} className="text-sm font-medium text-zinc-700 underline">恢复</button> : <button type="button" onClick={() => void remove(material)} className="text-sm font-medium text-rose-700 underline">移入回收站</button>}
            </div>
          </article>)}</div>
          {total > pageSize && <div className="mt-5 flex items-center justify-between text-sm"><button type="button" disabled={offset === 0} onClick={() => setOffset(current => Math.max(0, current - pageSize))} className="theme-btn-secondary rounded-lg px-3 py-2 disabled:opacity-40">上一页</button><span>第 {Math.floor(offset / pageSize) + 1} 页</span><button type="button" disabled={offset + pageSize >= total} onClick={() => setOffset(current => current + pageSize)} className="theme-btn-secondary rounded-lg px-3 py-2 disabled:opacity-40">下一页</button></div>}
        </section>
      </div>
    </main>
  );
}
