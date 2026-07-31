'use client';

import Link from 'next/link';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useParams } from 'next/navigation';
import { createMemoryClient, type AutoMemory, MemoryApiError } from '@/api/memory';
import { API_CONFIG } from '@/config/api-config';

type ScopeSectionProps = {
  id: string;
  title: string;
  description: string;
  memories: AutoMemory[];
  drafts: Record<string, string>;
  pendingId: string | null;
  onDraft: (memoryId: string, value: string) => void;
  onSave: (memory: AutoMemory) => void;
  onDisable: (memory: AutoMemory) => void;
  onActivate: (memory: AutoMemory) => void;
  onRemove: (memory: AutoMemory) => void;
};

const confidenceLabel = (value: number) => `${Math.round(value * 100)}% confidence`;

function ScopeSection({
  id,
  title,
  description,
  memories,
  drafts,
  pendingId,
  onDraft,
  onSave,
  onDisable,
  onActivate,
  onRemove,
}: ScopeSectionProps) {
  return (
    <section aria-labelledby={id} className="mb-9">
      <div className="mb-3 flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 id={id} className="text-lg font-semibold">{title}</h2>
          <p className="mt-1 text-sm text-zinc-500">{description}</p>
        </div>
        <span className="text-xs text-zinc-500">{memories.length} memories</span>
      </div>
      {memories.length === 0 ? (
        <div className="rounded-2xl border border-dashed border-stone-300 bg-white p-6 text-sm text-zinc-500">
          Stable preferences and feedback will appear here automatically.
        </div>
      ) : (
        <div className="grid gap-4 lg:grid-cols-2">
          {memories.map(memory => (
            <article key={memory.memoryId} className="rounded-2xl border border-stone-200 bg-white p-5 shadow-sm">
              <div className="flex items-start justify-between gap-3">
                <div>
                  <p className="text-xs font-semibold uppercase tracking-wider text-zinc-500">
                    {memory.memoryType} · {memory.semanticKey}
                  </p>
                  <h3 className="mt-2 text-sm font-semibold text-zinc-800">{memory.title}</h3>
                  <div className="mt-2 flex flex-wrap gap-2 text-[11px]">
                    <span className={`rounded-full px-2 py-1 font-medium ${
                      memory.status === 'ACTIVE'
                        ? 'bg-emerald-50 text-emerald-700'
                        : memory.status === 'OBSERVED'
                          ? 'bg-amber-50 text-amber-700'
                          : 'bg-stone-100 text-zinc-500'
                    }`}>
                      {memory.status.toLowerCase()}
                    </span>
                    <span className="rounded-full bg-stone-100 px-2 py-1 text-zinc-500">
                      {memory.explicit ? 'explicit' : confidenceLabel(memory.confidence)}
                    </span>
                    <span className="rounded-full bg-stone-100 px-2 py-1 text-zinc-500">
                      {memory.evidenceCount} {memory.evidenceCount === 1 ? 'signal' : 'signals'}
                    </span>
                  </div>
                </div>
                <span className="text-xs text-zinc-400">v{memory.version}</span>
              </div>
              <textarea
                value={drafts[memory.memoryId] || ''}
                onChange={event => onDraft(memory.memoryId, event.target.value)}
                rows={3}
                disabled={memory.status === 'DISABLED'}
                className="mt-4 w-full rounded-xl border border-stone-200 bg-stone-50 px-3 py-2 text-sm leading-6 outline-none focus:border-zinc-500 disabled:opacity-60"
                aria-label={`Edit ${memory.title}`}
              />
              <div className="mt-3 flex flex-wrap justify-end gap-2">
                {memory.status !== 'DISABLED' && (
                  <>
                    <button type="button" onClick={() => onSave(memory)} disabled={pendingId === memory.memoryId} className="rounded-lg bg-zinc-900 px-3 py-2 text-sm font-medium text-white disabled:opacity-40">
                      Save &amp; activate
                    </button>
                    <button type="button" onClick={() => onDisable(memory)} disabled={pendingId === memory.memoryId} className="rounded-lg border border-stone-300 px-3 py-2 text-sm text-zinc-700 disabled:opacity-40">
                      Disable
                    </button>
                  </>
                )}
                {memory.status === 'DISABLED' && (
                  <button type="button" onClick={() => onActivate(memory)} disabled={pendingId === memory.memoryId} className="rounded-lg bg-zinc-900 px-3 py-2 text-sm font-medium text-white disabled:opacity-40">
                    Re-enable
                  </button>
                )}
                <button type="button" onClick={() => onRemove(memory)} disabled={pendingId === memory.memoryId} className="rounded-lg border border-rose-200 px-3 py-2 text-sm text-rose-700 disabled:opacity-40">
                  Delete
                </button>
              </div>
            </article>
          ))}
        </div>
      )}
    </section>
  );
}

export default function ChartbookMemoryPage() {
  const params = useParams<{ chartbookId: string }>();
  const chartbookId = params.chartbookId;
  const client = useMemo(() => createMemoryClient({ baseUrl: API_CONFIG.BASE_URL }), []);
  const [memories, setMemories] = useState<AutoMemory[]>([]);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [message, setMessage] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [pendingId, setPendingId] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [userMemories, chartbookMemories] = await Promise.all([
        client.listUser(),
        client.listChartbook(chartbookId),
      ]);
      const nextMemories = [...chartbookMemories, ...userMemories];
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

  const run = async (memory: AutoMemory, action: () => Promise<void>) => {
    setPendingId(memory.memoryId);
    try {
      await action();
      await load();
    } catch (error) {
      setMessage(error instanceof MemoryApiError ? error.message : 'Memory action failed.');
    } finally {
      setPendingId(null);
    }
  };

  const scopeChartbookId = (memory: AutoMemory) =>
    memory.scopeType === 'CHARTBOOK' ? chartbookId : undefined;
  const save = (memory: AutoMemory) => run(memory, async () => {
    const text = drafts[memory.memoryId]?.trim() || '';
    if (!text) throw new Error('Memory text cannot be empty.');
    await client.edit(memory, text, scopeChartbookId(memory));
  });
  const disable = (memory: AutoMemory) => run(memory, async () => {
    await client.disable(memory, scopeChartbookId(memory));
  });
  const activate = (memory: AutoMemory) => run(memory, async () => {
    await client.activate(memory, scopeChartbookId(memory));
  });
  const remove = (memory: AutoMemory) => run(memory, async () => {
    await client.remove(memory, scopeChartbookId(memory));
  });

  const chartbookMemories = memories.filter(memory => memory.scopeType === 'CHARTBOOK');
  const userMemories = memories.filter(memory => memory.scopeType === 'USER');
  const sectionProps = {
    drafts,
    pendingId,
    onDraft: (memoryId: string, value: string) =>
      setDrafts(current => ({ ...current, [memoryId]: value })),
    onSave: (memory: AutoMemory) => { void save(memory); },
    onDisable: (memory: AutoMemory) => { void disable(memory); },
    onActivate: (memory: AutoMemory) => { void activate(memory); },
    onRemove: (memory: AutoMemory) => { void remove(memory); },
  };

  return (
    <main className="min-h-screen bg-stone-50 px-5 py-8 text-zinc-900 sm:px-8">
      <div className="mx-auto max-w-5xl">
        <div className="mb-8 flex flex-wrap items-start justify-between gap-4">
          <div>
            <Link href={`/chartbooks/${encodeURIComponent(chartbookId)}`} className="text-sm text-zinc-500 hover:text-zinc-900">
              ← Back to chartbook
            </Link>
            <h1 className="mt-4 text-3xl font-semibold tracking-tight">Auto Memory</h1>
            <p className="mt-2 max-w-2xl text-sm leading-6 text-zinc-600">
              Stable preferences and feedback are summarized after completed conversations. Inferred items stay observed until repeated; you can edit, disable, re-enable, or delete them at any time.
            </p>
          </div>
          <span className="rounded-full border border-stone-200 bg-white px-3 py-1 text-xs font-medium text-zinc-500">
            automatic · reversible
          </span>
        </div>

        {message && <p role="status" className="mb-6 rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800">{message}</p>}
        {loading ? (
          <div className="rounded-2xl border border-stone-200 bg-white p-6 text-sm text-zinc-500">Loading memories...</div>
        ) : (
          <>
            <ScopeSection
              id="chartbook-memory-heading"
              title="This Chartbook"
              description="Project-specific conventions used only inside this Chartbook."
              memories={chartbookMemories}
              {...sectionProps}
            />
            <ScopeSection
              id="user-memory-heading"
              title="Across all Chartbooks"
              description="Your stable global preferences, used when this Chartbook does not override them."
              memories={userMemories}
              {...sectionProps}
            />
          </>
        )}
      </div>
    </main>
  );
}
