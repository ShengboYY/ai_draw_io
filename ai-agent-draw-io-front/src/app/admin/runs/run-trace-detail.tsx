'use client';

import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { agentApi, ApiResponseError } from '@/api/agent';
import type {
  AdminDebugTraceCaptureDTO,
  AdminDiagramTraceDTO,
  AdminDiagramTraceSpanDTO,
  TraceAnalysisJobViewDTO,
  TraceFindingViewDTO,
} from '@/types/api';
import { buildLoginHref } from '@/utils/login-form';
import {
  barColor,
  buildWaterfall,
  formatCost,
  formatMs,
  formatNumber,
  formatTime,
  isFailed,
  sourceLabel,
  traceDisplayName,
  traceKind,
  waterfallRowView,
  type TraceKindLike,
} from '../admin-shared';
import { TracePayloadPanel } from './trace-payload-panel';

// The trace inspector, fetched by run id. Rendered inline as the detail pane of
// the Trace Runs master-detail — the parent page owns the shell and selection.
// Deliberately lean: an identity/metric header over a single two-pane surface
// (execution tree | span inspector). No nested cards, no incidental copy.
export function RunTraceDetail({ runId, onClose }: { runId: string; onClose?: () => void }) {
  const router = useRouter();
  const returnTo = `/admin/runs?run=${encodeURIComponent(runId)}`;

  const [trace, setTrace] = useState<AdminDiagramTraceDTO | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [traceViewMode, setTraceViewMode] = useState<'tree' | 'timeline'>('tree');
  const [loading, setLoading] = useState(true);
  const [forbidden, setForbidden] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [analyzing, setAnalyzing] = useState(false);
  const [analyzeNote, setAnalyzeNote] = useState<string | null>(null);
  const [traceAnalyzer, setTraceAnalyzer] = useState<'DETERMINISTIC' | 'LLM' | 'VLM'>('DETERMINISTIC');
  const [analysisJob, setAnalysisJob] = useState<TraceAnalysisJobViewDTO | null>(null);
  const [analysisFindings, setAnalysisFindings] = useState<TraceFindingViewDTO[]>([]);
  const [zoomUrl, setZoomUrl] = useState<string | null>(null);

  const [spanPayloads, setSpanPayloads] = useState<Record<string, AdminDebugTraceCaptureDTO[]>>({});
  const [spanPayloadErrors, setSpanPayloadErrors] = useState<Record<string, string>>({});
  const spanPayloadRequests = useRef(new Set<string>());

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
      .adminDiagramTrace(runId)
      .then((res) => {
        if (!alive) return;
        setTrace(res.data);
        setSelectedId(res.data.spans?.find((span) => span.kind === 'RUN')?.id || res.data.spans?.[0]?.id || null);
        setSpanPayloads({});
        setSpanPayloadErrors({});
        setAnalyzeNote(null);
        spanPayloadRequests.current.clear();
      })
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
    const jobId = analysisJob?.job.id;
    if (!jobId || !['QUEUED', 'RUNNING'].includes(analysisJob.job.status)) return;
    // Poll the persisted Job rather than assuming the HTTP start response is its final state.
    const timer = window.setInterval(() => {
      agentApi.adminGetTraceAnalysisJob(jobId).then(({ data }) => {
        setAnalysisJob(data);
        if (!['QUEUED', 'RUNNING'].includes(data.job.status)) {
          void agentApi.adminListTraceFindings({ sourceRunId: runId, limit: 10 })
            .then(({ data: findings }) => setAnalysisFindings(findings || []));
        }
      }).catch((reason) => setAnalyzeNote(reason instanceof Error ? reason.message : 'Failed to refresh analysis job'));
    }, 1500);
    return () => window.clearInterval(timer);
  }, [runId, analysisJob]);

  const spans = useMemo(() => trace?.spans || [], [trace]);
  const findings = useMemo(() => trace?.findings || [], [trace]);
  const rows = useMemo(() => buildWaterfall(spans), [spans]);

  const selected: AdminDiagramTraceSpanDTO | undefined = useMemo(
    () => spans.find((e) => e.id === selectedId),
    [spans, selectedId],
  );

  useEffect(() => {
    if (!selected?.id
      || selected.kind === 'EVENT'
      || Object.prototype.hasOwnProperty.call(spanPayloads, selected.id)
      || Boolean(spanPayloadErrors[selected.id])
      || spanPayloadRequests.current.has(selected.id)) {
      return;
    }
    let alive = true;
    const selectedSpanId = selected.id;
    spanPayloadRequests.current.add(selectedSpanId);
    const request = selected.kind === 'RUN'
      ? agentApi.adminRunCaptures(runId)
      : agentApi.adminSpanPayloads(runId, selectedSpanId);
    request
      .then((response) => {
        if (!alive) return;
        const items = response.data || [];
        // The legacy run endpoint returns every capture; keep root-span I/O scoped in the inspector.
        const inspectorItems = selected.kind === 'RUN'
          ? items.filter((item) => !item.spanId || item.spanId === selectedSpanId)
          : items;
        setSpanPayloads((current) => ({ ...current, [selectedSpanId]: inspectorItems }));
      })
      .catch((reason) => {
        if (!alive) return;
        setSpanPayloadErrors((current) => ({
          ...current,
          [selectedSpanId]: reason instanceof Error ? reason.message : 'Failed to load span I/O',
        }));
      })
      .finally(() => {
        spanPayloadRequests.current.delete(selectedSpanId);
      });
    return () => {
      alive = false;
    };
  }, [runId, selected, spanPayloadErrors, spanPayloads]);

  const totalTokens = useMemo(
    () => trace?.summary?.totalTokens ?? spans.reduce((sum, c) => sum + (c.totalTokens || 0), 0),
    [spans, trace],
  );
  const totalCost = useMemo(
    () => trace?.summary?.estimatedCost ?? spans.reduce((sum, c) => sum + (c.estimatedCost || 0), 0),
    [spans, trace],
  );

  const analyzeTrace = () => {
    setAnalyzing(true);
    setAnalyzeNote(null);
    // The persistent job owns retries and evidence isolation; this view only starts and observes it.
    agentApi.adminStartTraceAnalysis(runId, traceAnalyzer)
      .then((response) => {
        setAnalysisJob(response.data);
        setAnalyzeNote(`Job ${response.data.job.status.toLowerCase()} · findings require review`);
        if (!['QUEUED', 'RUNNING'].includes(response.data.job.status)) {
          void agentApi.adminListTraceFindings({ sourceRunId: runId, limit: 10 })
            .then(({ data }) => setAnalysisFindings(data || []));
        }
      })
      .catch((reason) => setAnalyzeNote(reason instanceof Error ? reason.message : 'Failed to start analysis'))
      .finally(() => setAnalyzing(false));
  };

  const run = trace?.run;

  if (forbidden) {
    return (
      <div className="py-16 text-center">
        <h2 className="font-display text-lg font-semibold text-zinc-900">Admin access required</h2>
        <Link href={buildLoginHref(returnTo)} className="mt-3 inline-block text-sm font-medium text-zinc-700 hover:underline">
          Go to sign in
        </Link>
      </div>
    );
  }

  const llmCount = trace?.summary?.llmCallCount ?? spans.filter((s) => s.kind === 'LLM').length;
  const toolCount = trace?.summary?.toolCallCount ?? spans.filter((s) => s.kind === 'TOOL').length;

  return (
    <div>
      {/* Identity + metrics, no card chrome. */}
      <div className="mb-4 border-b border-stone-200 pb-4">
        <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-3">
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              {onClose && (
                <button
                  type="button"
                  onClick={onClose}
                  aria-label="Close trace detail"
                  className="flex h-6 w-6 items-center justify-center rounded text-zinc-400 transition hover:bg-stone-100 hover:text-zinc-700 xl:hidden"
                >
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="h-4 w-4">
                    <path d="M6 6l12 12M18 6L6 18" strokeLinecap="round" />
                  </svg>
                </button>
              )}
              {run && <StatusDot status={run.status} />}
              <span className="truncate font-mono text-sm font-medium text-zinc-900">
                {run ? run.id.replace(/^aru_/, '') : 'Trace'}
              </span>
            </div>
            {run && (
              <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-0.5 font-mono text-[11px] text-zinc-400">
                {run.agentId && <span className="truncate">{run.agentId}</span>}
                {run.requestType && <span className="truncate">{run.requestType}</span>}
                {run.diagramId && <span className="truncate">{run.diagramId}</span>}
                <span>{formatTime(run.startedAt)}</span>
              </div>
            )}
          </div>

          <div className="flex shrink-0 items-center gap-2">
            <select
              value={traceAnalyzer}
              onChange={(event) => setTraceAnalyzer(event.target.value as typeof traceAnalyzer)}
              aria-label="Analyzer"
              className="h-8 rounded-md border border-stone-200 bg-white px-2 text-xs text-zinc-700"
            >
              <option value="DETERMINISTIC">Rules</option>
              <option value="LLM">LLM</option>
              <option value="VLM">VLM</option>
            </select>
            <button
              type="button"
              onClick={analyzeTrace}
              disabled={!run || analyzing}
              className="h-8 rounded-md bg-zinc-900 px-3 text-xs font-medium text-white transition hover:bg-zinc-700 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {analyzing ? 'Starting…' : 'Analyze'}
            </button>
          </div>
        </div>

        {run && (
          <div className="mt-3 flex flex-wrap items-center gap-x-2.5 gap-y-1 text-xs text-zinc-500">
            <HeaderStat label="latency" value={formatMs(run.latencyMs)} />
            <HeaderStat label="llm" value={formatNumber(llmCount)} />
            <HeaderStat label="tools" value={formatNumber(toolCount)} />
            <HeaderStat label="tokens" value={formatNumber(totalTokens)} />
            <HeaderStat label="cost" value={formatCost(totalCost)} />
            {findings.length > 0 && <HeaderStat label="findings" value={formatNumber(findings.length)} tone="warn" />}
            {run.errorClass && <HeaderStat label="error" value={run.errorClass} tone="bad" />}
          </div>
        )}

        {(analyzeNote || analysisJob || analysisFindings.length > 0) && (
          <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 font-mono text-[10px] text-zinc-400">
            {analysisJob && <span>{analysisJob.job.status.toLowerCase()} · {analysisJob.job.succeededItems}/{analysisJob.job.totalItems} · ${analysisJob.job.actualCost.toFixed(4)}</span>}
            {analyzeNote && !analysisJob && <span>{analyzeNote}</span>}
            {analysisFindings.length > 0 && (
              <Link href="/admin/eval-candidates" className="text-amber-700 hover:underline">
                {analysisFindings.length} finding{analysisFindings.length > 1 ? 's' : ''} to review →
              </Link>
            )}
          </div>
        )}
      </div>

      {loading && <div className="py-16 text-center text-sm text-zinc-400">Loading trace…</div>}
      {error && <div className="rounded-md border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{error}</div>}

      {run && !loading && (
        <div className="grid grid-cols-1 overflow-hidden rounded-md border border-stone-200 bg-white lg:grid-cols-[minmax(220px,300px)_minmax(0,1fr)] lg:divide-x lg:divide-stone-200">
          {/* Execution: tree / timeline, one selection. */}
          <div className="min-w-0 lg:max-h-[600px] lg:overflow-y-auto">
            <div className="sticky top-0 z-10 flex items-center justify-end gap-1 border-b border-stone-100 bg-white/95 px-2.5 py-2 backdrop-blur">
              {(['tree', 'timeline'] as const).map((mode) => (
                <button
                  key={mode}
                  type="button"
                  onClick={() => setTraceViewMode(mode)}
                  className={`rounded px-2 py-0.5 text-[11px] font-medium capitalize transition ${
                    traceViewMode === mode ? 'bg-stone-100 text-zinc-800' : 'text-zinc-400 hover:text-zinc-700'
                  }`}
                >
                  {mode}
                </button>
              ))}
            </div>
            {rows.length === 0 ? (
              <div className="py-12 text-center text-sm text-zinc-400">No spans recorded.</div>
            ) : (
              <div className="flex flex-col p-2">
                {rows.map(({ event, leftPct, widthPct, isPoint }, index) => {
                  const failed = isFailed(event.status);
                  const isSel = event.id === selectedId;
                  const rowView = waterfallRowView(rows, index);
                  const kind = traceKind(event);
                  const isTree = traceViewMode === 'tree';
                  const isRoot = event.kind === 'RUN';
                  return (
                    <button
                      key={event.id}
                      onClick={() => setSelectedId(event.id)}
                      className={`group flex items-center gap-2 rounded px-1.5 text-left text-xs transition ${
                        isSel ? 'bg-indigo-50 ring-1 ring-inset ring-indigo-100' : 'hover:bg-stone-50'
                      } py-1 ${rowView.startsStepGroup ? 'mt-1.5' : index === 0 ? '' : 'mt-px'}`}
                    >
                      {isTree && rowView.visualDepth > 0 && (
                        <span className="flex shrink-0 self-stretch" aria-hidden>
                          {Array.from({ length: rowView.visualDepth }).map((_, depth) => (
                            <span key={depth} className="w-3.5 self-stretch border-l border-stone-200/80" />
                          ))}
                        </span>
                      )}
                      <TraceRowIcon kind={kind} failed={failed} />
                      <span
                        className={`min-w-0 truncate ${isTree ? '' : 'w-28 shrink-0'} ${
                          isRoot ? 'font-semibold' : 'font-medium'
                        } ${failed ? 'text-rose-700' : 'text-zinc-800'}`}
                        title={traceDisplayName(event)}
                      >
                        {traceDisplayName(event)}
                      </span>
                      {event.latencyMs != null && (
                        <span className="shrink-0 font-mono text-[10px] text-zinc-400">{formatMs(event.latencyMs)}</span>
                      )}
                      {isTree ? (
                        isRoot && (
                          <span className="ml-auto shrink-0 font-mono text-[10px] text-zinc-400">
                            {formatCompact(totalTokens)} · {formatCost(totalCost)}
                          </span>
                        )
                      ) : (
                        <span className="relative ml-1 h-3.5 flex-1 rounded bg-stone-100">
                          <span
                            className="absolute top-0 bottom-0 rounded"
                            style={{
                              left: `${leftPct}%`,
                              width: `${widthPct}%`,
                              minWidth: isPoint ? '3px' : '2px',
                              background: barColor(kind, event.status),
                            }}
                          />
                        </span>
                      )}
                    </button>
                  );
                })}
              </div>
            )}
          </div>

          {/* Inspector: the selected span's I/O. */}
          <div className="min-w-0 border-t border-stone-200 p-4 lg:border-t-0">
            {!selected ? (
              <div className="py-16 text-center text-sm text-zinc-400">Select a span.</div>
            ) : (
              <div>
                <div className="flex items-baseline justify-between gap-3">
                  <div className="min-w-0">
                    <div className="font-mono text-[10px] uppercase tracking-wide text-zinc-400">{sourceLabel(traceKind(selected))}</div>
                    <div className="mt-0.5 truncate text-sm font-semibold text-zinc-900">{traceDisplayName(selected)}</div>
                  </div>
                  <div className={`shrink-0 font-mono text-[11px] ${isFailed(selected.status) ? 'text-rose-700' : 'text-zinc-400'}`}>
                    {(selected.status || '').toLowerCase()}
                    {selected.latencyMs != null ? ` · ${formatMs(selected.latencyMs)}` : ''}
                  </div>
                </div>

                {(selected.kind === 'LLM' || selected.kind === 'TOOL') && (
                  <div className="mt-2 flex flex-wrap items-center gap-x-2.5 gap-y-1 font-mono text-[11px] text-zinc-500">
                    {selected.kind === 'LLM' && (selected.provider || selected.model) && (
                      <span className="truncate">{selected.provider || '—'}/{selected.model || '—'}</span>
                    )}
                    {selected.kind === 'LLM' && selected.totalTokens != null && (
                      <span>{formatNumber(selected.promptTokens)}/{formatNumber(selected.completionTokens)}/{formatNumber(selected.totalTokens)} tok</span>
                    )}
                    {selected.kind === 'LLM' && selected.estimatedCost != null && <span>{formatCost(selected.estimatedCost)}</span>}
                    {selected.kind === 'TOOL' && selected.toolName && <span>{selected.toolName}</span>}
                    {selected.errorClass && <span className="text-rose-600">{selected.errorClass}</span>}
                  </div>
                )}

                <SpanTiming span={selected} />

                {selected.kind === 'EVENT' ? (
                  <TraceEventMetadata metadataJson={selected.metadataJson} />
                ) : (
                  <TracePayloadPanel
                    payloads={spanPayloads[selected.id]}
                    loading={!Object.prototype.hasOwnProperty.call(spanPayloads, selected.id) && !spanPayloadErrors[selected.id]}
                    error={spanPayloadErrors[selected.id]}
                  />
                )}

                {selected.diagramEffect?.thumbnailUrl && (
                  <button
                    type="button"
                    onClick={() => setZoomUrl(selected.diagramEffect?.thumbnailUrl?.trim() || null)}
                    className="mt-4 block w-full overflow-hidden rounded-md border border-stone-200 bg-stone-50"
                    aria-label="Enlarge diagram"
                  >
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img src={selected.diagramEffect.thumbnailUrl} alt="Diagram after this span" className="max-h-64 w-full object-contain" />
                  </button>
                )}
              </div>
            )}
          </div>
        </div>
      )}

      {zoomUrl && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-4"
          role="dialog"
          aria-modal="true"
          aria-label="Diagram preview"
          onClick={() => setZoomUrl(null)}
        >
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={zoomUrl} alt="Diagram" className="max-h-[88vh] max-w-full object-contain" onClick={(e) => e.stopPropagation()} />
        </div>
      )}
    </div>
  );
}

function SpanTiming({ span }: { span: AdminDiagramTraceSpanDTO }) {
  if (!span.startedAt && !span.completedAt && span.latencyMs == null) return null;
  return (
    <dl className="mt-3 grid grid-cols-1 gap-2 rounded-md bg-stone-50 px-3 py-2.5 text-[11px] sm:grid-cols-3">
      <TimingValue label="Started" value={formatTime(span.startedAt)} />
      <TimingValue label="Completed" value={formatTime(span.completedAt)} />
      <TimingValue label="Duration" value={formatMs(span.latencyMs)} />
    </dl>
  );
}

function TimingValue({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0">
      <dt className="text-zinc-400">{label}</dt>
      <dd className="mt-0.5 truncate font-mono text-zinc-700" title={value}>{value}</dd>
    </div>
  );
}

function TraceEventMetadata({ metadataJson }: { metadataJson?: string }) {
  const metadata = parseMetadata(metadataJson);
  if (!metadata) {
    return <div className="mt-4 rounded-md bg-stone-50 px-3 py-3 text-xs text-zinc-400">No event metadata recorded.</div>;
  }
  return (
    <details open className="mt-4 rounded-md border border-stone-200 bg-white">
      <summary className="cursor-pointer list-none px-3 py-2 text-xs font-semibold text-zinc-700">
        Event metadata
      </summary>
      <dl className="grid grid-cols-1 gap-x-4 gap-y-2 border-t border-stone-100 p-3 sm:grid-cols-2">
        {Object.entries(metadata).map(([key, value]) => (
          <div key={key} className="min-w-0">
            <dt className="font-mono text-[10px] text-zinc-400">{key}</dt>
            <dd className="mt-0.5 break-all font-mono text-[11px] text-zinc-700">{formatMetadataValue(value)}</dd>
          </div>
        ))}
      </dl>
    </details>
  );
}

function parseMetadata(metadataJson?: string): Record<string, unknown> | null {
  if (!metadataJson) return null;
  try {
    const parsed: unknown = JSON.parse(metadataJson);
    return parsed != null && typeof parsed === 'object' && !Array.isArray(parsed)
      ? parsed as Record<string, unknown>
      : null;
  } catch {
    return null;
  }
}

function formatMetadataValue(value: unknown): string {
  if (value == null) return '—';
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}

function HeaderStat({ label, value, tone }: { label: string; value: string; tone?: 'warn' | 'bad' }) {
  const valueTone = tone === 'bad' ? 'text-rose-700' : tone === 'warn' ? 'text-amber-700' : 'text-zinc-800';
  return (
    <span className="inline-flex items-baseline gap-1">
      <span className="text-[11px] text-zinc-400">{label}</span>
      <span className={`font-mono text-xs font-medium ${valueTone}`}>{value}</span>
    </span>
  );
}

function StatusDot({ status }: { status?: string }) {
  const s = (status || '').toUpperCase();
  const tone = s === 'FAILED' ? 'bg-rose-500' : s === 'RUNNING' ? 'bg-blue-500' : s === 'SUCCESS' ? 'bg-emerald-500' : 'bg-zinc-300';
  return <span className={`h-2 w-2 shrink-0 rounded-full ${tone}`} title={s.toLowerCase()} />;
}

// Compact token/count formatting for the tree root summary (e.g. 116900 -> "116.9K").
function formatCompact(n?: number | null): string {
  if (n == null) return '—';
  if (Math.abs(n) < 1000) return `${n}`;
  if (Math.abs(n) < 1_000_000) return `${(n / 1000).toFixed(1)}K`;
  return `${(n / 1_000_000).toFixed(1)}M`;
}

// Type glyph for a trace row: boxed icons for agent/tool/diagram spans, bare
// accent icons for LLM/event spans.
function TraceRowIcon({ kind, failed }: { kind?: TraceKindLike; failed?: boolean }) {
  const bucket = sourceLabel(kind);
  const boxed = (tone: string, glyph: ReactNode) => (
    <span className={`flex h-5 w-5 shrink-0 items-center justify-center rounded-md ${failed ? 'bg-rose-100 text-rose-600' : tone}`}>
      {glyph}
    </span>
  );
  const bare = (tone: string, glyph: ReactNode) => (
    <span className={`flex h-5 w-5 shrink-0 items-center justify-center ${failed ? 'text-rose-500' : tone}`}>{glyph}</span>
  );

  if (bucket === 'run' || bucket === 'step') return boxed('bg-indigo-100 text-indigo-600', <BotGlyph />);
  if (bucket === 'tool') return boxed('bg-amber-100 text-amber-600', <WrenchGlyph />);
  if (bucket === 'diagram') return boxed('bg-teal-100 text-teal-600', <SquareGlyph />);
  if (bucket === 'quality') return boxed('bg-emerald-100 text-emerald-600', <CheckGlyph />);
  if (bucket === 'llm') return bare('text-violet-500', <SparkleGlyph />);
  return bare('text-zinc-400', <DiamondGlyph />);
}

function BotGlyph() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="h-3.5 w-3.5">
      <rect x="4" y="8" width="16" height="11" rx="3" />
      <path d="M12 4v4" strokeLinecap="round" />
      <circle cx="12" cy="3.2" r="1.3" fill="currentColor" stroke="none" />
      <circle cx="9.5" cy="13.5" r="1.1" fill="currentColor" stroke="none" />
      <circle cx="14.5" cy="13.5" r="1.1" fill="currentColor" stroke="none" />
    </svg>
  );
}

function WrenchGlyph() {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" className="h-3.5 w-3.5">
      <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z" />
    </svg>
  );
}

function SparkleGlyph() {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" className="h-4 w-4">
      <path d="M12 3c.6 4.8 3.6 7.8 8.4 8.4-4.8.6-7.8 3.6-8.4 8.4-.6-4.8-3.6-7.8-8.4-8.4C8.4 10.8 11.4 7.8 12 3z" />
    </svg>
  );
}

function DiamondGlyph() {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" className="h-2.5 w-2.5">
      <path d="M12 3l9 9-9 9-9-9z" />
    </svg>
  );
}

function SquareGlyph() {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" className="h-3 w-3">
      <rect x="4" y="4" width="16" height="16" rx="3" />
    </svg>
  );
}

function CheckGlyph() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" className="h-3.5 w-3.5">
      <path d="M5 12.5l4.5 4.5L19 7" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
