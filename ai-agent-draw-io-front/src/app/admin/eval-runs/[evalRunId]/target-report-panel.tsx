import type { EvalTargetReportDTO } from '@/types/api';

export function TargetReportPanel({ report, onOpenEpisode, isPartial }: { report: EvalTargetReportDTO; onOpenEpisode: (episodeId: string) => void; isPartial: boolean }) {
  return (
    <section className="mb-5 rounded-xl border border-stone-200 bg-white p-4 shadow-sm" aria-label="Target-specific report">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <p className="text-xs font-semibold uppercase tracking-wide text-violet-600">{targetName(report.target)} report</p>
          <h2 className="mt-1 text-lg font-semibold text-zinc-900">Metrics that match this evaluation target</h2>
          <p className="mt-1 text-xs text-zinc-500">{isPartial ? 'Partial report · refreshes while this Run is active · ' : ''}{report.eligibleEpisodes} eligible · {report.excludedErrors} infra errors excluded · {report.excludedUnavailable} unavailable excluded</p>
        </div>
        <div className="rounded-lg bg-stone-50 px-3 py-2 text-xs text-zinc-600">
          Latency {value(report.latency.medianMs, ' ms')} median · {value(report.latency.maxMs, ' ms')} max
          <div className="mt-0.5 text-zinc-400">p95: {report.latency.p95Availability === 'AVAILABLE' ? value(report.latency.p95Ms, ' ms') : report.latency.p95Reason}</div>
        </div>
      </div>
      {report.fullAgent && <FullAgent report={report.fullAgent} onOpenEpisode={onOpenEpisode} />}
      {report.router && <Router report={report.router} onOpenEpisode={onOpenEpisode} />}
      {report.drawing && <Drawing report={report.drawing} onOpenEpisode={onOpenEpisode} />}
    </section>
  );
}

function FullAgent({ report, onOpenEpisode }: { report: NonNullable<EvalTargetReportDTO['fullAgent']>; onOpenEpisode: (id: string) => void }) {
  return (
    <div className="mt-4">
      <div className="grid gap-2 sm:grid-cols-3">
        <Metric label="TSR@1 (95% CI)" value={report.availability === 'AVAILABLE'
          ? `${metric(report.tsrAtOne)} (${metric(report.ciLower)}–${metric(report.ciUpper)})`
          : report.unavailableReason || 'Unavailable'} />
        <Metric label="Error rate" value={metric(report.errorRate)} />
        <Metric label="Estimated cost" value={`$${report.estimatedCost.toFixed(4)}`} />
      </div>
      <h3 className="mt-4 text-xs font-semibold text-zinc-600">Failure funnel</h3>
      <div className="mt-2 grid gap-2 sm:grid-cols-5">
        {report.funnel.map((stage) => (
          <div key={stage.stage} className="rounded-lg border border-stone-200 p-2 text-xs">
            <div className="font-medium capitalize text-zinc-700">{stage.stage.replaceAll('_', ' ')}</div>
            <div className="mt-1 text-lg font-semibold text-zinc-900">{stage.passRate == null ? 'Unavailable' : pct(stage.passRate)}</div>
            <EpisodeLinks ids={stage.failedEpisodeIds} label="failed" onOpen={onOpenEpisode} />
            <EpisodeLinks ids={stage.unavailableEpisodeIds} label="unavailable" onOpen={onOpenEpisode} />
          </div>
        ))}
      </div>
    </div>
  );
}

function Router({ report, onOpenEpisode }: { report: NonNullable<EvalTargetReportDTO['router']>; onOpenEpisode: (id: string) => void }) {
  return (
    <div className="mt-4">
      <div className="grid gap-2 sm:grid-cols-4">
        <Metric label="Accuracy" value={metric(report.accuracy)} />
        <Metric label="Macro-F1" value={report.macroF1Availability === 'AVAILABLE' ? metric(report.macroF1) : report.macroF1Reason || 'Unavailable'} />
        <Metric label="Repeat stability" value={report.stabilityAvailability === 'AVAILABLE' ? metric(report.repeatStability) : report.stabilityReason || 'Unavailable'} />
        <Metric label="Invalid / unavailable evidence" value={`${report.invalidCount} invalid · ${report.evidenceUnavailableCount} unavailable`} />
      </div>
      <div className="mt-4 grid gap-4 lg:grid-cols-2">
        <Table title="Confusion matrix" headers={['Expected', 'Actual', 'Count']} rows={report.confusionMatrix.map((cell) => [
          cell.expectedRoute, cell.actualRoute, <EpisodeLinks key={`${cell.expectedRoute}:${cell.actualRoute}`} ids={cell.episodeIds} label={String(cell.count)} onOpen={onOpenEpisode} />,
        ])} />
        <Table title="Per-route quality" headers={['Route', 'Support', 'Precision / Recall / F1']} rows={report.perRoute.map((route) => [
          route.route, String(route.support), `${metric(route.precision)} / ${metric(route.recall)} / ${metric(route.f1)}`,
        ])} />
      </div>
    </div>
  );
}

function Drawing({ report, onOpenEpisode }: { report: NonNullable<EvalTargetReportDTO['drawing']>; onOpenEpisode: (id: string) => void }) {
  return (
    <div className="mt-4">
      <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
        {report.layers.map((layer) => (
          <div key={layer.graderName} className="rounded-lg bg-stone-50 p-3">
            <div className="text-[11px] text-zinc-400">{layer.graderName}</div>
            <div className="mt-1 text-sm font-semibold text-zinc-900">{metric(layer.passRate)}</div>
            <div className="mt-1 text-[10px] text-zinc-500">{layer.eligibleCount} eligible · {layer.failedCount} failed · {layer.unavailableCount} unavailable · {layer.notRequiredCount} not required</div>
            <EpisodeLinks ids={layer.failedEpisodeIds} label="failed" onOpen={onOpenEpisode} />
            <EpisodeLinks ids={layer.unavailableEpisodeIds} label="unavailable" onOpen={onOpenEpisode} />
          </div>
        ))}
      </div>
      <div className="mt-4 grid gap-4 lg:grid-cols-2">
        <Table title="Issue severity" headers={['Severity', 'Count']} rows={report.issueSeverities.map((issue) => [
          issue.severity, <EpisodeLinks key={issue.severity} ids={issue.episodeIds} label={String(issue.count)} onOpen={onOpenEpisode} />,
        ])} />
        <Table title={`Before / after evidence · Judge ${report.judgeAvailableCount} available / ${report.judgeUnavailableCount} unavailable / ${report.judgeNotRequiredCount} not required`} headers={['Case', 'Evidence', 'Canvas changed']} rows={report.evidence.map((item) => [
          item.caseId, <button type="button" key={item.episodeId} className="font-medium text-violet-700 hover:underline" onClick={() => onOpenEpisode(item.episodeId)}>Open episode</button>,
          item.canvasChanged == null ? 'Unavailable' : item.canvasChanged ? 'Yes' : 'No',
        ])} />
      </div>
    </div>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return <div className="rounded-lg bg-stone-50 p-3"><div className="text-[11px] text-zinc-400">{label}</div><div className="mt-1 text-sm font-semibold text-zinc-900">{value}</div></div>;
}
function EpisodeLinks({ ids, label, onOpen }: { ids: string[]; label: string; onOpen: (id: string) => void }) {
  if (!ids.length) return null;
  return <div className="mt-1 flex flex-wrap gap-1">{ids.slice(0, 3).map((id, index) => <button type="button" key={id} onClick={() => onOpen(id)} className="text-[10px] font-medium text-violet-700 hover:underline">{index === 0 ? label : `#${index + 1}`}</button>)}</div>;
}
function Table({ title, headers, rows }: { title: string; headers: string[]; rows: React.ReactNode[][] }) {
  return <div><h3 className="text-xs font-semibold text-zinc-600">{title}</h3><div className="mt-2 overflow-x-auto rounded-lg border border-stone-200"><table className="w-full text-left text-xs"><thead className="bg-stone-50 text-zinc-400"><tr>{headers.map((header) => <th key={header} className="px-2 py-1.5 font-medium">{header}</th>)}</tr></thead><tbody>{rows.map((row, index) => <tr key={index} className="border-t border-stone-100">{row.map((cell, cellIndex) => <td key={cellIndex} className="px-2 py-2 text-zinc-700">{cell}</td>)}</tr>)}</tbody></table></div></div>;
}
function targetName(target: EvalTargetReportDTO['target']) {
  if (target === 'FULL_AGENT') return 'Full Agent';
  if (target === 'INTENT_ROUTER') return 'Intent Router';
  if (target === 'VISUAL_REVIEW') return 'Visual Review';
  return 'Drawing Quality';
}
function pct(value: number) { return `${(value * 100).toFixed(1)}%`; }
function metric(value?: number) { return value == null ? 'Unavailable' : pct(value); }
function value(value?: number, suffix = '') { return value == null ? 'Unavailable' : `${value}${suffix}`; }
