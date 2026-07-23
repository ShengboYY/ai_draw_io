'use client';

/* The protected preview endpoint needs browser cookies, so this remains a native image request. */
/* eslint-disable @next/next/no-img-element */

import { useState } from 'react';
import type { MaterialPageSet } from './material-types';
import { materialPreviewUrl } from './library-view';

export const MaterialPreview = ({
  baseUrl,
  materialId,
  pageSet,
  enabled,
}: {
  baseUrl: string;
  materialId: string;
  pageSet: MaterialPageSet | null;
  enabled: boolean;
}) => {
  const [pageNo, setPageNo] = useState<number | null>(null);
  const pages = pageSet?.pages || [];
  const selectedPage = pages.find(page => page.pageNo === pageNo) || pages[0];

  if (!enabled) return <p className="text-sm text-zinc-500">页面预览当前不可用。</p>;
  if (!pageSet) return <p className="text-sm text-zinc-500">选择版本以加载页面预览。</p>;
  if (!selectedPage) return <p className="text-sm text-zinc-500">此版本尚无可预览页面。</p>;

  const index = pages.findIndex(page => page.pageNo === selectedPage.pageNo);
  const select = (nextIndex: number) => setPageNo(pages[nextIndex]?.pageNo || selectedPage.pageNo);

  return (
    <section aria-label="资料页面预览" className="rounded-xl border border-stone-200 bg-stone-50 p-4">
      <div className="mb-3 flex items-center justify-between gap-3 text-sm">
        <span>第 {selectedPage.pageNo} 页 / 共 {pages.length} 页</span>
        <div className="flex gap-2">
          <button type="button" disabled={index === 0} onClick={() => select(index - 1)} className="theme-btn-secondary rounded-lg px-3 py-1.5 disabled:opacity-40">上一页</button>
          <button type="button" disabled={index === pages.length - 1} onClick={() => select(index + 1)} className="theme-btn-secondary rounded-lg px-3 py-1.5 disabled:opacity-40">下一页</button>
        </div>
      </div>
      {selectedPage.previewAvailable ? (
        <>
          {/* One selected page keeps preview byte requests scoped to explicit user navigation. */}
          <img
          src={materialPreviewUrl(baseUrl, materialId, pageSet.versionId, selectedPage.pageNo)}
          alt={`资料第 ${selectedPage.pageNo} 页预览`}
          className="max-h-[65vh] w-full rounded-lg border border-stone-200 bg-white object-contain"
          />
        </>
      ) : (
        <p className="rounded-lg bg-white p-6 text-sm text-zinc-500">此页预览尚未生成。</p>
      )}
    </section>
  );
};
