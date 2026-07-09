import type {
  AdminDiagramTraceKind,
  AdminDiagramTraceSpanDTO,
  AdminRunTimelineEventDTO,
  AdminTimelineSource,
} from '@/types/api';

export const formatMs = (ms?: number | null): string => {
  if (ms == null) return '—';
  if (ms < 1000) return `${Math.round(ms)}ms`;
  return `${(ms / 1000).toFixed(ms < 10000 ? 2 : 1)}s`;
};

export const formatNumber = (n?: number | null): string =>
  n == null ? '—' : n.toLocaleString();

// Rough price table, USD per 1M tokens [prompt, completion]. Matched by model substring;
// unknown models fall back to a generic mid estimate. Meant for ballpark, not billing.
const PRICE_PER_M: { match: RegExp; in: number; out: number }[] = [
  { match: /gpt-5|gpt5/i, in: 1.25, out: 10 },
  { match: /gpt-4o|4o-mini/i, in: 2.5, out: 10 },
  { match: /opus/i, in: 15, out: 75 },
  { match: /sonnet/i, in: 3, out: 15 },
  { match: /haiku/i, in: 0.8, out: 4 },
  { match: /gemini/i, in: 1.25, out: 5 },
];
const FALLBACK_PRICE = { in: 2, out: 8 };

export const estCostUsd = (
  promptTokens?: number | null,
  completionTokens?: number | null,
  model?: string | null,
): number => {
  const p = PRICE_PER_M.find((row) => model && row.match.test(model)) ?? FALLBACK_PRICE;
  const inCost = ((promptTokens || 0) / 1_000_000) * p.in;
  const outCost = ((completionTokens || 0) / 1_000_000) * p.out;
  return inCost + outCost;
};

export const formatCost = (usd?: number | null): string => {
  if (usd == null || usd === 0) return '$0';
  if (usd < 0.01) return '<$0.01';
  return `$${usd.toFixed(usd < 1 ? 3 : 2)}`;
};

export const formatTime = (iso?: string): string => {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '—';
  return d.toLocaleString();
};

export const formatRelative = (iso?: string): string => {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '—';
  const diff = Date.now() - d.getTime();
  const s = Math.round(diff / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.round(h / 24)}d ago`;
};

export interface DiagramPreviewSource {
  diagramId?: string;
  title?: string;
  version?: number | null;
  updatedAt?: string;
  thumbnailUrl?: string | null;
}

export const diagramPreviewTitle = (diagram?: DiagramPreviewSource | null): string => {
  const title = diagram?.title?.trim();
  if (title) return title;
  const id = diagram?.diagramId?.trim();
  if (!id) return 'No linked diagram';
  return id.length > 11 ? `${id.slice(0, 11)}…` : id;
};

export const diagramPreviewMeta = (diagram?: DiagramPreviewSource | null): string => {
  const parts: string[] = [];
  if (typeof diagram?.version === 'number' && Number.isFinite(diagram.version)) {
    parts.push(`v${diagram.version}`);
  }
  if (diagram?.updatedAt) {
    const updatedAt = new Date(diagram.updatedAt);
    if (!Number.isNaN(updatedAt.getTime())) {
      // Keep admin snapshots stable across operator time zones.
      parts.push(`${updatedAt.toISOString().slice(0, 16).replace('T', ' ')} UTC`);
    }
  }
  return parts.length > 0 ? parts.join(' · ') : 'No saved canvas snapshot';
};

export const diagramPreviewHasImage = (diagram?: DiagramPreviewSource | null): boolean =>
  Boolean(diagram?.thumbnailUrl?.trim());

export const isFailed = (status?: string): boolean =>
  (status || '').toUpperCase() === 'FAILED';

export const isRunning = (status?: string): boolean =>
  (status || '').toUpperCase() === 'RUNNING';

// Tailwind tone classes for a run/span status pill.
export const statusPill = (status?: string): string => {
  const s = (status || '').toUpperCase();
  if (s === 'SUCCESS') return 'bg-emerald-50 text-emerald-700 border-emerald-200';
  if (s === 'FAILED') return 'bg-red-50 text-red-700 border-red-200';
  if (s === 'RUNNING') return 'bg-blue-50 text-blue-700 border-blue-200';
  return 'bg-neutral-100 text-neutral-600 border-neutral-200';
};

export type TraceKindLike = AdminTimelineSource | AdminDiagramTraceKind | string;

export interface TraceWaterfallEvent {
  id: string;
  parentId?: string;
  source?: AdminTimelineSource;
  kind?: AdminDiagramTraceKind;
  phase?: string;
  status?: string;
  detail?: string;
  name?: string;
  eventType?: string;
  occurredAt?: string;
  startedAt?: string;
  latencyMs?: number;
}

export type TraceEventLike = AdminRunTimelineEventDTO | AdminDiagramTraceSpanDTO;

export const traceKind = (event?: TraceWaterfallEvent | null): TraceKindLike | undefined =>
  event?.kind || event?.source;

export const traceDisplayName = (event?: TraceWaterfallEvent | null): string =>
  event?.name || event?.detail || event?.eventType || '—';

const normalizedKind = (source?: TraceKindLike): string =>
  (source || '').toLowerCase();

// Bar fill color per timeline source/kind (failed always wins → red).
export const barColor = (source?: TraceKindLike, status?: string): string => {
  if (isFailed(status)) return '#e24b4a';
  switch (normalizedKind(source)) {
    case 'step':
      return '#378add';
    case 'llm':
    case 'llm_call':
      return '#7f77dd';
    case 'tool':
    case 'tool_call':
      return '#ef9f27';
    case 'run':
      return '#58595d';
    default:
      return '#b4b2a9';
  }
};

export const sourceLabel = (source?: TraceKindLike): string => {
  switch (normalizedKind(source)) {
    case 'step':
      return 'step';
    case 'llm':
    case 'llm_call':
      return 'llm';
    case 'tool':
    case 'tool_call':
      return 'tool';
    case 'event':
    case 'trace_event':
      return 'event';
    case 'run':
      return 'run';
    case 'diagram':
      return 'diagram';
    case 'quality':
      return 'quality';
    default:
      return source || '';
  }
};

export interface WaterfallRow<T extends TraceWaterfallEvent = TraceEventLike> {
  event: T;
  depth: number;
  leftPct: number;
  widthPct: number;
  isPoint: boolean;
}

export interface WaterfallRowView {
  isStep: boolean;
  compact: boolean;
  startsStepGroup: boolean;
  visualDepth: number;
}

const startMs = (e: TraceWaterfallEvent): number => {
  const t = e.startedAt || e.occurredAt ? new Date(e.startedAt || e.occurredAt || '').getTime() : NaN;
  return Number.isNaN(t) ? 0 : t;
};

/**
 * Turn the flat, pre-sorted timeline into an ordered, indented waterfall.
 *
 * Nesting comes from parentId: an event whose parentId matches another event's
 * id is rendered as its child. Events whose parentId is the run (or missing)
 * are top-level. Bars are positioned proportionally across the run's span.
 */
export const buildWaterfall = <T extends TraceWaterfallEvent>(timeline: T[]): WaterfallRow<T>[] => {
  if (!timeline || timeline.length === 0) return [];

  const byId = new Map<string, T>();
  timeline.forEach((e) => byId.set(e.id, e));

  // Time window across the whole run.
  let runStart = Infinity;
  let runEnd = -Infinity;
  timeline.forEach((e) => {
    const s = startMs(e);
    const end = s + (e.latencyMs || 0);
    if (s < runStart) runStart = s;
    if (end > runEnd) runEnd = end;
  });
  const total = Math.max(1, runEnd - runStart);

  // Children preserve timeline (time-sorted) order.
  const children = new Map<string, T[]>();
  const roots: T[] = [];
  timeline.forEach((e) => {
    const parent = e.parentId && byId.has(e.parentId) ? e.parentId : null;
    if (parent) {
      const list = children.get(parent) || [];
      list.push(e);
      children.set(parent, list);
    } else {
      roots.push(e);
    }
  });

  const rows: WaterfallRow<T>[] = [];
  const visit = (e: T, depth: number) => {
    const s = startMs(e);
    const latency = e.latencyMs || 0;
    const isPoint = !e.latencyMs;
    const leftPct = ((s - runStart) / total) * 100;
    const widthPct = isPoint ? 1.5 : Math.max(0.8, (latency / total) * 100);
    rows.push({
      event: e,
      depth,
      leftPct: Math.min(99, Math.max(0, leftPct)),
      widthPct: Math.min(100 - Math.min(99, Math.max(0, leftPct)), widthPct),
      isPoint,
    });
    (children.get(e.id) || []).forEach((c) => visit(c, depth + 1));
  };
  roots.forEach((r) => visit(r, 0));
  return rows;
};

export const waterfallRowView = <T extends TraceWaterfallEvent>(
  rows: WaterfallRow<T>[],
  index: number,
): WaterfallRowView => {
  const row = rows[index];
  if (!row) return { isStep: false, compact: false, startsStepGroup: false, visualDepth: 0 };

  const isStep = normalizedKind(traceKind(row.event)) === 'step';
  let visualDepth = row.depth;
  let implicitStepChild = false;

  // Legacy rows may not have parentId; tuck same-phase spans under the nearest preceding step.
  if (!isStep && row.depth === 0) {
    for (let i = index - 1; i >= 0; i -= 1) {
      const candidate = rows[i];
      if (normalizedKind(traceKind(candidate.event)) !== 'step') continue;
      if (!row.event.phase || !candidate.event.phase || row.event.phase === candidate.event.phase) {
        visualDepth = candidate.depth + 1;
        implicitStepChild = true;
      }
      break;
    }
  }

  return {
    isStep,
    compact: !isStep && (row.depth > 0 || implicitStepChild),
    startsStepGroup: isStep && index > 0,
    visualDepth,
  };
};
