'use client';

import { useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useParams, usePathname, useRouter } from 'next/navigation';
import { agentApi, ApiResponseError } from '@/api/agent';
import type {
  AdminDebugTraceCaptureDTO,
  AdminLlmCallDTO,
  AdminRunDetailDTO,
  AdminRunTimelineEventDTO,
  AdminToolCallDTO,
  DiagramCanvasStateResponseDTO,
} from '@/types/api';
import { buildLoginHref } from '@/utils/login-form';
import {
  barColor,
  buildWaterfall,
  diagramPreviewHasImage,
  diagramPreviewMeta,
  diagramPreviewTitle,
  estCostUsd,
  formatCost,
  formatMs,
  formatNumber,
  formatTime,
  isFailed,
  sourceLabel,
  statusPill,
  waterfallRowView,
} from '../../admin-shared';

function Stat({ label, value, bad }: { label: string; value: string; bad?: boolean }) {
  return (
    <div className="rounded-lg bg-neutral-50 px-3 py-2">
      <div className="text-xs text-neutral-500">{label}</div>
      <div className={`mt-0.5 text-lg font-medium ${bad ? 'text-red-600' : 'text-neutral-900'}`}>
        {value}
      </div>
    </div>
  );
}

export default function AdminRunDetailPage() {
  const params = useParams<{ runId: string }>();
  const pathname = usePathname();
  const router = useRouter();
  const returnTo = pathname;
  const runId = decodeURIComponent(params.runId);

  const [detail, setDetail] = useState<AdminRunDetailDTO | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [forbidden, setForbidden] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [diagramResult, setDiagramResult] = useState<{
    runId: string;
    diagram: DiagramCanvasStateResponseDTO | null;
    note: string | null;
  } | null>(null);

  const [captures, setCaptures] = useState<AdminDebugTraceCaptureDTO[] | null>(null);
  const [capturesLoading, setCapturesLoading] = useState(false);
  const [captureNote, setCaptureNote] = useState<string | null>(null);

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
      .adminRunDetail(runId)
      .then((res) => alive && setDetail(res.data))
      .catch((e) => {
        if (!alive) return;
        if (e instanceof ApiResponseError && e.code === 'AUTH_FORBIDDEN') handleForbidden();
        else setError(e instanceof Error ? e.message : 'Failed to load run');
      })
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
  }, [returnTo, router, runId]);

  useEffect(() => {
    const diagramId = detail?.run?.diagramId;
    if (!diagramId) {
      return;
    }
    let alive = true;
    agentApi
      .adminRunDiagram(runId)
      .then((res) => {
        if (!alive) return;
        setDiagramResult({
          runId,
          diagram: res.data || null,
          note: res.data ? null : 'Diagram snapshot not found.',
        });
      })
      .catch((e) => {
        if (alive) {
          setDiagramResult({
            runId,
            diagram: null,
            note: e instanceof Error ? e.message : 'Failed to load diagram',
          });
        }
      })
    return () => {
      alive = false;
    };
  }, [detail?.run?.diagramId, runId]);

  const rows = useMemo(() => buildWaterfall(detail?.timeline || []), [detail]);

  const llmById = useMemo(() => {
    const m = new Map<string, AdminLlmCallDTO>();
    (detail?.llmCalls || []).forEach((c) => m.set(c.id, c));
    return m;
  }, [detail]);
  const toolById = useMemo(() => {
    const m = new Map<string, AdminToolCallDTO>();
    (detail?.toolCalls || []).forEach((c) => m.set(c.id, c));
    return m;
  }, [detail]);

  const selected: AdminRunTimelineEventDTO | undefined = useMemo(
    () => (detail?.timeline || []).find((e) => e.id === selectedId),
    [detail, selectedId],
  );

  const totalTokens = useMemo(
    () => (detail?.llmCalls || []).reduce((sum, c) => sum + (c.totalTokens || 0), 0),
    [detail],
  );

  const totalCost = useMemo(
    () =>
      (detail?.llmCalls || []).reduce(
        (sum, c) => sum + estCostUsd(c.promptTokens, c.completionTokens, c.model),
        0,
      ),
    [detail],
  );

  const loadCaptures = () => {
    setCapturesLoading(true);
    setCaptureNote(null);
    agentApi
      .adminRunCaptures(runId)
      .then((res) => {
        const items = res.data || [];
        setCaptures(items);
        if (items.length === 0) {
          setCaptureNote(
            'No payloads captured for this run. Content capture is opt-in per user/run and is not retained by default — enable it, then re-run to record prompts and responses.',
          );
        }
      })
      .catch((e) => setCaptureNote(e instanceof Error ? e.message : 'Failed to load payloads'))
      .finally(() => setCapturesLoading(false));
  };

  const enableCapture = () => {
    const uid = detail?.run?.userId;
    if (!uid) return;
    setCaptureNote('Enabling…');
    agentApi
      .adminEnableCapture({ userId: uid })
      .then(() =>
        setCaptureNote(
          'Capture enabled for this user. New runs from now on will record payloads; this past run is unaffected.',
        ),
      )
      .catch((e) => setCaptureNote(e instanceof Error ? e.message : 'Failed to enable capture'));
  };

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

  const run = detail?.run;
  const currentDiagramResult = diagramResult?.runId === runId ? diagramResult : null;
  const currentDiagram = currentDiagramResult?.diagram || null;
  const currentDiagramNote = currentDiagramResult?.note || null;
  const currentDiagramLoading = Boolean(run?.diagramId && !currentDiagramResult);

  return (
    <div className="mx-auto max-w-6xl px-6 py-8">
      <div className="mb-4 flex items-center justify-between">
        <Link href="/admin/runs" className="text-sm text-neutral-500 hover:text-neutral-800">
          ← Runs
        </Link>
      </div>

      {loading && <div className="py-16 text-center text-sm text-neutral-400">Loading…</div>}
      {error && <div className="rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>}

      {run && !loading && (
        <>
          <div className="mb-3 flex flex-wrap items-center gap-2">
            <span className="font-mono text-sm text-neutral-500">{run.id}</span>
            <span className={`rounded-md border px-2 py-0.5 text-xs ${statusPill(run.status)}`}>
              {(run.status || '—').toLowerCase()}
            </span>
            {run.agentId && (
              <span className="rounded-md border border-blue-200 bg-blue-50 px-2 py-0.5 text-xs text-blue-700">
                {run.agentId}
              </span>
            )}
            {run.requestType && (
              <span className="text-xs text-neutral-400">{run.requestType}</span>
            )}
            {run.diagramId && (
              <span className="rounded-md border border-neutral-200 bg-neutral-50 px-2 py-0.5 font-mono text-xs text-neutral-500">
                {run.diagramId}
              </span>
            )}
          </div>

          <div className="mb-6 grid grid-cols-2 gap-3 sm:grid-cols-6">
            <Stat label="Latency" value={formatMs(run.latencyMs)} />
            <Stat label="LLM calls" value={formatNumber(detail?.llmCalls?.length)} />
            <Stat label="Tool calls" value={formatNumber(detail?.toolCalls?.length)} />
            <Stat label="Tokens" value={formatNumber(totalTokens)} />
            <Stat label="Est. cost" value={formatCost(totalCost)} />
            <Stat label="Error" value={run.errorClass || '—'} bad={isFailed(run.status)} />
          </div>

          <div className="grid grid-cols-1 gap-4 xl:grid-cols-[1.45fr_1fr]">
            {/* Waterfall */}
            <div className="rounded-xl border border-neutral-200 p-3">
              <div className="mb-2 flex items-center justify-between text-xs text-neutral-400">
                <span>timeline (waterfall)</span>
                <span>{formatMs(run.latencyMs)} total</span>
              </div>
              {rows.length === 0 ? (
                <div className="py-8 text-center text-sm text-neutral-400">No spans recorded.</div>
              ) : (
                <div className="flex flex-col">
                  {rows.map(({ event, leftPct, widthPct, isPoint }, index) => {
                    const failed = isFailed(event.status);
                    const isSel = event.id === selectedId;
                    const rowView = waterfallRowView(rows, index);
                    return (
                      <button
                        key={event.id}
                        onClick={() => setSelectedId(event.id)}
                        className={`flex items-center gap-2 rounded px-1 text-left text-xs ${
                          isSel ? 'bg-neutral-100' : 'hover:bg-neutral-50'
                        } ${rowView.compact ? 'py-0' : 'py-0.5'} ${
                          rowView.startsStepGroup ? 'mt-2' : index === 0 ? '' : rowView.compact ? 'mt-0.5' : 'mt-1'
                        }`}
                      >
                        <span
                          className={`w-40 shrink-0 truncate ${
                            failed ? 'text-red-600' : 'text-neutral-600'
                          }`}
                          style={{ paddingLeft: `${rowView.visualDepth * 14}px` }}
                          title={`${sourceLabel(event.source)} · ${event.detail || event.eventType || ''}`}
                        >
                          <span className="text-neutral-400">{sourceLabel(event.source)} </span>
                          {event.detail || event.eventType || '—'}
                        </span>
                        <span className="relative h-4 flex-1 rounded bg-neutral-100">
                          <span
                            className="absolute top-0 bottom-0 rounded"
                            style={{
                              left: `${leftPct}%`,
                              width: `${widthPct}%`,
                              minWidth: isPoint ? '3px' : '2px',
                              background: barColor(event.source, event.status),
                            }}
                          />
                        </span>
                        <span className="w-12 shrink-0 text-right text-neutral-400">
                          {event.latencyMs != null ? formatMs(event.latencyMs) : ''}
                        </span>
                      </button>
                    );
                  })}
                </div>
              )}
            </div>

            <div className="space-y-4">
              <DiagramSnapshotPanel
                linkedDiagramId={run.diagramId}
                diagram={currentDiagram}
                loading={currentDiagramLoading}
                note={currentDiagramNote}
              />

              {/* Span detail panel */}
              <div className="rounded-xl border border-neutral-200 p-4">
                {!selected ? (
                  <div className="py-8 text-center text-sm text-neutral-400">
                    Select a span to inspect it.
                  </div>
                ) : (
                  <div className="text-sm">
                    <div className="text-xs text-neutral-400">{sourceLabel(selected.source)}</div>
                    <div className="mt-0.5 text-base font-medium text-neutral-900">
                      {selected.detail || selected.eventType || '—'}
                    </div>
                    <div
                      className={`mt-0.5 font-mono text-xs ${
                        isFailed(selected.status) ? 'text-red-600' : 'text-neutral-500'
                      }`}
                    >
                      {(selected.status || '').toLowerCase()}
                      {selected.latencyMs != null ? ` · ${formatMs(selected.latencyMs)}` : ''}
                    </div>

                    <dl className="mt-4 space-y-2">
                      <Row k="Phase" v={selected.phase} />
                      <Row k="Started" v={formatTime(selected.occurredAt)} />
                      {(() => {
                        const llm = llmById.get(selected.id);
                        const tool = toolById.get(selected.id);
                        return (
                          <>
                            {llm && <Row k="Provider / model" v={`${llm.provider || '—'} / ${llm.model || '—'}`} />}
                            {llm && (
                              <Row
                                k="Tokens (p/c/t)"
                                v={`${formatNumber(llm.promptTokens)} / ${formatNumber(
                                  llm.completionTokens,
                                )} / ${formatNumber(llm.totalTokens)}`}
                              />
                            )}
                            {(llm?.errorClass || tool?.errorClass) && (
                              <Row k="Error" v={llm?.errorClass || tool?.errorClass} bad />
                            )}
                          </>
                        );
                      })()}
                    </dl>

                    {selected.metadataJson && (
                      <div className="mt-4">
                        <div className="mb-1 text-xs text-neutral-400">metadata</div>
                        <pre className="max-h-48 overflow-auto rounded-lg bg-neutral-50 p-2 text-xs text-neutral-700">
                          {prettyJson(selected.metadataJson)}
                        </pre>
                      </div>
                    )}
                  </div>
                )}
              </div>
            </div>
          </div>

          {/* Captured payloads (opt-in, retention-gated) */}
          <div className="mt-4 rounded-xl border border-neutral-200 p-4">
            <div className="mb-2 flex items-center justify-between">
              <h2 className="text-sm font-medium text-neutral-700">Captured payloads</h2>
              {captures === null && (
                <button
                  onClick={loadCaptures}
                  disabled={capturesLoading}
                  className="rounded-lg border border-neutral-200 px-3 py-1 text-sm text-neutral-600 hover:bg-neutral-50 disabled:opacity-50"
                >
                  {capturesLoading ? 'Loading…' : 'Load payloads'}
                </button>
              )}
            </div>

            {captures && captures.length > 0 && (
              <div className="space-y-3">
                {captures.map((c) => {
                  const deleted = Boolean(c.contentDeletedAt);
                  return (
                    <div key={c.id} className="rounded-lg border border-neutral-100">
                      <div className="flex items-center justify-between border-b border-neutral-100 px-3 py-1.5 text-xs">
                        <span className="font-medium text-neutral-600">{c.eventType || 'payload'}</span>
                        <span className="text-neutral-400">{formatTime(c.createdAt)}</span>
                      </div>
                      {deleted ? (
                        <div className="px-3 py-3 text-xs text-neutral-400">
                          Content expired and was cleaned up{c.contentDeletedAt ? ` (${formatTime(c.contentDeletedAt)})` : ''}.
                        </div>
                      ) : (
                        <pre className="max-h-64 overflow-auto whitespace-pre-wrap break-words px-3 py-2 text-xs text-neutral-700">
                          {c.content || '(empty)'}
                        </pre>
                      )}
                    </div>
                  );
                })}
              </div>
            )}

            {captureNote && (
              <div className="rounded-lg bg-neutral-50 px-3 py-3 text-xs text-neutral-500">
                <p>{captureNote}</p>
                {captures && captures.length === 0 && detail?.run?.userId && (
                  <button
                    onClick={enableCapture}
                    className="mt-2 rounded-lg border border-neutral-200 px-3 py-1 text-neutral-600 hover:bg-white"
                  >
                    Enable capture for this user
                  </button>
                )}
              </div>
            )}
          </div>
        </>
      )}
    </div>
  );
}

function Row({ k, v, bad }: { k: string; v?: string; bad?: boolean }) {
  if (!v) return null;
  return (
    <div className="flex justify-between gap-3">
      <dt className="text-neutral-400">{k}</dt>
      <dd className={`text-right ${bad ? 'text-red-600' : 'text-neutral-700'}`}>{v}</dd>
    </div>
  );
}

function DiagramSnapshotPanel({
  linkedDiagramId,
  diagram,
  loading,
  note,
}: {
  linkedDiagramId?: string;
  diagram: DiagramCanvasStateResponseDTO | null;
  loading: boolean;
  note: string | null;
}) {
  const previewText = diagram?.currentXml || diagram?.summary || '';
  const [zoomOpen, setZoomOpen] = useState(false);
  const thumbnailUrl = diagram?.thumbnailUrl?.trim() || '';
  const canZoom = diagramPreviewHasImage(diagram);
  const title = diagram ? diagramPreviewTitle(diagram) : linkedDiagramId || 'No linked diagram';
  const meta = diagram ? diagramPreviewMeta(diagram) : linkedDiagramId || '—';

  return (
    <div className="rounded-xl border border-neutral-200 p-4">
      <div className="mb-3 flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="text-xs text-neutral-400">diagram</div>
          <div className="mt-0.5 truncate text-base font-medium text-neutral-900">{title}</div>
          <div className="mt-0.5 font-mono text-xs text-neutral-400">{meta}</div>
        </div>
      </div>

      {loading && <div className="py-8 text-center text-sm text-neutral-400">Loading diagram…</div>}

      {!loading && thumbnailUrl && (
        <button
          type="button"
          onClick={() => setZoomOpen(true)}
          className="group relative block w-full overflow-hidden rounded-lg border border-neutral-100 bg-neutral-50 text-left"
          aria-label={`View larger diagram: ${title}`}
        >
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={thumbnailUrl}
            alt={title}
            className="max-h-64 w-full object-contain"
          />
          {canZoom && (
            <span className="pointer-events-none absolute right-2 top-2 rounded-md bg-white/90 px-2 py-1 text-[11px] font-medium text-neutral-600 opacity-0 shadow-sm transition-opacity group-hover:opacity-100">
              View larger
            </span>
          )}
        </button>
      )}

      {!loading && diagram && !diagram.thumbnailUrl && (
        <pre className="max-h-56 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-neutral-50 p-2 text-xs text-neutral-600">
          {previewText || 'No canvas XML.'}
        </pre>
      )}

      {!loading && note && (
        <div className="rounded-lg bg-neutral-50 px-3 py-3 text-xs text-neutral-500">{note}</div>
      )}

      {canZoom && zoomOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-4"
          role="dialog"
          aria-modal="true"
          aria-label={`Large diagram preview: ${title}`}
          onClick={() => setZoomOpen(false)}
        >
          <div
            className="flex max-h-[92vh] w-full max-w-6xl flex-col overflow-hidden rounded-lg bg-white shadow-2xl"
            onClick={(event) => event.stopPropagation()}
          >
            <div className="flex items-start justify-between gap-3 border-b border-neutral-200 px-4 py-3">
              <div className="min-w-0">
                <div className="truncate text-sm font-medium text-neutral-900">{title}</div>
                <div className="mt-0.5 font-mono text-xs text-neutral-400">{meta}</div>
              </div>
              <button
                type="button"
                onClick={() => setZoomOpen(false)}
                className="shrink-0 rounded-md border border-neutral-200 px-2 py-1 text-xs text-neutral-600 hover:bg-neutral-50"
              >
                Close
              </button>
            </div>
            <div className="flex min-h-0 flex-1 items-center justify-center overflow-auto bg-neutral-50 p-4">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src={thumbnailUrl}
                alt={title}
                className="max-h-[78vh] max-w-full object-contain"
              />
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function prettyJson(raw: string): string {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}
