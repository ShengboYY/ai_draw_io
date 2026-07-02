'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { agentApi } from '@/api/agent';
import { getUserInfo } from '@/utils/cookie';
import { workspaceLabelFromAccount } from '@/utils/current-account';
import { getWorkspaceIdentity } from '@/utils/workspace-identity';
import { CurrentAccountResponseDTO, DiagramSummaryResponseDTO } from '@/types/api';

const formatUpdatedAt = (value?: string) => {
  if (!value) return 'No updates yet';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return 'No updates yet';
  return date.toLocaleString();
};

const usageNumber = (value?: number) => Number.isFinite(value) ? Math.max(0, value || 0) : 0;

export default function Home() {
  const router = useRouter();
  const [ownerId] = useState(() => getWorkspaceIdentity(getUserInfo()?.user).ownerId);
  const [currentAccount, setCurrentAccount] = useState<CurrentAccountResponseDTO | null>(null);
  const [diagrams, setDiagrams] = useState<DiagramSummaryResponseDTO[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [errorMessage, setErrorMessage] = useState('');

  useEffect(() => {
    let cancelled = false;
    agentApi.currentAccount(ownerId)
      .then(res => {
        if (!cancelled) setCurrentAccount(res.data || null);
      })
      .catch(() => {
        if (!cancelled) setCurrentAccount(null);
      });

    agentApi.listDiagrams(ownerId)
      .then(res => {
        if (!cancelled) setDiagrams(res.data || []);
      })
      .catch(() => {
        if (!cancelled) setErrorMessage('Failed to load diagrams.');
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [ownerId]);

  const openDiagram = (diagramId: string) => {
    router.push(`/drawio?diagramId=${encodeURIComponent(diagramId)}`);
  };

  const startNewDiagram = () => {
    router.push('/drawio');
  };

  const renameDiagram = async (diagram: DiagramSummaryResponseDTO) => {
    const nextTitle = window.prompt('Rename diagram', diagram.title || 'Untitled Diagram');
    if (nextTitle === null) return;

    const title = nextTitle.trim();
    if (!title) return;

    setErrorMessage('');
    try {
      const res = await agentApi.renameDiagram(ownerId, diagram.diagramId, title);
      const updated = res.data;
      setDiagrams(prev => prev.map(item => (
        item.diagramId === diagram.diagramId
          ? {
              ...item,
              title: updated?.title || title,
              diagramType: updated?.diagramType || item.diagramType,
              version: updated?.version || item.version,
              updatedAt: updated?.updatedAt || item.updatedAt,
            }
          : item
      )));
    } catch {
      setErrorMessage('Failed to rename diagram.');
    }
  };

  const deleteDiagram = async (diagram: DiagramSummaryResponseDTO) => {
    if (!window.confirm(`Delete "${diagram.title || 'Untitled Diagram'}"?`)) return;

    setErrorMessage('');
    try {
      const res = await agentApi.deleteDiagram(ownerId, diagram.diagramId);
      if (!res.data) {
        setErrorMessage('Diagram was not deleted.');
        return;
      }
      setDiagrams(prev => prev.filter(item => item.diagramId !== diagram.diagramId));
    } catch {
      setErrorMessage('Failed to delete diagram.');
    }
  };

  return (
    <main className="min-h-screen bg-slate-50 text-slate-900">
      <div className="mx-auto flex min-h-screen w-full max-w-6xl flex-col px-6 py-8">
        <header className="flex flex-wrap items-center justify-between gap-4 border-b border-slate-200 pb-5">
          <div>
            <h1 className="text-2xl font-semibold tracking-normal">My diagrams</h1>
            <p className="mt-1 text-sm text-slate-500">{workspaceLabelFromAccount(currentAccount, ownerId)}</p>
          </div>
          <button
            type="button"
            onClick={startNewDiagram}
            className="h-10 rounded-md bg-slate-900 px-4 text-sm font-medium text-white transition hover:bg-slate-700"
          >
            New diagram
          </button>
        </header>

        {currentAccount?.ownerType === 'USER' && (
          <section className="mt-5 grid gap-3 md:grid-cols-3">
            <div className="rounded-md border border-slate-200 bg-white p-4">
              <div className="text-xs font-medium uppercase tracking-normal text-slate-400">Platform quota</div>
              <div className="mt-2 text-xl font-semibold text-slate-900">
                {usageNumber(currentAccount.platformDailyQuotaRemaining)} / {usageNumber(currentAccount.platformDailyQuotaLimit)}
              </div>
              <div className="mt-1 text-xs text-slate-500">remaining today</div>
            </div>
            <div className="rounded-md border border-slate-200 bg-white p-4">
              <div className="text-xs font-medium uppercase tracking-normal text-slate-400">Platform runs</div>
              <div className="mt-2 text-xl font-semibold text-slate-900">{usageNumber(currentAccount.platformRunCount)}</div>
              <div className="mt-1 text-xs text-slate-500">requests using the shared key</div>
            </div>
            <div className="rounded-md border border-slate-200 bg-white p-4">
              <div className="text-xs font-medium uppercase tracking-normal text-slate-400">Own-key runs</div>
              <div className="mt-2 text-xl font-semibold text-slate-900">{usageNumber(currentAccount.userKeyRunCount)}</div>
              <div className="mt-1 text-xs text-slate-500">
                {usageNumber(currentAccount.knownTotalTokens)} known tokens
                {usageNumber(currentAccount.unknownTokenLlmCallCount) > 0
                  ? `, ${usageNumber(currentAccount.unknownTokenLlmCallCount)} calls unknown`
                  : ''}
              </div>
            </div>
          </section>
        )}

        {errorMessage && (
          <div className="mt-6 rounded-md border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">
            {errorMessage}
          </div>
        )}

        <section className="mt-6 flex-1">
          {isLoading ? (
            <div className="rounded-md border border-slate-200 bg-white px-4 py-5 text-sm text-slate-500">Loading diagrams...</div>
          ) : diagrams.length === 0 ? (
            <div className="rounded-md border border-dashed border-slate-300 bg-white px-5 py-8">
              <h2 className="text-base font-medium tracking-normal text-slate-800">No diagrams yet</h2>
              <button
                type="button"
                onClick={startNewDiagram}
                className="mt-4 h-9 rounded-md border border-slate-300 px-3 text-sm font-medium text-slate-700 transition hover:border-slate-400 hover:bg-slate-50"
              >
                New diagram
              </button>
            </div>
          ) : (
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {diagrams.map(diagram => (
                <article
                  key={diagram.diagramId}
                  className="min-h-32 rounded-md border border-slate-200 bg-white p-4 transition hover:border-slate-300 hover:shadow-sm"
                >
                  <div className="flex items-start justify-between gap-3">
                    <h2 className="line-clamp-2 text-base font-medium tracking-normal text-slate-900">
                      {diagram.title || 'Untitled Diagram'}
                    </h2>
                    <span className="shrink-0 rounded bg-slate-100 px-2 py-1 text-xs text-slate-500">v{diagram.version || 1}</span>
                  </div>
                  <div className="mt-3 text-xs uppercase tracking-normal text-slate-400">{diagram.diagramType || 'basic'}</div>
                  <div className="mt-4 flex flex-wrap items-center justify-between gap-2">
                    <span className="text-xs text-slate-500">{formatUpdatedAt(diagram.updatedAt)}</span>
                    <div className="flex items-center gap-2">
                      <button
                        type="button"
                        onClick={() => openDiagram(diagram.diagramId)}
                        className="h-8 rounded-md border border-slate-300 px-3 text-xs font-medium text-slate-700 transition hover:bg-slate-50"
                      >
                        Open
                      </button>
                      <button
                        type="button"
                        onClick={() => renameDiagram(diagram)}
                        className="h-8 rounded-md border border-slate-300 px-3 text-xs font-medium text-slate-700 transition hover:bg-slate-50"
                      >
                        Rename
                      </button>
                      <button
                        type="button"
                        onClick={() => deleteDiagram(diagram)}
                        className="h-8 rounded-md border border-rose-200 px-3 text-xs font-medium text-rose-600 transition hover:bg-rose-50"
                      >
                        Delete
                      </button>
                    </div>
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
