'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { agentApi, ApiResponseError } from '@/api/agent';
import type { AdminRunMetadataDTO } from '@/types/api';
import { buildLoginHref } from '@/utils/login-form';
import { formatMs, formatRelative, isFailed, isRunning } from '../admin-shared';
import { AdminShell } from '../admin-shell';
import { TraceAnalysisWorkspace } from '../trace-analysis-workspace';
import { RunTraceDetail } from './run-trace-detail';

const PAGE_SIZE = 50;
const STATUS_FILTERS: { label: string; value?: string }[] = [
  { label: 'All' },
  { label: 'Success', value: 'SUCCESS' },
  { label: 'Failed', value: 'FAILED' },
  { label: 'Running', value: 'RUNNING' },
];

// Reflect the selected run in the address bar without a full navigation so the
// detail pane stays mounted and the URL remains deep-linkable / shareable.
function syncRunParam(runId: string | null) {
  if (typeof window === 'undefined') return;
  const url = new URL(window.location.href);
  if (runId) url.searchParams.set('run', runId);
  else url.searchParams.delete('run');
  window.history.replaceState(window.history.state, '', url);
}

export default function AdminRunsPage() {
  const router = useRouter();
  const returnTo = '/admin/runs';
  const [runs, setRuns] = useState<AdminRunMetadataDTO[]>([]);
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [status, setStatus] = useState<string | undefined>(undefined);
  const [offset, setOffset] = useState(0);
  const [hasMore, setHasMore] = useState(true);
  const [loading, setLoading] = useState(true);
  const [forbidden, setForbidden] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Restore a deep-linked selection (/admin/runs?run=…). Read post-hydration from
  // the URL (a browser API) so the server and client markup stay in agreement.
  useEffect(() => {
    const initial = new URLSearchParams(window.location.search).get('run');
    // eslint-disable-next-line react-hooks/set-state-in-effect -- one-time mount read of an external (URL) source
    if (initial) setSelectedRunId(initial);
  }, []);

  const selectRun = useCallback((runId: string) => {
    setSelectedRunId(runId);
    syncRunParam(runId);
  }, []);

  const clearRun = useCallback(() => {
    setSelectedRunId(null);
    syncRunParam(null);
  }, []);

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
      <TraceAnalysisWorkspace active="runs" />

      <div className="xl:flex xl:items-start xl:gap-5">
        {/* Master: the trace list. Stays put while the detail pane scrolls. */}
        <div className="xl:sticky xl:top-6 xl:max-h-[calc(100vh-5.5rem)] xl:w-[360px] xl:shrink-0 xl:overflow-y-auto xl:pr-1">
          <div className="mb-3 flex items-center justify-between gap-2">
            <h1 className="font-display text-lg font-semibold text-zinc-900">Trace Runs</h1>
            <span className="font-mono text-[11px] text-zinc-400">{runs.length}{hasMore ? '+' : ''}</span>
          </div>

          <div className="mb-3 flex flex-wrap items-center gap-1" role="tablist" aria-label="Filter runs by status">
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
                  className={`h-7 rounded-md px-2.5 text-xs font-medium transition ${
                    active ? 'bg-zinc-800 text-white' : 'text-zinc-500 hover:bg-stone-100 hover:text-zinc-800'
                  }`}
                >
                  {f.label}
                </button>
              );
            })}
          </div>

          {error && <div className="mb-3 rounded-md border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-700">{error}</div>}

          <div className="overflow-hidden rounded-md border border-stone-200 bg-white">
            <ul className="divide-y divide-stone-100">
              {runs.map((r) => {
                const selected = r.id === selectedRunId;
                return (
                  <li key={r.id}>
                    <button
                      type="button"
                      onClick={() => selectRun(r.id)}
                      aria-current={selected ? 'true' : undefined}
                      className={`flex w-full items-start gap-2.5 px-3 py-2.5 text-left transition ${
                        selected ? 'bg-indigo-50/70 ring-1 ring-inset ring-indigo-100' : 'hover:bg-stone-50'
                      }`}
                    >
                      <StatusDot status={r.status} />
                      <span className="min-w-0 flex-1">
                        <span className="flex items-center justify-between gap-2">
                          <span className="truncate font-mono text-xs font-medium text-zinc-800">
                            {r.id.replace(/^aru_/, '').slice(0, 12)}…
                          </span>
                          <span className="shrink-0 font-mono text-[11px] text-zinc-400">{formatRelative(r.startedAt)}</span>
                        </span>
                        <span className="mt-0.5 flex items-center justify-between gap-2">
                          <span className="truncate text-[11px] text-zinc-500">
                            {r.agentId || '—'}{r.requestType ? ` · ${r.requestType}` : ''}
                          </span>
                          <span className={`shrink-0 font-mono text-[11px] ${isFailed(r.status) ? 'text-rose-600' : 'text-zinc-400'}`}>
                            {r.errorClass || formatMs(r.latencyMs)}
                          </span>
                        </span>
                      </span>
                    </button>
                  </li>
                );
              })}
              {runs.length === 0 && !loading && (
                <li className="px-4 py-14 text-center text-sm text-zinc-400">No runs found.</li>
              )}
            </ul>
          </div>

          <div className="mt-3 flex justify-center">
            {loading ? (
              <span className="text-xs text-zinc-400">Loading runs…</span>
            ) : hasMore && runs.length > 0 ? (
              <button
                onClick={() => {
                  const next = offset + PAGE_SIZE;
                  setLoading(true);
                  setError(null);
                  setForbidden(false);
                  fetchRuns(status, next, true);
                }}
                className="theme-btn-secondary inline-flex h-8 items-center rounded-md px-3 text-xs font-medium transition"
              >
                Load more
              </button>
            ) : null}
          </div>
        </div>

        {/* Detail: mounted in-place, no route change. Sits on the page surface
            (separated by a hairline rule) rather than inside another card. */}
        <div className="mt-6 min-w-0 flex-1 border-t border-stone-200 pt-6 xl:mt-0 xl:border-l xl:border-t-0 xl:pl-6 xl:pt-0">
          {selectedRunId ? (
            <RunTraceDetail key={selectedRunId} runId={selectedRunId} onClose={clearRun} />
          ) : (
            <div className="flex min-h-[320px] flex-col items-center justify-center px-6 py-16 text-center">
              <div className="flex h-11 w-11 items-center justify-center rounded-md bg-stone-100 text-zinc-400">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" className="h-6 w-6">
                  <path d="M4 5h16M4 10h10M4 15h13M4 20h7" strokeLinecap="round" />
                </svg>
              </div>
              <p className="mt-3 text-sm font-medium text-zinc-600">Select a trace to inspect it</p>
              <p className="mt-1 max-w-xs text-xs text-zinc-400">
                Pick a run from the list to open its timeline, span inspector and diagram evolution here.
              </p>
            </div>
          )}
        </div>
      </div>
    </AdminShell>
  );
}

function StatusDot({ status }: { status?: string }) {
  const tone = isFailed(status)
    ? 'bg-rose-500'
    : isRunning(status)
      ? 'bg-blue-500'
      : (status || '').toUpperCase() === 'SUCCESS'
        ? 'bg-emerald-500'
        : 'bg-zinc-300';
  return <span className={`mt-1 h-2 w-2 shrink-0 rounded-full ${tone}`} title={(status || '').toLowerCase()} />;
}
