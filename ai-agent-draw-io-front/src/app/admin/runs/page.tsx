'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { agentApi, ApiResponseError } from '@/api/agent';
import type { AdminRunMetadataDTO } from '@/types/api';
import { buildLoginHref } from '@/utils/login-form';
import { formatMs, formatNumber, formatRelative, statusPill } from '../admin-shared';
import { AdminPageHeading, AdminShell } from '../admin-shell';

const PAGE_SIZE = 50;
const STATUS_FILTERS: { label: string; value?: string }[] = [
  { label: 'All' },
  { label: 'Success', value: 'SUCCESS' },
  { label: 'Failed', value: 'FAILED' },
  { label: 'Running', value: 'RUNNING' },
];

export default function AdminRunsPage() {
  const pathname = usePathname();
  const router = useRouter();
  const returnTo = pathname;
  const [runs, setRuns] = useState<AdminRunMetadataDTO[]>([]);
  const [status, setStatus] = useState<string | undefined>(undefined);
  const [offset, setOffset] = useState(0);
  const [hasMore, setHasMore] = useState(true);
  const [loading, setLoading] = useState(true);
  const [forbidden, setForbidden] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchRuns = useCallback(
    (nextStatus: string | undefined, nextOffset: number, append: boolean) => {
      let alive = true;
      const handleForbidden = () => {
        agentApi.me().then(({ data: currentUser }) => {
          if (!alive) return;
          if (currentUser.status === 'SUCCESS') setForbidden(true);
          else router.replace(buildLoginHref(returnTo));
        }).catch(() => {
          if (alive) router.replace(buildLoginHref(returnTo));
        });
      };
      agentApi
        .adminListRuns({ status: nextStatus, limit: PAGE_SIZE, offset: nextOffset })
        .then((res) => {
          if (!alive) return;
          const items = res.data || [];
          setHasMore(items.length === PAGE_SIZE);
          setRuns((prev) => (append ? [...prev, ...items] : items));
          // Only advance after a successful append so a retry cannot skip a page.
          if (append) setOffset(nextOffset);
        })
        .catch((e) => {
          if (!alive) return;
          if (e instanceof ApiResponseError && e.code === 'AUTH_FORBIDDEN') handleForbidden();
          else setError(e instanceof Error ? e.message : 'Failed to load runs');
        })
        .finally(() => alive && setLoading(false));
      return () => {
        alive = false;
      };
    },
    [returnTo, router],
  );

  useEffect(() => {
    return fetchRuns(status, 0, false);
  }, [status, fetchRuns]);

  if (forbidden) {
    return (
      <AdminShell active="runs">
        <div className="mx-auto max-w-md py-20 text-center">
          <h1 className="font-display text-2xl font-semibold text-zinc-900">Admin access required</h1>
          <Link href={buildLoginHref(returnTo)} className="mt-5 inline-block text-sm font-medium text-zinc-700 hover:underline">
            Go to sign in
          </Link>
        </div>
      </AdminShell>
    );
  }

  return (
    <AdminShell active="runs">
      <AdminPageHeading
        eyebrow="Observability"
        title="Agent runs"
        description="Browse recent execution traces and open any run for the full event timeline."
      />

      <div className="mb-5 flex flex-wrap items-center gap-2 border-b border-stone-200 pb-4" role="tablist" aria-label="Filter runs by status">
        {STATUS_FILTERS.map((f) => {
          const active = status === f.value;
          return (
            <button
              key={f.label}
              onClick={() => {
                if (active) return;
                setLoading(true);
                setError(null);
                setForbidden(false);
                setOffset(0);
                setStatus(f.value);
              }}
              role="tab"
              aria-selected={active}
              className={`h-9 rounded-lg px-3 text-sm font-medium transition ${
                active
                  ? 'bg-zinc-800 text-white shadow-sm'
                  : 'text-zinc-500 hover:bg-stone-100 hover:text-zinc-800'
              }`}
            >
              {f.label}
            </button>
          );
        })}
      </div>

      {error && <div className="mb-4 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{error}</div>}

      <div className="overflow-x-auto rounded-lg border border-stone-200 bg-white shadow-sm">
        <table className="w-full min-w-[950px] text-sm">
          <thead className="border-b border-stone-200 bg-stone-50 text-left text-xs font-medium text-zinc-500">
            <tr>
              <th className="px-4 py-3 font-medium">Status</th>
              <th className="px-4 py-3 font-medium">Run</th>
              <th className="px-4 py-3 font-medium">Agent</th>
              <th className="px-4 py-3 font-medium">Type</th>
              <th className="px-4 py-3 font-medium">Activity</th>
              <th className="px-4 py-3 font-medium">Error</th>
              <th className="px-4 py-3 text-right font-medium">Latency</th>
              <th className="px-4 py-3 text-right font-medium">Started</th>
            </tr>
          </thead>
          <tbody>
            {runs.map((r) => (
              <tr key={r.id} className="border-t border-stone-100 text-zinc-700 transition hover:bg-stone-50/70">
                <td className="px-4 py-3">
                  <span
                    className={`inline-block rounded-md border px-2 py-0.5 text-xs font-medium ${statusPill(r.status)}`}
                  >
                    {(r.status || '—').toLowerCase()}
                  </span>
                </td>
                <td className="px-4 py-3">
                  <Link
                    href={`/admin/runs/${encodeURIComponent(r.id)}`}
                    className="font-mono text-xs font-medium text-zinc-800 hover:text-zinc-500 hover:underline"
                  >
                    {r.id.replace(/^aru_/, '').slice(0, 12)}…
                  </Link>
                  {r.diagramId && (
                    <div className="mt-1 max-w-40 truncate font-mono text-[11px] text-zinc-400">
                      {r.diagramId}
                    </div>
                  )}
                </td>
                <td className="px-4 py-3">{r.agentId || '—'}</td>
                <td className="px-4 py-3 text-zinc-500">{r.requestType || '—'}</td>
                <td className="px-4 py-3">
                  <div className="flex flex-wrap gap-1">
                    <CountBadge label="ev" value={r.traceEventCount} />
                    <CountBadge label="st" value={r.stepCount} />
                    <CountBadge label="llm" value={r.llmCallCount} />
                    <CountBadge label="tool" value={r.toolCallCount} />
                    <CountBadge label="tok" value={r.knownTotalTokens} />
                  </div>
                </td>
                <td className="px-4 py-3 text-rose-700">{r.errorClass || ''}</td>
                <td className="px-4 py-3 text-right font-mono text-xs">{formatMs(r.latencyMs)}</td>
                <td className="px-4 py-3 text-right font-mono text-xs text-zinc-500">{formatRelative(r.startedAt)}</td>
              </tr>
            ))}
            {runs.length === 0 && !loading && (
              <tr>
                <td colSpan={8} className="px-4 py-16 text-center text-sm text-zinc-400">
                  No runs found.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      <div className="mt-5 flex justify-center">
        {loading ? (
          <span className="text-sm text-zinc-400">Loading runs…</span>
        ) : hasMore && runs.length > 0 ? (
          <button
            onClick={() => {
              const next = offset + PAGE_SIZE;
              setLoading(true);
              setError(null);
              setForbidden(false);
              fetchRuns(status, next, true);
            }}
            className="theme-btn-secondary inline-flex h-10 items-center rounded-lg px-4 text-sm font-medium transition"
          >
            Load more
          </button>
        ) : null}
      </div>
    </AdminShell>
  );
}

function CountBadge({ label, value }: { label: string; value?: number | null }) {
  return (
    <span className="rounded border border-stone-200 bg-stone-50 px-1.5 py-0.5 font-mono text-[11px] text-zinc-500">
      {label}:{formatNumber(value)}
    </span>
  );
}
