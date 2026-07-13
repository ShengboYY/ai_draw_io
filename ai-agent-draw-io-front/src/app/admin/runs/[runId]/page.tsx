'use client';

import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import Link from 'next/link';
import { useParams, usePathname, useRouter } from 'next/navigation';
import { agentApi, ApiResponseError } from '@/api/agent';
import type {
  AdminDiagramEffectDTO,
  AdminDiagramFindingDTO,
  AdminDiagramSnapshotDTO,
  AdminDiagramTraceDTO,
  AdminDiagramTraceSpanDTO,
  AdminDebugTraceCaptureDTO,
  DiagramCanvasStateResponseDTO,
  TraceAnalysisJobViewDTO,
  TraceFindingViewDTO,
} from '@/types/api';
import { buildLoginHref } from '@/utils/login-form';
import {
  barColor,
  buildWaterfall,
  diagramPreviewMeta,
  diagramPreviewTitle,
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
} from '../../admin-shared';
import { AdminShell } from '../../admin-shell';
import { TraceAnalysisWorkspace } from '../../trace-analysis-workspace';
import { TracePayloadPanel } from './trace-payload-panel';
import { TraceWorkbench } from './trace-workbench';

function Stat({ label, value, bad }: { label: string; value: string; bad?: boolean }) {
  return (
    <div className="border-l border-stone-200 px-3 py-1 first:border-l-0">
      <div className="text-xs font-medium text-zinc-500">{label}</div>
      <div className={`mt-1 font-display text-lg font-semibold ${bad ? 'text-rose-700' : 'text-zinc-900'}`}>
        {value}
      </div>
    </div>
  );
}

function SummaryCard({ label, value, meta }: { label: string; value: string; meta: string }) {
  return (
    <div className="rounded-lg border border-stone-200 bg-white px-4 py-3 shadow-sm">
      <div className="font-mono text-[11px] uppercase tracking-wide text-zinc-400">{label}</div>
      <div className="mt-1 truncate text-sm font-medium text-zinc-900" title={value}>
        {value}
      </div>
      <div className="mt-1 truncate font-mono text-[11px] text-zinc-400" title={meta}>
        {meta}
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

  const [trace, setTrace] = useState<AdminDiagramTraceDTO | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [selectedSnapshotId, setSelectedSnapshotId] = useState<string | null>(null);
  const [playingReplay, setPlayingReplay] = useState(false);
  const [traceViewMode, setTraceViewMode] = useState<'tree' | 'timeline'>('tree');
  const [loading, setLoading] = useState(true);
  const [forbidden, setForbidden] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [evalCandidateLoading, setEvalCandidateLoading] = useState(false);
  const [evalCandidateNote, setEvalCandidateNote] = useState<string | null>(null);
  const [traceAnalyzer, setTraceAnalyzer] = useState<'DETERMINISTIC' | 'LLM' | 'VLM'>('DETERMINISTIC');
  const [traceAnalysisJob, setTraceAnalysisJob] = useState<TraceAnalysisJobViewDTO | null>(null);
  const [traceAnalysisFindings, setTraceAnalysisFindings] = useState<TraceFindingViewDTO[]>([]);
  const [diagramResult, setDiagramResult] = useState<{
    runId: string;
    diagram: DiagramCanvasStateResponseDTO | null;
    note: string | null;
  } | null>(null);

  const [captures, setCaptures] = useState<AdminDebugTraceCaptureDTO[] | null>(null);
  const [capturesLoading, setCapturesLoading] = useState(false);
  const [captureNote, setCaptureNote] = useState<string | null>(null);
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
        // Dynamic run routes can reuse this component, so never carry span caches across runs.
        setSelectedId(res.data.spans?.find((span) => span.kind === 'RUN')?.id
          || res.data.spans?.[0]?.id
          || null);
        setSpanPayloads({});
        setSpanPayloadErrors({});
        setCaptures(null);
        setEvalCandidateNote(null);
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
    const diagramId = trace?.run?.diagramId;
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
  }, [trace?.run?.diagramId, runId]);

  useEffect(() => {
    let alive = true;
    agentApi.adminListTraceFindings({ sourceRunId: runId, limit: 10 })
      .then(({ data }) => { if (alive) setTraceAnalysisFindings(data || []); })
      .catch(() => { /* The main Trace remains usable when its optional Finding projection is unavailable. */ });
    return () => { alive = false; };
  }, [runId]);

  useEffect(() => {
    const jobId = traceAnalysisJob?.job.id;
    if (!jobId || !['QUEUED', 'RUNNING'].includes(traceAnalysisJob.job.status)) return;
    // Poll the persisted Job rather than assuming the HTTP start response is its final state.
    const timer = window.setInterval(() => {
      agentApi.adminGetTraceAnalysisJob(jobId).then(({ data }) => {
        setTraceAnalysisJob(data);
        if (!['QUEUED', 'RUNNING'].includes(data.job.status)) {
          void agentApi.adminListTraceFindings({ sourceRunId: runId, limit: 10 })
            .then(({ data: findings }) => setTraceAnalysisFindings(findings || []));
        }
      }).catch((reason) => setEvalCandidateNote(reason instanceof Error ? reason.message : 'Failed to refresh analysis job'));
    }, 1500);
    return () => window.clearInterval(timer);
  }, [runId, traceAnalysisJob]);

  const spans = useMemo(() => trace?.spans || [], [trace]);
  const findings = useMemo(() => trace?.findings || [], [trace]);
  const snapshots = useMemo(() => trace?.snapshots || [], [trace]);
  const replayActive = playingReplay && snapshots.length > 0;
  const rows = useMemo(() => buildWaterfall(spans), [spans]);

  const selected: AdminDiagramTraceSpanDTO | undefined = useMemo(
    () => spans.find((e) => e.id === selectedId),
    [spans, selectedId],
  );
  const selectedMetadata = useMemo(() => parseJsonRecord(selected?.metadataJson), [selected?.metadataJson]);

  useEffect(() => {
    if (!selected?.id
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
        if (selected.kind === 'RUN') setCaptures(items);
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

  const activeSnapshot: AdminDiagramSnapshotDTO | undefined = useMemo(() => {
    if (selectedSnapshotId) {
      const snapshot = snapshots.find((item) => item.id === selectedSnapshotId);
      if (snapshot) return snapshot;
    }
    if (selected?.id) {
      const matching = snapshots.filter((snapshot) => snapshot.spanId === selected.id);
      return matching[matching.length - 1];
    }
    return undefined;
  }, [selected, selectedSnapshotId, snapshots]);

  const activeSnapshotIndex = activeSnapshot
    ? snapshots.findIndex((snapshot) => snapshot.id === activeSnapshot.id)
    : -1;
  const beforeSnapshot = activeSnapshotIndex > 0 ? snapshots[activeSnapshotIndex - 1] : undefined;
  const showDiagramPreview = isDiagramRelatedSpan(selected, snapshots);

  useEffect(() => {
    if (!replayActive) {
      return;
    }
    // Replay advances the selected snapshot and keeps the span inspector in sync.
    const timer = window.setInterval(() => {
      setSelectedSnapshotId((currentId) => {
        const currentIndex = snapshots.findIndex((snapshot) => snapshot.id === currentId);
        const nextIndex = currentIndex >= 0 ? (currentIndex + 1) % snapshots.length : 0;
        const next = snapshots[nextIndex];
        if (next?.spanId) {
          setSelectedId(next.spanId);
        }
        return next?.id || null;
      });
    }, 1200);
    return () => window.clearInterval(timer);
  }, [replayActive, snapshots]);

  const totalTokens = useMemo(
    () => trace?.summary?.totalTokens ?? spans.reduce((sum, c) => sum + (c.totalTokens || 0), 0),
    [spans, trace],
  );

  const totalCost = useMemo(
    () => trace?.summary?.estimatedCost ?? spans.reduce((sum, c) => sum + (c.estimatedCost || 0), 0),
    [spans, trace],
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

  const selectTraceSpan = (spanId: string) => {
    setSelectedId(spanId);
    const snapshot = snapshots.find((item) => item.spanId === spanId);
    if (snapshot?.id) {
      setSelectedSnapshotId(snapshot.id);
    } else {
      setSelectedSnapshotId(null);
    }
  };

  const enableCapture = () => {
    const uid = trace?.run?.userId;
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

  const analyzeTrace = () => {
    setEvalCandidateLoading(true);
    setEvalCandidateNote(null);
    // The persistent job owns retries and evidence isolation; this page only starts and observes it.
    agentApi.adminStartTraceAnalysis(runId, traceAnalyzer)
      .then((response) => {
        setTraceAnalysisJob(response.data);
        setEvalCandidateNote(`Analysis job ${response.data.job.id} · ${response.data.job.status}. Findings, if any, require review.`);
        if (!['QUEUED', 'RUNNING'].includes(response.data.job.status)) {
          void agentApi.adminListTraceFindings({ sourceRunId: runId, limit: 10 })
            .then(({ data }) => setTraceAnalysisFindings(data || []));
        }
      })
      .catch((reason) => {
        setEvalCandidateNote(reason instanceof Error ? reason.message : 'Failed to create Finding');
      })
      .finally(() => setEvalCandidateLoading(false));
  };

  const run = trace?.run;

  if (forbidden) {
    return (
      <AdminShell active="trace">
        <div className="mx-auto max-w-md py-20 text-center">
          <h1 className="font-display text-2xl font-semibold text-zinc-900">Admin access required</h1>
          <Link href={buildLoginHref(returnTo)} className="mt-5 inline-block text-sm font-medium text-zinc-700 hover:underline">
            Go to sign in
          </Link>
        </div>
      </AdminShell>
    );
  }

  const currentDiagramResult = diagramResult?.runId === runId ? diagramResult : null;
  const currentDiagram = currentDiagramResult?.diagram || null;
  const currentDiagramNote = currentDiagramResult?.note || null;
  const currentDiagramLoading = Boolean(run?.diagramId && !currentDiagramResult);
  const diagramOutcomeTitle = currentDiagram
    ? diagramPreviewTitle(currentDiagram)
    : run?.diagramId || 'No linked diagram';
  const diagramOutcomeMeta = currentDiagram
    ? diagramPreviewMeta(currentDiagram)
    : currentDiagramLoading
      ? 'Loading snapshot'
      : run?.diagramId
        ? 'Linked diagram'
        : 'No saved canvas snapshot';
  const routeMeta = `${formatNumber(run?.stepCount ?? spans.filter((span) => span.kind === 'STEP').length)} steps · ${formatNumber(
    run?.llmCallCount ?? spans.filter((span) => span.kind === 'LLM').length,
  )} llm · ${formatNumber(run?.toolCallCount ?? spans.filter((span) => span.kind === 'TOOL').length)} tools`;

  return (
    <AdminShell active="trace">
      <TraceAnalysisWorkspace active="runs" />
      <div className="mb-7 border-b border-stone-200 pb-5 sm:flex sm:items-start sm:justify-between sm:gap-5">
        <div className="min-w-0">
          <h1 className="font-display text-3xl font-semibold text-zinc-900 sm:text-4xl">Diagram trace</h1>
          {run && (
            // Keep the identifiers and start time with the page title rather than competing with the trace summary.
            <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1 font-mono text-xs text-zinc-500">
              <span className="min-w-0 truncate">ID: {run.id}</span>
              {run.diagramId && <span className="min-w-0 truncate">Diagram ID: {run.diagramId}</span>}
              <span>Started: {formatTime(run.startedAt)}</span>
            </div>
          )}
        </div>
        <div className="mt-4 shrink-0 sm:mt-0 sm:text-right">
          <label className="mr-2 inline-flex flex-col text-left text-[10px] font-medium uppercase tracking-wide text-zinc-400">Analyzer
            <select value={traceAnalyzer} onChange={(event) => setTraceAnalyzer(event.target.value as typeof traceAnalyzer)} className="mt-1 rounded-md border border-stone-200 bg-white px-2 py-1.5 text-xs normal-case text-zinc-700">
              <option value="DETERMINISTIC">Rules</option><option value="LLM">LLM semantic</option><option value="VLM">VLM visual</option>
            </select>
          </label>
          <button
            type="button"
            onClick={analyzeTrace}
            disabled={!run || evalCandidateLoading}
            className="rounded-md bg-zinc-900 px-3 py-2 text-sm font-medium text-white hover:bg-zinc-700 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {evalCandidateLoading ? 'Starting…' : 'Analyze trace'}
          </button>
          {evalCandidateNote && <div className="mt-1 max-w-xs text-xs text-zinc-500">{evalCandidateNote}</div>}
          {traceAnalysisJob && <div className="mt-1 max-w-xs font-mono text-[10px] text-zinc-500">{traceAnalysisJob.job.status} · {traceAnalysisJob.job.succeededItems}/{traceAnalysisJob.job.totalItems} analyzed · ${traceAnalysisJob.job.actualCost.toFixed(4)}</div>}
          {traceAnalysisFindings.length > 0 && <div className="mt-2 max-w-xs rounded-md bg-amber-50 px-2 py-1.5 text-left text-[11px] text-amber-800"><div className="font-semibold">Recent Findings</div>{traceAnalysisFindings.slice(0, 3).map((finding) => <Link key={finding.candidateId} href="/admin/eval-candidates" className="mt-1 block truncate hover:underline">{finding.analyzerType} · {finding.failureFamily}</Link>)}</div>}
        </div>
      </div>

      {loading && <div className="py-20 text-center text-sm text-zinc-400">Loading trace…</div>}
      {error && <div className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{error}</div>}

      {run && !loading && (
        <>
          <div className="mb-7">
            {/* Trace progression keeps request, execution, and diagram result readable at a glance. */}
            <div>
              <h2 className="font-display text-base font-semibold text-zinc-800">Trace progression</h2>
            </div>
            <div className="mt-2 grid grid-cols-1 gap-3 md:grid-cols-3">
              <SummaryCard
                label="Request"
                value={run.requestType || 'Unknown request'}
                meta={run.requestId ? `request ${run.requestId}` : 'No request id'}
              />
              <SummaryCard
                label="Agent route"
                value={run.agentId || 'Unknown agent'}
                meta={routeMeta}
              />
              <SummaryCard
                label="Diagram outcome"
                value={diagramOutcomeTitle}
                meta={diagramOutcomeMeta}
              />
            </div>

            <div className="mt-5 grid grid-cols-2 rounded-lg border border-stone-200 bg-white px-1 py-3 shadow-sm sm:grid-cols-4 lg:grid-cols-7">
              <Stat label="Latency" value={formatMs(run.latencyMs)} />
              <Stat label="LLM calls" value={formatNumber(trace?.summary?.llmCallCount ?? spans.filter((span) => span.kind === 'LLM').length)} />
              <Stat label="Tool calls" value={formatNumber(trace?.summary?.toolCallCount ?? spans.filter((span) => span.kind === 'TOOL').length)} />
              <Stat label="Tokens" value={formatNumber(totalTokens)} />
              <Stat label="Est. cost" value={formatCost(totalCost)} />
              <Stat label="Findings" value={formatNumber(findings.length)} bad={findings.some(isErrorFinding)} />
              <Stat label="Error" value={run.errorClass || '—'} bad={isFailed(run.status)} />
            </div>
          </div>

          <TraceWorkbench showDiagramPreview={showDiagramPreview}>
            {/* Tree and timeline share one selection so the inspector stays in sync. */}
            <div className="rounded-lg border border-stone-200 bg-white p-4 shadow-sm">
              <div className="mb-4 flex items-center justify-between gap-3 border-b border-stone-100 pb-3">
                <div>
                  <h2 className="font-display text-base font-semibold text-zinc-800">Trace execution</h2>
                  <p className="mt-1 text-xs text-zinc-500">Select a span to inspect its input, output and canvas effect.</p>
                </div>
                <div className="flex items-center gap-1 rounded-md bg-stone-100 p-1 text-[11px]">
                  {(['tree', 'timeline'] as const).map((mode) => (
                    <button
                      key={mode}
                      type="button"
                      onClick={() => setTraceViewMode(mode)}
                      className={`rounded px-2 py-1 font-medium capitalize ${
                        traceViewMode === mode ? 'bg-white text-zinc-800 shadow-sm' : 'text-zinc-500'
                      }`}
                    >
                      {mode}
                    </button>
                  ))}
                </div>
              </div>
              {rows.length === 0 ? (
                <div className="py-12 text-center text-sm text-zinc-400">No spans recorded.</div>
              ) : (
                <div className="flex flex-col">
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
                        onClick={() => selectTraceSpan(event.id)}
                        className={`group flex items-center gap-2 rounded-md px-1.5 text-left text-xs transition ${
                          isSel
                            ? 'bg-indigo-50 ring-1 ring-inset ring-indigo-100'
                            : 'hover:bg-stone-50'
                        } py-1 ${rowView.startsStepGroup ? 'mt-1.5' : index === 0 ? '' : 'mt-px'}`}
                      >
                        {isTree && rowView.visualDepth > 0 && (
                          <span className="flex shrink-0 self-stretch" aria-hidden>
                            {Array.from({ length: rowView.visualDepth }).map((_, depth) => (
                              <span key={depth} className="w-4 self-stretch border-l border-stone-200/80" />
                            ))}
                          </span>
                        )}
                        <TraceRowIcon kind={kind} failed={failed} />
                        <span
                          className={`min-w-0 truncate ${isTree ? '' : 'w-32 shrink-0'} ${
                            isRoot ? 'font-semibold' : 'font-medium'
                          } ${failed ? 'text-rose-700' : 'text-zinc-800'}`}
                          title={`${sourceLabel(kind)} · ${traceDisplayName(event)}`}
                        >
                          {traceDisplayName(event)}
                        </span>
                        {event.latencyMs != null && (
                          <span className="shrink-0 font-mono text-[11px] text-zinc-400">
                            {formatMs(event.latencyMs)}
                          </span>
                        )}
                        {failed && (
                          <span className="shrink-0 rounded bg-rose-100 px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-rose-600">
                            Error
                          </span>
                        )}
                        {isTree ? (
                          isRoot && (
                            <span className="ml-auto shrink-0 font-mono text-[11px] text-zinc-400">
                              {formatCompact(totalTokens)} · {formatCost(totalCost)}
                            </span>
                          )
                        ) : (
                          <span className="relative ml-1 h-4 flex-1 rounded bg-stone-100">
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

            {/* Input and output are the primary reading surface for the selected span. */}
            <div className="min-w-0 rounded-lg border border-stone-200 bg-white p-4 shadow-sm">
                <div className="mb-3 border-b border-stone-100 pb-3">
                  <h2 className="font-display text-base font-semibold text-zinc-800">Trace inspector</h2>
                  <p className="mt-1 text-xs text-zinc-500">Selected span details</p>
                </div>
                {!selected ? (
                  <div className="py-10 text-center text-sm text-zinc-400">
                    Select a trace item to inspect it.
                  </div>
                ) : (
                  <div className="text-sm">
                    <div className="font-mono text-[11px] uppercase tracking-wide text-zinc-400">{sourceLabel(traceKind(selected))}</div>
                    <div className="mt-1 text-base font-medium text-zinc-900">
                      {traceDisplayName(selected)}
                    </div>
                    <div
                      className={`mt-0.5 font-mono text-xs ${
                        isFailed(selected.status) ? 'text-rose-700' : 'text-zinc-500'
                      }`}
                    >
                      {(selected.status || '').toLowerCase()}
                      {selected.latencyMs != null ? ` · ${formatMs(selected.latencyMs)}` : ''}
                    </div>

                    <TracePayloadPanel
                      payloads={spanPayloads[selected.id]}
                      loading={!Object.prototype.hasOwnProperty.call(spanPayloads, selected.id)
                        && !spanPayloadErrors[selected.id]}
                      error={spanPayloadErrors[selected.id]}
                    />

                    <div className="mt-5 border-t border-stone-100 pt-4">
                      <div className="mb-3 font-mono text-[10px] font-semibold uppercase tracking-wide text-zinc-400">
                        Metadata
                      </div>
                      <dl className="space-y-2">
                      <Row k="Kind" v={selected.kind} />
                      <Row k="Phase" v={selected.phase} />
                      <Row k="Parent" v={selected.parentId} />
                      <Row k="Started" v={formatTime(selected.startedAt)} />
                      <Row k="Completed" v={formatTime(selected.completedAt)} />
                      {selected.kind === 'LLM' && (
                        <Row k="Provider / model" v={`${selected.provider || '—'} / ${selected.model || '—'}`} />
                      )}
                      {selected.kind === 'LLM' && (
                        <Row
                          k="Tokens (p/c/t)"
                          v={`${formatNumber(selected.promptTokens)} / ${formatNumber(
                            selected.completionTokens,
                          )} / ${formatNumber(selected.totalTokens)}`}
                        />
                      )}
                      {selected.kind === 'LLM' && <Row k="Est. cost" v={formatCost(selected.estimatedCost)} />}
                      {selected.kind === 'LLM' && <Row k="Provider request ID" v={selected.providerRequestId} />}
                      {selected.kind === 'LLM' && <Row k="Provider response ID" v={selected.providerResponseId} />}
                      {selected.kind === 'LLM' && <Row k="TTFT" v={formatOptionalMs(selected.ttftMs)} />}
                      {selected.kind === 'LLM' && (
                        <Row
                          k="Attempt / retry"
                          v={`${formatOptionalNumber(selected.attemptCount)} / ${formatOptionalNumber(selected.retryCount)}`}
                        />
                      )}
                      {selected.kind === 'TOOL' && <Row k="Tool" v={selected.toolName} />}
                      {selected.kind !== 'LLM' && <Row k="TTFT" v={formatOptionalMs(selectedMetadata?.ttftMs)} />}
                      {selected.kind !== 'LLM' && <Row k="Retry" v={formatOptionalNumber(selectedMetadata?.retryCount)} />}
                      <Row k="Outcome" v={stringValue(selectedMetadata?.outcome)} />
                      {selected.errorClass && <Row k="Error" v={selected.errorClass} bad />}
                      </dl>
                    </div>

                    {selected.diagramEffect && (
                      <DiagramEffectPanel effect={selected.diagramEffect} />
                    )}

                    {selected.metadataJson && (
                      <div className="mt-4">
                        <div className="mb-1 text-xs text-zinc-400">metadata</div>
                        <pre className="max-h-48 overflow-auto rounded-lg bg-stone-50 p-2 text-xs text-zinc-700">
                          {prettyJson(selected.metadataJson)}
                        </pre>
                      </div>
                    )}
                  </div>
                )}
            </div>

            {showDiagramPreview && (
              <aside className="min-w-0 space-y-4" aria-label="Diagram Preview">
                <DiagramSnapshotPanel
                  linkedDiagramId={run.diagramId}
                  diagram={currentDiagram}
                  snapshot={activeSnapshot || null}
                  beforeSnapshot={beforeSnapshot || null}
                  effect={selected?.diagramEffect}
                  loading={currentDiagramLoading}
                  note={currentDiagramNote}
                  onClearSnapshot={() => setSelectedSnapshotId(null)}
                />

                <EvolutionFilmstrip
                  snapshots={snapshots}
                  activeSnapshotId={activeSnapshot?.id}
                  playingReplay={replayActive}
                  setPlayingReplay={setPlayingReplay}
                  setSelectedId={setSelectedId}
                  setSelectedSnapshotId={setSelectedSnapshotId}
                />

                <DiagramValidationPanel
                  effect={selected?.diagramEffect}
                  findings={findings.filter((finding) => finding.spanId === selected?.id
                    || (finding.diagramId && finding.diagramId === selected?.diagramEffect?.diagramId))}
                />
              </aside>
            )}
          </TraceWorkbench>

          <TraceFindingsPanel findings={findings} setSelectedId={setSelectedId} />

          {/* Captured payload evidence remains retention-gated and loaded only on demand. */}
          <div className="mt-4 rounded-lg border border-stone-200 bg-white p-4 shadow-sm">
            <div className="mb-3 flex items-center justify-between border-b border-stone-100 pb-3">
              <div>
                <h2 className="font-display text-base font-semibold text-zinc-800">Payload evidence</h2>
                <p className="mt-1 text-xs text-zinc-500">Content is available only when capture was enabled for the run.</p>
              </div>
              {captures === null && (
                <button
                  onClick={loadCaptures}
                  disabled={capturesLoading}
                  className="theme-btn-secondary inline-flex h-9 shrink-0 items-center rounded-lg px-3 text-sm font-medium transition disabled:opacity-50"
                >
                  {capturesLoading ? 'Loading…' : 'Load payloads'}
                </button>
              )}
            </div>

            {captures && captures.length > 0 && (
              <div className="space-y-3">
                {captures.map((c) => {
                  const expired = isCaptureExpired(c);
                  return (
                    <div key={c.id} className="rounded-lg border border-stone-200">
                      <div className="flex items-center justify-between border-b border-stone-100 px-3 py-2 text-xs">
                        <span className="font-medium text-zinc-700">{c.eventType || 'payload'}</span>
                        <span className="font-mono text-[11px] text-zinc-400">{formatTime(c.createdAt)}</span>
                      </div>
                      {expired ? (
                        <div className="px-3 py-3 text-xs text-zinc-400">
                          Content expired and was cleaned up{c.contentDeletedAt ? ` (${formatTime(c.contentDeletedAt)})` : ''}.
                        </div>
                      ) : (
                        <pre className="max-h-64 overflow-auto whitespace-pre-wrap break-words px-3 py-2 text-xs text-zinc-700">
                          {c.content || '(empty)'}
                        </pre>
                      )}
                    </div>
                  );
                })}
              </div>
            )}

            {captureNote && (
              <div className="rounded-lg bg-stone-50 px-3 py-3 text-xs text-zinc-500">
                <p>{captureNote}</p>
                {captures && captures.length === 0 && trace?.run?.userId && (
                  <button
                    onClick={enableCapture}
                    className="theme-btn-secondary mt-2 inline-flex h-8 items-center rounded-lg px-3 text-xs font-medium transition"
                  >
                    Enable capture for this user
                  </button>
                )}
              </div>
            )}
          </div>
        </>
      )}
    </AdminShell>
  );
}

function Row({ k, v, bad }: { k: string; v?: string; bad?: boolean }) {
  if (!v) return null;
  return (
    <div className="flex justify-between gap-3">
      <dt className="shrink-0 text-zinc-400">{k}</dt>
      <dd className={`min-w-0 break-all text-right ${bad ? 'text-rose-700' : 'text-zinc-700'}`}>{v}</dd>
    </div>
  );
}

function DiagramEffectPanel({ effect }: { effect: AdminDiagramEffectDTO }) {
  return (
    <div className="mt-4 rounded-lg bg-stone-50 p-3">
      <div className="mb-2 text-xs font-medium text-zinc-500">Diagram effect</div>
      <dl className="space-y-2">
        <Row k="Diagram ID" v={effect.diagramId} />
        <Row k="Version" v={formatEffectVersion(effect)} />
        <Row k="Before hash" v={effect.beforeHash} />
        <Row k="After hash" v={effect.afterHash} />
        <Row k="Change" v={formatDiagramChange(effect)} />
        <Row k="Changed cells" v={formatOptionalNumber(effect.changedCellCount)} />
        <Row k="Render status" v={effect.renderStatus} />
        <Row k="Thumbnail" v={effect.thumbnailUrl ? 'present' : 'missing'} />
        <Row k="XML changed" v={formatEffectBoolean(effect.xmlChanged)} />
        <Row k="Thumbnail changed" v={formatEffectBoolean(effect.thumbnailChanged)} />
      </dl>
    </div>
  );
}

function formatDiagramChange(effect: AdminDiagramEffectDTO): string {
  if (effect.beforeHash && effect.afterHash) {
    return effect.beforeHash === effect.afterHash ? 'unchanged' : 'updated';
  }
  return effect.afterHash ? 'created' : effect.beforeHash ? 'removed' : 'unknown';
}

function parseJsonRecord(value?: string): Record<string, unknown> | undefined {
  if (!value) return undefined;
  try {
    const parsed = JSON.parse(value);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? parsed as Record<string, unknown>
      : undefined;
  } catch {
    return undefined;
  }
}

function formatOptionalMs(value: unknown): string | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? formatMs(value) : undefined;
}

function formatOptionalNumber(value: unknown): string | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? formatNumber(value) : undefined;
}

function stringValue(value: unknown): string | undefined {
  return typeof value === 'string' && value ? value : undefined;
}

function isCaptureExpired(capture: AdminDebugTraceCaptureDTO): boolean {
  if (capture.contentDeletedAt) return true;
  if (capture.content != null || !capture.contentExpiresAt) return false;
  const expiresAt = new Date(capture.contentExpiresAt).getTime();
  return Number.isFinite(expiresAt) && expiresAt <= Date.now();
}

function formatEffectVersion(effect: AdminDiagramEffectDTO): string | undefined {
  if (effect.beforeVersion != null && effect.afterVersion != null) {
    return `v${effect.beforeVersion} -> v${effect.afterVersion}`;
  }
  if (effect.afterVersion != null) return `v${effect.afterVersion}`;
  if (effect.beforeVersion != null) return `v${effect.beforeVersion}`;
  return undefined;
}

function formatEffectBoolean(value?: boolean): string {
  if (value == null) return 'unknown';
  return value ? 'yes' : 'no';
}

function formatBooleanChange(value?: boolean): string {
  if (value == null) return 'unknown';
  return value ? 'changed' : 'unchanged';
}

function isDiagramRelatedSpan(
  span: AdminDiagramTraceSpanDTO | undefined,
  snapshots: AdminDiagramSnapshotDTO[],
): boolean {
  if (!span) return false;
  if (span.diagramEffect || snapshots.some((snapshot) => snapshot.spanId === span.id)) return true;
  const name = `${span.toolName || ''} ${span.eventType || ''}`.toLowerCase();
  if (span.kind === 'TOOL' || span.kind === 'EVENT') {
    return name.includes('diagram') || name.includes('canvas') || name.includes('render');
  }
  // A drawing STEP owns persisted snapshots; ordinary LLM spans keep the wider two-column inspector.
  return span.kind === 'STEP' && `${span.phase || ''}`.toLowerCase().includes('drawing');
}

function DiagramValidationPanel({
  effect,
  findings,
}: {
  effect?: AdminDiagramEffectDTO;
  findings: AdminDiagramFindingDTO[];
}) {
  return (
    <div className="rounded-lg border border-stone-200 bg-white p-4 shadow-sm">
      <div className="border-b border-stone-100 pb-3">
        <h2 className="font-display text-base font-semibold text-zinc-800">Validation</h2>
        <p className="mt-1 text-xs text-zinc-500">Selected canvas effect and related findings.</p>
      </div>
      <dl className="mt-3 space-y-2 text-xs">
        <Row k="Render status" v={effect?.renderStatus || 'No render evidence'} />
        <Row k="XML" v={formatBooleanChange(effect?.xmlChanged)} />
        <Row k="Thumbnail" v={formatBooleanChange(effect?.thumbnailChanged)} />
      </dl>
      <div className="mt-3 space-y-2 border-t border-stone-100 pt-3">
        {findings.length === 0 ? (
          <div className="text-xs text-zinc-400">No findings for this diagram span.</div>
        ) : findings.map((finding, index) => (
          <div key={`${finding.code || 'finding'}-${index}`} className="rounded-md bg-stone-50 px-2.5 py-2">
            <div className="flex items-center gap-2">
              <span className={`rounded border px-1.5 py-0.5 text-[10px] ${findingSeverityClass(finding.severity)}`}>
                {finding.severity || 'INFO'}
              </span>
              <span className="truncate text-xs font-medium text-zinc-700">
                {finding.title || finding.code || 'Finding'}
              </span>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function TraceFindingsPanel({
  findings,
  setSelectedId,
}: {
  findings: AdminDiagramFindingDTO[];
  setSelectedId: (spanId: string) => void;
}) {
  return (
    <div className="mt-4 rounded-lg border border-stone-200 bg-white p-4 shadow-sm">
      <div className="mb-3 flex items-center justify-between gap-3 border-b border-stone-100 pb-3">
        <div>
          <h2 className="font-display text-base font-semibold text-zinc-800">Trace findings</h2>
          <p className="mt-1 text-xs text-zinc-500">Automated checks surfaced during this run.</p>
        </div>
        <span className="font-mono text-xs text-zinc-400">{formatNumber(findings.length)}</span>
      </div>

      {findings.length === 0 ? (
        <div className="py-8 text-center text-sm text-zinc-400">No findings.</div>
      ) : (
        <div className="space-y-2">
          {findings.map((finding, index) => (
            <div
              key={`${finding.code || 'finding'}-${finding.spanId || index}`}
              className="rounded-lg border border-stone-200 px-3 py-3"
            >
              <div className="flex flex-wrap items-start justify-between gap-2">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className={`rounded-md border px-2 py-0.5 text-[11px] ${findingSeverityClass(finding.severity)}`}>
                      {finding.severity || 'INFO'}
                    </span>
                    <span className="font-mono text-xs text-zinc-400">{finding.code || 'FINDING'}</span>
                  </div>
                  <div className="mt-1 text-sm font-medium text-zinc-900">
                    {finding.title || finding.code || 'Trace finding'}
                  </div>
                </div>
                {finding.spanId && (
                  <button
                    type="button"
                    onClick={() => finding.spanId && setSelectedId(finding.spanId)}
                    className="theme-btn-secondary shrink-0 rounded-md px-2 py-1 text-xs font-medium transition"
                  >
                    Go to span
                  </button>
                )}
              </div>
              {finding.description && (
                <p className="mt-1 text-sm text-zinc-600">{finding.description}</p>
              )}
              {finding.suggestion && (
                <p className="mt-1 text-xs text-zinc-400">{finding.suggestion}</p>
              )}
              {(finding.spanId || finding.diagramId) && (
                <div className="mt-2 flex flex-wrap gap-2 font-mono text-[11px] text-zinc-400">
                  {finding.spanId && <span>span {finding.spanId}</span>}
                  {finding.diagramId && <span>diagram {finding.diagramId}</span>}
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function isErrorFinding(finding: AdminDiagramFindingDTO): boolean {
  return finding.severity === 'ERROR';
}

function findingSeverityClass(severity?: string): string {
  if (severity === 'ERROR') return 'border-rose-200 bg-rose-50 text-rose-700';
  if (severity === 'WARNING') return 'border-amber-200 bg-amber-50 text-amber-700';
  return 'border-sky-200 bg-sky-50 text-sky-700';
}

function EvolutionFilmstrip({
  snapshots,
  activeSnapshotId,
  playingReplay,
  setPlayingReplay,
  setSelectedId,
  setSelectedSnapshotId,
}: {
  snapshots: AdminDiagramSnapshotDTO[];
  activeSnapshotId?: string;
  playingReplay: boolean;
  setPlayingReplay: (playing: boolean) => void;
  setSelectedId: (spanId: string) => void;
  setSelectedSnapshotId: (snapshotId: string | null) => void;
}) {
  return (
    <div className="rounded-lg border border-stone-200 bg-white p-4 shadow-sm">
      <div className="mb-3 flex items-center justify-between gap-3 border-b border-stone-100 pb-3">
        <div>
          <h2 className="font-display text-base font-semibold text-zinc-800">Diagram evolution</h2>
          <p className="mt-1 text-xs text-zinc-500">Canvas snapshots captured through the run.</p>
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={() => setPlayingReplay(!playingReplay)}
            disabled={snapshots.length === 0}
            className="theme-btn-secondary rounded-md px-2 py-1 text-xs font-medium transition disabled:cursor-not-allowed disabled:opacity-40"
          >
            {playingReplay ? 'Pause' : 'Play'}
          </button>
          <span className="font-mono text-xs text-zinc-400">{formatNumber(snapshots.length)}</span>
        </div>
      </div>

      {snapshots.length === 0 ? (
        <div className="py-8 text-center text-sm text-zinc-400">No diagram snapshots.</div>
      ) : (
        <div className="flex gap-2 overflow-x-auto pb-1">
          {snapshots.map((snapshot, index) => (
            <button
              key={snapshot.id || `${snapshot.diagramId || 'snapshot'}-${index}`}
              type="button"
              onClick={() => {
                if (snapshot.id) {
                  setSelectedSnapshotId(snapshot.id);
                }
                if (snapshot.spanId) {
                  setSelectedId(snapshot.spanId);
                }
              }}
              className={`min-w-44 rounded-lg border p-2 text-left transition hover:border-stone-300 hover:bg-white ${
                snapshot.id && snapshot.id === activeSnapshotId
                  ? 'border-zinc-400 bg-stone-50'
                  : 'border-stone-200 bg-stone-50'
              }`}
            >
              <div className="flex items-center justify-between gap-2">
                <span className="text-xs font-medium text-zinc-700">
                  {snapshot.version != null ? `v${snapshot.version}` : `#${index + 1}`}
                </span>
                <span className="text-[11px] text-zinc-400">{formatTime(snapshot.createdAt)}</span>
              </div>
              {snapshot.thumbnailUrl ? (
                // eslint-disable-next-line @next/next/no-img-element
                <img
                  src={snapshot.thumbnailUrl}
                  alt={snapshot.summary || snapshot.diagramId || 'diagram snapshot'}
                  className="mt-2 h-20 w-full rounded-md bg-white object-contain"
                />
              ) : (
                <div className="mt-2 flex h-20 items-center justify-center rounded-md bg-white text-xs text-zinc-300">
                  XML
                </div>
              )}
              <div className="mt-2 truncate font-mono text-[11px] text-zinc-400" title={snapshot.canvasHash}>
                {snapshot.canvasHash || 'no hash'}
              </div>
              <div className="mt-1 truncate text-xs text-zinc-500" title={snapshot.summary || snapshot.diagramId}>
                {snapshot.summary || snapshot.diagramId || 'snapshot'}
              </div>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function DiagramSnapshotPanel({
  linkedDiagramId,
  diagram,
  snapshot,
  beforeSnapshot,
  effect,
  loading,
  note,
  onClearSnapshot,
}: {
  linkedDiagramId?: string;
  diagram: DiagramCanvasStateResponseDTO | null;
  snapshot?: AdminDiagramSnapshotDTO | null;
  beforeSnapshot?: AdminDiagramSnapshotDTO | null;
  effect?: AdminDiagramEffectDTO;
  loading: boolean;
  note: string | null;
  onClearSnapshot?: () => void;
}) {
  const previewText = snapshot
    ? snapshot.summary || snapshot.canvasHash || ''
    : diagram?.currentXml || diagram?.summary || '';
  const [zoomOpen, setZoomOpen] = useState(false);
  const thumbnailUrl = snapshot ? snapshot.thumbnailUrl?.trim() || '' : diagram?.thumbnailUrl?.trim() || '';
  const beforeThumbnailUrl = beforeSnapshot?.thumbnailUrl?.trim() || '';
  const canZoom = Boolean(thumbnailUrl);
  const title = snapshot
    ? snapshotPreviewTitle(snapshot)
    : diagram
      ? diagramPreviewTitle(diagram)
      : linkedDiagramId || 'No linked diagram';
  const meta = snapshot
    ? snapshotPreviewMeta(snapshot)
    : diagram
      ? diagramPreviewMeta(diagram)
      : linkedDiagramId || '—';

  return (
    <div className="rounded-lg border border-stone-200 bg-white p-4 shadow-sm">
      <div className="mb-3 flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="font-display text-base font-semibold text-zinc-800">Diagram Preview</h2>
          <div className="mt-1 truncate text-xs text-zinc-500">{title}</div>
          <div className="mt-1 font-mono text-[10px] text-zinc-400">{meta}</div>
        </div>
        {snapshot && onClearSnapshot && (
          <button
            type="button"
            onClick={onClearSnapshot}
            className="theme-btn-secondary shrink-0 rounded-md px-2 py-1 text-xs font-medium transition"
          >
            Current
          </button>
        )}
      </div>

      {effect && (
        <div className="mb-3 flex flex-wrap gap-1.5 border-y border-stone-100 py-2">
          <span className="rounded bg-stone-100 px-1.5 py-1 font-mono text-[10px] text-zinc-600">
            {formatEffectVersion(effect) || 'version unavailable'}
          </span>
          <span className="rounded bg-stone-100 px-1.5 py-1 font-mono text-[10px] text-zinc-600">
            XML {formatBooleanChange(effect.xmlChanged)}
          </span>
          <span className="rounded bg-stone-100 px-1.5 py-1 font-mono text-[10px] text-zinc-600">
            thumbnail {formatBooleanChange(effect.thumbnailChanged)}
          </span>
        </div>
      )}

      {loading && <div className="py-10 text-center text-sm text-zinc-400">Loading diagram…</div>}

      {!loading && snapshot && (
        <div className="grid grid-cols-2 gap-2">
          <SnapshotPreviewFrame label="Before" snapshot={beforeSnapshot} thumbnailUrl={beforeThumbnailUrl} />
          <SnapshotPreviewFrame
            label="After"
            snapshot={snapshot}
            thumbnailUrl={thumbnailUrl}
            onOpen={canZoom ? () => setZoomOpen(true) : undefined}
          />
        </div>
      )}

      {!loading && !snapshot && thumbnailUrl && (
        <SnapshotPreviewFrame
          label="Current"
          title={title}
          thumbnailUrl={thumbnailUrl}
          onOpen={canZoom ? () => setZoomOpen(true) : undefined}
        />
      )}

      {!loading && !snapshot && diagram && !thumbnailUrl && (
        <pre className="max-h-56 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-stone-50 p-2 text-xs text-zinc-600">
          {previewText || 'No canvas XML.'}
        </pre>
      )}

      {!loading && note && (
        <div className="rounded-lg bg-stone-50 px-3 py-3 text-xs text-zinc-500">{note}</div>
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
            <div className="flex items-start justify-between gap-3 border-b border-stone-200 px-4 py-3">
              <div className="min-w-0">
                <div className="truncate text-sm font-medium text-zinc-900">{title}</div>
                <div className="mt-0.5 font-mono text-xs text-zinc-400">{meta}</div>
              </div>
              <button
                type="button"
                onClick={() => setZoomOpen(false)}
                className="theme-btn-secondary shrink-0 rounded-md px-2 py-1 text-xs font-medium transition"
              >
                Close
              </button>
            </div>
            <div className="flex min-h-0 flex-1 items-center justify-center overflow-auto bg-stone-50 p-4">
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

function SnapshotPreviewFrame({
  label,
  snapshot,
  thumbnailUrl,
  title,
  onOpen,
}: {
  label: string;
  snapshot?: AdminDiagramSnapshotDTO | null;
  thumbnailUrl: string;
  title?: string;
  onOpen?: () => void;
}) {
  const content = thumbnailUrl ? (
    // eslint-disable-next-line @next/next/no-img-element
    <img
      src={thumbnailUrl}
      alt={title || snapshot?.summary || `${label} diagram snapshot`}
      className="h-36 w-full object-contain"
    />
  ) : (
    <div className="flex h-36 items-center justify-center px-3 text-center text-xs text-zinc-400">
      {snapshot ? snapshot.summary || 'Snapshot image unavailable' : 'No previous snapshot'}
    </div>
  );
  return (
    <div className="min-w-0 overflow-hidden rounded-lg border border-stone-200 bg-stone-50">
      <div className="flex items-center justify-between gap-2 border-b border-stone-200 px-2 py-1.5">
        <span className="text-[10px] font-semibold uppercase tracking-wide text-zinc-500">{label}</span>
        <span className="truncate font-mono text-[10px] text-zinc-400">
          {snapshot?.version != null ? `v${snapshot.version}` : ''}
        </span>
      </div>
      {onOpen ? (
        <button type="button" onClick={onOpen} className="block w-full bg-white" aria-label={`View larger ${label.toLowerCase()} diagram`}>
          {content}
        </button>
      ) : content}
    </div>
  );
}

function snapshotPreviewTitle(snapshot: AdminDiagramSnapshotDTO): string {
  if (snapshot.version != null) return `Snapshot v${snapshot.version}`;
  return snapshot.diagramId || 'Diagram snapshot';
}

function snapshotPreviewMeta(snapshot: AdminDiagramSnapshotDTO): string {
  const parts = [
    snapshot.diagramId,
    snapshot.canvasHash ? `hash ${snapshot.canvasHash}` : undefined,
    snapshot.createdAt ? formatTime(snapshot.createdAt) : undefined,
  ].filter(Boolean);
  return parts.join(' · ') || 'snapshot';
}

function prettyJson(raw: string): string {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

// Compact token/count formatting for the tree root summary (e.g. 116900 -> "116.9K").
function formatCompact(n?: number | null): string {
  if (n == null) return '—';
  if (Math.abs(n) < 1000) return `${n}`;
  if (Math.abs(n) < 1_000_000) return `${(n / 1000).toFixed(1)}K`;
  return `${(n / 1_000_000).toFixed(1)}M`;
}

// Type glyph for a trace row, matching the trace-viewer visual language:
// boxed icons for agent/tool spans, bare accent icons for LLM/event spans.
function TraceRowIcon({ kind, failed }: { kind?: TraceKindLike; failed?: boolean }) {
  const bucket = sourceLabel(kind);
  const boxed = (tone: string, glyph: ReactNode) => (
    <span
      className={`flex h-5 w-5 shrink-0 items-center justify-center rounded-md ${
        failed ? 'bg-rose-100 text-rose-600' : tone
      }`}
    >
      {glyph}
    </span>
  );
  const bare = (tone: string, glyph: ReactNode) => (
    <span className={`flex h-5 w-5 shrink-0 items-center justify-center ${failed ? 'text-rose-500' : tone}`}>
      {glyph}
    </span>
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
