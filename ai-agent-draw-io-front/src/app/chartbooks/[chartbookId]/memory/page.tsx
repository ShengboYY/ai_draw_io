'use client';

import Link from 'next/link';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useParams } from 'next/navigation';
import { createMemoryClient, type ConfirmedMemory, type MemoryCandidate, MemoryApiError } from '@/api/memory';
import { API_CONFIG } from '@/config/api-config';

const formatExpiry = (value: string) => {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? 'expires soon' : `expires ${date.toLocaleString()}`;
};

/** Small, explicit review surface: candidates are never confirmed implicitly by the page. */
export default function ChartbookMemoryPage() {
  const params = useParams<{ chartbookId: string }>();
  const chartbookId = params.chartbookId;
  const client = useMemo(() => createMemoryClient({ baseUrl: API_CONFIG.BASE_URL }), []);
  const [candidates, setCandidates] = useState<MemoryCandidate[]>([]);
  const [memories, setMemories] = useState<ConfirmedMemory[]>([]);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [message, setMessage] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [pendingId, setPendingId] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [nextCandidates, nextMemories] = await Promise.all([
        client.pending(chartbookId),
        client.list(chartbookId),
      ]);
      setCandidates(nextCandidates);
      setMemories(nextMemories);
      setDrafts(Object.fromEntries(nextMemories.map(memory => [memory.memoryId, memory.canonicalText])));
      setMessage(null);
    } catch (error) {
      setMessage(error instanceof MemoryApiError ? error.message : 'Memory could not be loaded.');
    } finally {
      setLoading(false);
    }
  }, [chartbookId, client]);

  useEffect(() => {
    const timeoutId = window.setTimeout(() => { void load(); }, 0);
    return () => window.clearTimeout(timeoutId);
  }, [load]);

  const run = async (id: string, action: () => Promise<void>) => {
    setPendingId(id);
    try {
      await action();
      await load();
    } catch (error) {
      setMessage(error instanceof MemoryApiError ? error.message : 'Memory action failed.');
    } finally {
      setPendingId(null);
    }
  };

  const confirm = (candidate: MemoryCandidate) => run(candidate.candidateId, async () => {
    await client.confirm(chartbookId, candidate);
  });

  const revoke = (candidate: MemoryCandidate) => run(candidate.candidateId, async () => {
    await client.revoke(chartbookId, candidate);
  });

  const save = (memory: ConfirmedMemory) => run(memory.memoryId, async () => {
    const text = drafts[memory.memoryId]?.trim() || '';
    if (!text) throw new Error('Memory text cannot be empty.');
    const updated = await client.edit(chartbookId, memory, text);
    setMemories(current => current.map(item => item.memoryId === updated.memoryId ? updated : item));
  });

  const disable = (memory: ConfirmedMemory) => run(memory.memoryId, async () => {
    await client.disable(chartbookId, memory);
  });

  const remove = (memory: ConfirmedMemory) => run(memory.memoryId, async () => {
    await client.remove(chartbookId, memory);
  });

  return (
    <main className="min-h-screen bg-stone-50 px-5 py-8 text-zinc-900 sm:px-8">
      <div className="mx-auto max-w-5xl">
        <div className="mb-8 flex flex-wrap items-start justify-between gap-4">
          <div>
            <Link href={`/chartbooks/${encodeURIComponent(chartbookId)}`} className="text-sm text-zinc-500 hover:text-zinc-900">
              ← Back to chartbook
            </Link>
            <h1 className="mt-4 text-3xl font-semibold tracking-tight">Confirmed Memory</h1>
            <p className="mt-2 max-w-2xl text-sm leading-6 text-zinc-600">
              Review proposed decisions before they become reusable Chartbook context. You can edit, disable, or delete confirmed items at any time.
            </p>
          </div>
          <span className="rounded-full border border-stone-200 bg-white px-3 py-1 text-xs font-medium text-zinc-500">
            explicit confirmation only
          </span>
        </div>

        {message && <p role="status" className="mb-6 rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800">{message}</p>}

        <section aria-labelledby="pending-memory-heading" className="mb-8">
          <div className="mb-3 flex items-baseline justify-between gap-3">
            <h2 id="pending-memory-heading" className="text-lg font-semibold">Needs your confirmation</h2>
            <span className="text-xs text-zinc-500">{candidates.length} pending</span>
          </div>
          {loading ? (
            <div className="rounded-2xl border border-stone-200 bg-white p-6 text-sm text-zinc-500">Loading candidates...</div>
          ) : candidates.length === 0 ? (
            <div className="rounded-2xl border border-dashed border-stone-300 bg-white p-6 text-sm text-zinc-500">No pending decisions.</div>
          ) : (
            <div className="space-y-3">
              {candidates.map(candidate => (
                <article key={candidate.candidateId} className="rounded-2xl border border-amber-200 bg-amber-50/60 p-5">
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div>
                      <p className="text-xs font-semibold uppercase tracking-wider text-amber-800">{candidate.decisionKey}</p>
                      <p className="mt-2 text-base leading-6 text-zinc-800">{candidate.canonicalText}</p>
                      <p className="mt-2 text-xs text-zinc-500">{candidate.applicabilityStage} · {formatExpiry(candidate.expiresAt)}</p>
                    </div>
                    <div className="flex shrink-0 gap-2">
                      <button type="button" onClick={() => void confirm(candidate)} disabled={pendingId === candidate.candidateId} className="rounded-lg bg-zinc-900 px-3 py-2 text-sm font-medium text-white disabled:opacity-40">
                        Confirm
                      </button>
                      <button type="button" onClick={() => void revoke(candidate)} disabled={pendingId === candidate.candidateId} className="rounded-lg border border-stone-300 bg-white px-3 py-2 text-sm font-medium text-zinc-700 disabled:opacity-40">
                        Dismiss
                      </button>
                    </div>
                  </div>
                </article>
              ))}
            </div>
          )}
        </section>

        <section aria-labelledby="confirmed-memory-heading">
          <div className="mb-3 flex items-baseline justify-between gap-3">
            <h2 id="confirmed-memory-heading" className="text-lg font-semibold">Your confirmed decisions</h2>
            <span className="text-xs text-zinc-500">{memories.length} saved</span>
          </div>
          {memories.length === 0 ? (
            <div className="rounded-2xl border border-dashed border-stone-300 bg-white p-6 text-sm text-zinc-500">Confirmed decisions will appear here.</div>
          ) : (
            <div className="grid gap-4 lg:grid-cols-2">
              {memories.map(memory => (
                <article key={memory.memoryId} className="rounded-2xl border border-stone-200 bg-white p-5 shadow-sm">
                  <div className="flex items-start justify-between gap-3">
                    <div>
                      <p className="text-xs font-semibold uppercase tracking-wider text-zinc-500">{memory.decisionKey}</p>
                      <span className={`mt-2 inline-flex rounded-full px-2 py-1 text-[11px] font-medium ${memory.status === 'ACTIVE' ? 'bg-emerald-50 text-emerald-700' : 'bg-stone-100 text-zinc-500'}`}>
                        {memory.status.toLowerCase()}
                      </span>
                    </div>
                    <span className="text-xs text-zinc-400">v{memory.version}</span>
                  </div>
                  <textarea
                    value={drafts[memory.memoryId] || ''}
                    onChange={event => setDrafts(current => ({ ...current, [memory.memoryId]: event.target.value }))}
                    rows={3}
                    className="mt-4 w-full rounded-xl border border-stone-200 bg-stone-50 px-3 py-2 text-sm leading-6 outline-none focus:border-zinc-500"
                    aria-label={`Edit ${memory.decisionKey}`}
                  />
                  <div className="mt-3 flex flex-wrap justify-end gap-2">
                    <button type="button" onClick={() => void save(memory)} disabled={pendingId === memory.memoryId || memory.status !== 'ACTIVE'} className="rounded-lg bg-zinc-900 px-3 py-2 text-sm font-medium text-white disabled:opacity-40">Save</button>
                    {memory.status === 'ACTIVE' && <button type="button" onClick={() => void disable(memory)} disabled={pendingId === memory.memoryId} className="rounded-lg border border-stone-300 px-3 py-2 text-sm text-zinc-700 disabled:opacity-40">Disable</button>}
                    <button type="button" onClick={() => void remove(memory)} disabled={pendingId === memory.memoryId} className="rounded-lg border border-rose-200 px-3 py-2 text-sm text-rose-700 disabled:opacity-40">Delete</button>
                  </div>
                </article>
              ))}
            </div>
          )}
        </section>
      </div>
    </main>
  );
}
