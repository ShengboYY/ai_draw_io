'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { agentApi, ApiResponseError } from '@/api/agent';
import type { AdminUsageDashboardDTO } from '@/types/api';
import { buildLoginHref } from '@/utils/login-form';
import { formatMs, formatNumber } from './admin-shared';

const pct = (v?: number) => (v == null ? '—' : `${Math.round(v * 100)}%`);

function Metric({ label, value, tone }: { label: string; value: string; tone?: 'ok' | 'bad' }) {
  return (
    <div className="rounded-xl bg-neutral-50 px-4 py-3">
      <div className="text-xs text-neutral-500">{label}</div>
      <div
        className={`mt-1 text-2xl font-medium ${
          tone === 'bad' ? 'text-red-600' : tone === 'ok' ? 'text-emerald-600' : 'text-neutral-900'
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
      <div className="mx-auto max-w-md px-6 py-24 text-center">
        <h1 className="text-lg font-medium text-neutral-900">Admin access required</h1>
        <p className="mt-2 text-sm text-neutral-500">
          Sign in with an admin account to view telemetry.
        </p>
        <Link href={buildLoginHref(returnTo)} className="mt-4 inline-block text-sm text-blue-600 hover:underline">
          Go to sign in
        </Link>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-5xl px-6 py-8">
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-xl font-medium text-neutral-900">Agent telemetry</h1>
          <p className="mt-0.5 text-sm text-neutral-500">Global usage across all runs.</p>
        </div>
        <Link
          href="/admin/runs"
          className="rounded-lg border border-neutral-200 px-3 py-1.5 text-sm text-neutral-700 hover:bg-neutral-50"
        >
          Browse runs →
        </Link>
      </div>

      {loading && <div className="py-16 text-center text-sm text-neutral-400">Loading…</div>}
      {error && <div className="rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>}

      {data && !loading && (
        <>
          <section className="mb-6">
            <h2 className="mb-2 text-sm font-medium text-neutral-500">Requests</h2>
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
              <Metric label="Total runs" value={formatNumber(data.requestCount)} />
              <Metric label="Success rate" value={pct(data.requestSuccessRate)} tone="ok" />
              <Metric label="Failed" value={formatNumber(data.failedRequestCount)} tone="bad" />
              <Metric label="Running" value={formatNumber(data.runningRequestCount)} />
              <Metric label="Avg latency" value={formatMs(data.averageRunLatencyMs)} />
              <Metric label="Max latency" value={formatMs(data.maxRunLatencyMs)} />
            </div>
          </section>

          <section className="mb-6">
            <h2 className="mb-2 text-sm font-medium text-neutral-500">LLM &amp; tools</h2>
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
              <Metric label="LLM calls" value={formatNumber(data.llmCallCount)} />
              <Metric label="Failed LLM" value={formatNumber(data.failedLlmCallCount)} tone="bad" />
              <Metric label="Tool calls" value={formatNumber(data.toolCallCount)} />
              <Metric label="Failed tools" value={formatNumber(data.failedToolCallCount)} tone="bad" />
              <Metric label="Prompt tokens" value={formatNumber(data.promptTokens)} />
              <Metric label="Completion tokens" value={formatNumber(data.completionTokens)} />
              <Metric label="Total tokens" value={formatNumber(data.totalTokens)} />
              <Metric label="Unknown-token calls" value={formatNumber(data.unknownTokenLlmCallCount)} />
            </div>
          </section>

          {data.groups && data.groups.length > 0 && (
            <section>
              <h2 className="mb-2 text-sm font-medium text-neutral-500">By provider / model</h2>
              <div className="overflow-x-auto rounded-xl border border-neutral-200">
                <table className="w-full text-sm">
                  <thead className="bg-neutral-50 text-left text-xs text-neutral-500">
                    <tr>
                      <th className="px-3 py-2 font-medium">Provider</th>
                      <th className="px-3 py-2 font-medium">Model</th>
                      <th className="px-3 py-2 font-medium">Source</th>
                      <th className="px-3 py-2 text-right font-medium">Calls</th>
                      <th className="px-3 py-2 text-right font-medium">Failed</th>
                      <th className="px-3 py-2 text-right font-medium">Tokens</th>
                      <th className="px-3 py-2 text-right font-medium">Avg latency</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.groups.map((g, i) => (
                      <tr key={i} className="border-t border-neutral-100">
                        <td className="px-3 py-2">{g.provider || '—'}</td>
                        <td className="px-3 py-2">{g.model || '—'}</td>
                        <td className="px-3 py-2 text-neutral-500">{g.credentialSource || '—'}</td>
                        <td className="px-3 py-2 text-right">{formatNumber(g.llmCallCount)}</td>
                        <td className="px-3 py-2 text-right text-red-600">{formatNumber(g.failedCallCount)}</td>
                        <td className="px-3 py-2 text-right">{formatNumber(g.totalTokens)}</td>
                        <td className="px-3 py-2 text-right">{formatMs(g.averageLatencyMs)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          )}
        </>
      )}
    </div>
  );
}
