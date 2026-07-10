'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { agentApi, ApiResponseError } from '@/api/agent';
import type { AdminUsageDashboardDTO } from '@/types/api';
import { buildLoginHref } from '@/utils/login-form';
import { formatMs, formatNumber } from './admin-shared';
import { AdminPageHeading, AdminShell } from './admin-shell';

const pct = (v?: number) => (v == null ? '—' : `${Math.round(v * 100)}%`);

function Metric({ label, value, tone }: { label: string; value: string; tone?: 'ok' | 'bad' }) {
  return (
    <div className="rounded-lg border border-stone-200 bg-white px-4 py-4 shadow-sm">
      <div className="text-xs font-medium text-zinc-500">{label}</div>
      <div
        className={`mt-2 font-display text-2xl font-semibold ${
          tone === 'bad' ? 'text-rose-700' : tone === 'ok' ? 'text-emerald-700' : 'text-zinc-900'
        }`}
      >
        {value}
      </div>
    </div>
  );
}

export default function AdminOverviewPage() {
  const pathname = usePathname();
  const router = useRouter();
  const returnTo = pathname;
  const [data, setData] = useState<AdminUsageDashboardDTO | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
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
      .adminUsage()
      .then((res) => {
        if (alive) setData(res.data);
      })
      .catch((e) => {
        if (!alive) return;
        if (e instanceof ApiResponseError && e.code === 'AUTH_FORBIDDEN') handleForbidden();
        else setError(e instanceof Error ? e.message : 'Failed to load usage');
      })
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
  }, [returnTo, router]);

  if (forbidden) {
    return (
      <AdminShell active="overview">
        <div className="mx-auto max-w-md py-20 text-center">
          <h1 className="font-display text-2xl font-semibold text-zinc-900">Admin access required</h1>
          <p className="mt-2 text-sm text-zinc-500">Sign in with an admin account to view telemetry.</p>
          <Link href={buildLoginHref(returnTo)} className="mt-5 inline-block text-sm font-medium text-zinc-700 hover:underline">
            Go to sign in
          </Link>
        </div>
      </AdminShell>
    );
  }

  return (
    <AdminShell active="overview">
      <AdminPageHeading
        eyebrow="Observability"
        title="Agent telemetry"
        description="Usage and reliability across every agent run."
        action={
          <Link href="/admin/runs" className="theme-btn inline-flex h-10 items-center rounded-lg px-4 text-sm font-medium transition">
            View runs
          </Link>
        }
      />

      {loading && <div className="py-20 text-center text-sm text-zinc-400">Loading telemetry…</div>}
      {error && <div className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{error}</div>}

      {data && !loading && (
        <>
          <section className="mb-8">
            <div className="mb-3 flex items-center justify-between">
              <h2 className="font-display text-base font-semibold text-zinc-800">Run health</h2>
              <span className="font-mono text-[11px] uppercase tracking-wide text-zinc-400">All time</span>
            </div>
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              <Metric label="Total runs" value={formatNumber(data.requestCount)} />
              <Metric label="Success rate" value={pct(data.requestSuccessRate)} tone="ok" />
              <Metric label="Average latency" value={formatMs(data.averageRunLatencyMs)} />
              <Metric label="Failed" value={formatNumber(data.failedRequestCount)} tone="bad" />
              <Metric label="Running" value={formatNumber(data.runningRequestCount)} />
              <Metric label="Longest run" value={formatMs(data.maxRunLatencyMs)} />
            </div>
          </section>

          <section className="mb-8 grid gap-4 lg:grid-cols-2">
            <UsageBreakdown
              title="Model activity"
              rows={[
                ['LLM calls', formatNumber(data.llmCallCount)],
                ['Failed LLM calls', formatNumber(data.failedLlmCallCount), 'bad'],
                ['Tool calls', formatNumber(data.toolCallCount)],
                ['Failed tool calls', formatNumber(data.failedToolCallCount), 'bad'],
              ]}
            />
            <UsageBreakdown
              title="Token usage"
              rows={[
                ['Total tokens', formatNumber(data.totalTokens)],
                ['Prompt tokens', formatNumber(data.promptTokens)],
                ['Completion tokens', formatNumber(data.completionTokens)],
                ['Unknown-token calls', formatNumber(data.unknownTokenLlmCallCount)],
              ]}
            />
          </section>

          {data.groups && data.groups.length > 0 && (
            <section>
              <div className="mb-3 flex items-center justify-between">
                <h2 className="font-display text-base font-semibold text-zinc-800">Provider and model</h2>
                <span className="font-mono text-[11px] uppercase tracking-wide text-zinc-400">LLM calls</span>
              </div>
              <div className="overflow-x-auto rounded-lg border border-stone-200 bg-white shadow-sm">
                <table className="w-full min-w-[680px] text-sm">
                  <thead className="border-b border-stone-200 bg-stone-50 text-left text-xs font-medium text-zinc-500">
                    <tr>
                      <th className="px-4 py-3 font-medium">Provider</th>
                      <th className="px-4 py-3 font-medium">Model</th>
                      <th className="px-4 py-3 font-medium">Source</th>
                      <th className="px-4 py-3 text-right font-medium">Calls</th>
                      <th className="px-4 py-3 text-right font-medium">Failed</th>
                      <th className="px-4 py-3 text-right font-medium">Tokens</th>
                      <th className="px-4 py-3 text-right font-medium">Avg latency</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.groups.map((g, i) => (
                      <tr key={i} className="border-t border-stone-100 text-zinc-700 transition hover:bg-stone-50/70">
                        <td className="px-4 py-3 font-medium">{g.provider || '—'}</td>
                        <td className="px-4 py-3">{g.model || '—'}</td>
                        <td className="px-4 py-3 text-zinc-500">{g.credentialSource || '—'}</td>
                        <td className="px-4 py-3 text-right font-mono text-xs">{formatNumber(g.llmCallCount)}</td>
                        <td className="px-4 py-3 text-right font-mono text-xs text-rose-700">{formatNumber(g.failedCallCount)}</td>
                        <td className="px-4 py-3 text-right font-mono text-xs">{formatNumber(g.totalTokens)}</td>
                        <td className="px-4 py-3 text-right font-mono text-xs">{formatMs(g.averageLatencyMs)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          )}
        </>
      )}
    </AdminShell>
  );
}

function UsageBreakdown({ title, rows }: { title: string; rows: [string, string, 'bad'?][] }) {
  return (
    <section className="rounded-lg border border-stone-200 bg-white px-5 py-4 shadow-sm">
      <h2 className="font-display text-base font-semibold text-zinc-800">{title}</h2>
      <dl className="mt-3 divide-y divide-stone-100 border-t border-stone-100">
        {rows.map(([label, value, tone]) => (
          <div key={label} className="flex items-center justify-between gap-4 py-2.5">
            <dt className="text-sm text-zinc-500">{label}</dt>
            <dd className={`font-mono text-sm ${tone === 'bad' ? 'text-rose-700' : 'text-zinc-800'}`}>{value}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}
