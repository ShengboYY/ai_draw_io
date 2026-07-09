'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { agentApi, ApiResponseError } from '@/api/agent';
import type { AdminRunMetadataDTO } from '@/types/api';
import { buildLoginHref } from '@/utils/login-form';
import { formatMs, formatNumber, formatRelative, statusPill } from '../admin-shared';

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
      <div className="mx-auto max-w-md px-6 py-24 text-center">
        <h1 className="text-lg font-medium text-neutral-900">Admin access required</h1>
        <Link href={buildLoginHref(returnTo)} className="mt-4 inline-block text-sm text-blue-600 hover:underline">
          Go to sign in
        </Link>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-5xl px-6 py-8">
      <div className="mb-5 flex items-center justify-between">
        <div>
          <h1 className="text-xl font-medium text-neutral-900">Runs</h1>
          <p className="mt-0.5 text-sm text-neutral-500">Most recent first.</p>
        </div>
        <Link href="/admin" className="text-sm text-neutral-500 hover:text-neutral-800">
          ← Overview
        </Link>
      </div>

      <div className="mb-4 flex gap-2">
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
              className={`rounded-lg border px-3 py-1.5 text-sm ${
                active
                  ? 'border-neutral-800 bg-neutral-900 text-white'
                  : 'border-neutral-200 text-neutral-600 hover:bg-neutral-50'
              }`}
            >
              {f.label}
            </button>
          );
        })}
      </div>

      {error && <div className="mb-4 rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>}

      <div className="overflow-x-auto rounded-xl border border-neutral-200">
        <table className="w-full text-sm">
          <thead className="bg-neutral-50 text-left text-xs text-neutral-500">
            <tr>
              <th className="px-3 py-2 font-medium">Status</th>
              <th className="px-3 py-2 font-medium">Run</th>
              <th className="px-3 py-2 font-medium">Agent</th>
              <th className="px-3 py-2 font-medium">Type</th>
              <th className="px-3 py-2 font-medium">Counts</th>
              <th className="px-3 py-2 font-medium">Error</th>
              <th className="px-3 py-2 text-right font-medium">Latency</th>
              <th className="px-3 py-2 text-right font-medium">Started</th>
            </tr>
          </thead>
          <tbody>
            {runs.map((r) => (
              <tr key={r.id} className="border-t border-neutral-100 hover:bg-neutral-50">
                <td className="px-3 py-2">
                  <span
                    className={`inline-block rounded-md border px-2 py-0.5 text-xs ${statusPill(r.status)}`}
                  >
                    {(r.status || '—').toLowerCase()}
                  </span>
                </td>
                <td className="px-3 py-2">
                  <Link
                    href={`/admin/runs/${encodeURIComponent(r.id)}`}
                    className="font-mono text-xs text-blue-600 hover:underline"
                  >
                    {r.id.replace(/^aru_/, '').slice(0, 12)}…
                  </Link>
                  {r.diagramId && (
                    <div className="mt-1 max-w-40 truncate font-mono text-[11px] text-neutral-400">
                      {r.diagramId}
                    </div>
                  )}
                </td>
                <td className="px-3 py-2 text-neutral-700">{r.agentId || '—'}</td>
                <td className="px-3 py-2 text-neutral-500">{r.requestType || '—'}</td>
                <td className="px-3 py-2">
                  <div className="flex flex-wrap gap-1">
                    <CountBadge label="ev" value={r.traceEventCount} />
                    <CountBadge label="st" value={r.stepCount} />
                    <CountBadge label="llm" value={r.llmCallCount} />
                    <CountBadge label="tool" value={r.toolCallCount} />
                    <CountBadge label="tok" value={r.knownTotalTokens} />
                  </div>
                </td>
                <td className="px-3 py-2 text-red-600">{r.errorClass || ''}</td>
                <td className="px-3 py-2 text-right">{formatMs(r.latencyMs)}</td>
                <td className="px-3 py-2 text-right text-neutral-500">{formatRelative(r.startedAt)}</td>
              </tr>
            ))}
            {runs.length === 0 && !loading && (
              <tr>
                <td colSpan={8} className="px-3 py-10 text-center text-sm text-neutral-400">
                  No runs found.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      <div className="mt-4 flex justify-center">
        {loading ? (
          <span className="text-sm text-neutral-400">Loading…</span>
        ) : hasMore && runs.length > 0 ? (
          <button
            onClick={() => {
              const next = offset + PAGE_SIZE;
              setLoading(true);
              setError(null);
              setForbidden(false);
              setOffset(next);
              fetchRuns(status, next, true);
            }}
            className="rounded-lg border border-neutral-200 px-4 py-1.5 text-sm text-neutral-600 hover:bg-neutral-50"
          >
            Load more
          </button>
        ) : null}
      </div>
    </div>
  );
}

function CountBadge({ label, value }: { label: string; value?: number | null }) {
  return (
    <span className="rounded border border-neutral-200 bg-neutral-50 px-1.5 py-0.5 font-mono text-[11px] text-neutral-500">
      {label}:{formatNumber(value)}
    </span>
  );
}
