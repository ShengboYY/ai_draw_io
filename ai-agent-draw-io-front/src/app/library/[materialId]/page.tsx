'use client';

import Link from 'next/link';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { API_CONFIG } from '@/config/api-config';
import { createMaterialCapabilitiesClient } from '@/api/material-capabilities';
import { createMaterialClient } from '@/api/material';
import { MaterialUploader } from '@/features/materials/MaterialUploader';
import { MaterialPreview } from '@/features/materials/MaterialPreview';
import { materialAccessMessage, materialCapabilityMessage } from '@/features/materials/library-view';
import type { MaterialCapabilities, MaterialCatalogDetails, MaterialPageSet } from '@/features/materials/material-types';

const idempotencyKey = () => globalThis.crypto.randomUUID();

export default function MaterialDetailsPage() {
  const params = useParams<{ materialId: string }>();
  const router = useRouter();
  const materialId = params.materialId;
  const materialClient = useMemo(() => createMaterialClient({ baseUrl: API_CONFIG.BASE_URL }), []);
  const capabilitiesClient = useMemo(() => createMaterialCapabilitiesClient({ baseUrl: API_CONFIG.BASE_URL }), []);
  const [capabilities, setCapabilities] = useState<MaterialCapabilities | null>(null);
  const [details, setDetails] = useState<MaterialCatalogDetails | null>(null);
  const [versionId, setVersionId] = useState('');
  const [pageSet, setPageSet] = useState<MaterialPageSet | null>(null);
  const [scopeId, setScopeId] = useState('');
  const [message, setMessage] = useState<string | null>(null);

  const loadDetails = useCallback(async () => {
    try {
      const currentCapabilities = await capabilitiesClient.get();
      setCapabilities(currentCapabilities);
      if (currentCapabilities.catalog !== 'AVAILABLE') return;
      const currentDetails = await materialClient.details(materialId);
      setDetails(currentDetails);
      setVersionId(current => current || currentDetails.versions[0]?.versionId || '');
      setMessage(null);
    } catch (error) {
      const code = error && typeof error === 'object' && 'code' in error ? String(error.code) : 'HTTP_ERROR';
      setMessage(materialAccessMessage(code));
    }
  }, [capabilitiesClient, materialClient, materialId]);

  useEffect(() => {
    const timeoutId = window.setTimeout(() => { void loadDetails(); }, 0);
    return () => window.clearTimeout(timeoutId);
  }, [loadDetails]);

  useEffect(() => {
    if (!versionId || capabilities?.preview !== 'AVAILABLE') return;
    materialClient.pageSet(materialId, versionId)
      .then(setPageSet)
      .catch(() => setMessage('页面预览当前不可用。'));
  }, [materialClient, materialId, versionId, capabilities?.preview]);

  const addChartbookScope = async () => {
    if (!scopeId.trim()) return;
    try {
      setDetails(await materialClient.addScope(materialId, 'CHARTBOOK', scopeId.trim()));
      setScopeId('');
    } catch {
      setMessage(materialAccessMessage('HTTP_ERROR'));
    }
  };

  const removeScope = async (linkId: string) => {
    try {
      setDetails(await materialClient.removeScope(materialId, linkId));
    } catch {
      setMessage(materialAccessMessage('HTTP_ERROR'));
    }
  };

  const reprocess = async () => {
    try {
      await materialClient.reprocess(materialId, idempotencyKey());
      setMessage('已提交重新处理请求。');
    } catch {
      setMessage(materialAccessMessage('HTTP_ERROR'));
    }
  };

  const replaceExcludedPage = async (pageNo: number, excluded: boolean) => {
    if (!pageSet) return;
    const pageNumbers = excluded
      ? [...pageSet.excludedPages, pageNo]
      : pageSet.excludedPages.filter(page => page !== pageNo);
    try {
      await materialClient.replaceExcludedPages(materialId, pageNumbers, idempotencyKey());
      setPageSet(await materialClient.pageSet(materialId, versionId));
    } catch {
      setMessage(materialAccessMessage('HTTP_ERROR'));
    }
  };

  const moveToTrash = async () => {
    try {
      await materialClient.remove(materialId, idempotencyKey());
      router.push('/library');
    } catch {
      setMessage(materialAccessMessage('HTTP_ERROR'));
    }
  };

  const restore = async () => {
    try {
      await materialClient.restore(materialId, idempotencyKey());
      await loadDetails();
    } catch {
      setMessage(materialAccessMessage('HTTP_ERROR'));
    }
  };

  const permanentlyDelete = async () => {
    try {
      const impact = await materialClient.deletionImpact(materialId);
      const confirmation = window.prompt(`将永久删除 ${impact.versionCount} 个版本，并影响 ${impact.citationCount} 条引用。输入 DELETE 继续。`);
      if (confirmation !== 'DELETE') return;
      await materialClient.permanentlyDelete(materialId, impact, idempotencyKey());
      router.push('/library');
    } catch {
      setMessage(materialAccessMessage('HTTP_ERROR'));
    }
  };

  const capabilityMessage = capabilities && materialCapabilityMessage(capabilities);
  const selectedVersion = details?.versions.find(version => version.versionId === versionId);

  return (
    <main className="app-page min-h-screen bg-stone-50 text-zinc-800"><div className="mx-auto max-w-6xl px-5 py-7">
      <Link href="/library" className="text-sm font-medium text-zinc-600 hover:underline">← 返回资料库</Link>
      {capabilityMessage && <p className="mt-5 rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">{capabilityMessage}</p>}
      {message && <p className="mt-5 rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-800">{message}</p>}
      {details && <div className="mt-5 space-y-6">
        <header><h1 className="text-3xl font-bold text-zinc-900">{details.material.displayName}</h1><p className="mt-2 text-sm text-zinc-500">{details.material.processingStatus} · {details.material.progress}% · {details.material.lifecycleState}</p></header>
        {capabilities?.upload === 'AVAILABLE' && <MaterialUploader client={materialClient} target={{ scopeType: 'LIBRARY', scopeId: 'library', retentionClass: 'RETAINED' }} acceptedMimeTypes={capabilities.acceptedMimeTypes} newVersionOfMaterialId={materialId} onReady={() => void loadDetails()} />}
        <section className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_20rem]">
          <div><label className="mb-2 block text-sm font-medium">版本</label><select value={versionId} onChange={event => setVersionId(event.target.value)} className="w-full rounded-lg border border-stone-300 bg-white px-3 py-2 text-sm">{details.versions.map(version => <option key={version.versionId} value={version.versionId}>版本 {version.versionNo} · {version.processingStatus}</option>)}</select>
            {selectedVersion && <p className="mt-2 text-sm text-zinc-500">{selectedVersion.detectedMime} · {selectedVersion.pageCount || 0} 页 · {selectedVersion.progress}%</p>}
            <div className="mt-5"><MaterialPreview baseUrl={API_CONFIG.BASE_URL} materialId={materialId} pageSet={pageSet} enabled={capabilities?.preview === 'AVAILABLE'} /></div>
            {pageSet && <ul className="mt-4 grid gap-2 sm:grid-cols-2" aria-label="页面处理状态">{pageSet.pages.map(page => <li key={page.pageNo} className="rounded-lg border border-stone-200 bg-white p-3 text-sm"><div className="flex items-center justify-between"><span>第 {page.pageNo} 页</span><label className="flex items-center gap-1 text-xs"><input type="checkbox" checked={pageSet.excludedPages.includes(page.pageNo)} onChange={event => void replaceExcludedPage(page.pageNo, event.target.checked)} /> 排除</label></div><p className="mt-2 text-xs text-zinc-500">原生文本：{page.nativeTextStatus} · OCR：{page.ocrStatus} · 视觉：{page.visualStatus}</p>{page.errorCode && <p className="mt-1 text-xs text-amber-700">{page.errorCode}</p>}</li>)}</ul>}
          </div>
          <aside className="space-y-5 rounded-xl border border-stone-200 bg-white p-4"><div><h2 className="font-semibold">资料关联</h2><p className="mt-1 text-sm text-zinc-500">关联资料不会复制文件。</p><ul className="mt-3 space-y-2">{details.scopes.map(scope => <li key={scope.linkId} className="flex items-center justify-between gap-2 text-sm"><span>{scope.scopeType} · {scope.scopeKey}</span><button type="button" onClick={() => void removeScope(scope.linkId)} className="text-rose-700 underline">移除</button></li>)}</ul>
            <div className="mt-3 flex gap-2"><input value={scopeId} onChange={event => setScopeId(event.target.value)} placeholder="图表册 ID" className="min-w-0 flex-1 rounded-lg border border-stone-300 px-2 py-1.5 text-sm"/><button type="button" onClick={() => void addChartbookScope()} className="theme-btn-secondary rounded-lg px-3 text-sm">关联</button></div>
          </div><div className="border-t border-stone-100 pt-4"><button type="button" onClick={() => void reprocess()} className="text-sm font-medium text-zinc-700 underline">重新处理</button>{details.material.lifecycleState === 'TRASHED' ? <><button type="button" onClick={() => void restore()} className="mt-3 block text-sm font-medium text-zinc-700 underline">恢复资料</button><button type="button" onClick={() => void permanentlyDelete()} className="mt-3 block text-sm font-medium text-rose-700 underline">永久删除</button></> : <button type="button" onClick={() => void moveToTrash()} className="mt-3 block text-sm font-medium text-rose-700 underline">移入回收站</button>}</div></aside>
        </section>
      </div>}
    </div></main>
  );
}
