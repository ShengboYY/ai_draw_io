'use client';

import { use, useEffect, useState } from 'react';
import Link from 'next/link';
import { agentApi } from '@/api/agent';
import type { EvalEpisodeArtifactDTO, EvalEpisodeDetailDTO, EvalEpisodeViewDTO, EvalLiveRunReportDTO, EvalRunSummaryDTO, EvalTargetReportDTO } from '@/types/api';
import { AdminPageHeading, AdminShell } from '../../admin-shell';
import { EvaluationWorkspace } from '../../evaluation-workspace';
import { TargetReportPanel } from './target-report-panel';

const statuses = ['', 'PASS', 'FAIL', 'ERROR', 'UNAVAILABLE'];
export default function AdminEvalRunDetailPage({ params }: { params: Promise<{ evalRunId: string }> }) {
  const { evalRunId } = use(params); const [run, setRun] = useState<EvalRunSummaryDTO | null>(null);
  const [episodes, setEpisodes] = useState<EvalEpisodeViewDTO[]>([]); const [status, setStatus] = useState('');
  const [route, setRoute] = useState(''); const [risk, setRisk] = useState(''); const [language, setLanguage] = useState(''); const [agent, setAgent] = useState('');
  const [detail, setDetail] = useState<EvalEpisodeDetailDTO | null>(null); const [artifact, setArtifact] = useState<EvalEpisodeArtifactDTO | null>(null);
  const [insights, setInsights] = useState<EvalLiveRunReportDTO | null>(null);
  const [targetReport, setTargetReport] = useState<EvalTargetReportDTO | null>(null);
  const [error, setError] = useState<string | null>(null);
  const isRunActive = Boolean(run && ['QUEUED', 'RUNNING'].includes(run.status));
  const show = (reason: unknown) => setError(reason instanceof Error ? reason.message : 'Evaluation query failed');
  useEffect(() => { agentApi.adminGetEvalRun(evalRunId).then(({ data }) => setRun(data)).catch((reason) => setError(reason instanceof Error ? reason.message : 'Evaluation query failed')); }, [evalRunId]);
  useEffect(() => { agentApi.adminGetEvalTargetReport(evalRunId).then(({ data }) => setTargetReport(data)).catch(show); }, [evalRunId]);
  useEffect(() => { agentApi.adminListEvalEpisodes(evalRunId, { status, route, risk, language, agent }).then(({ data }) => setEpisodes(data || [])).catch((reason) => setError(reason instanceof Error ? reason.message : 'Evaluation query failed')); }, [evalRunId, status, route, risk, language, agent]);
  useEffect(() => { if (run?.mode !== 'MODE_B' && run?.status === 'COMPLETED') agentApi.adminGetEvalRunInsights(evalRunId).then(({ data }) => setInsights(data)).catch((reason) => setError(reason instanceof Error ? reason.message : 'Live evaluation insights failed')); }, [evalRunId, run?.mode, run?.status]);
  useEffect(() => {
    if (!isRunActive) return;
    // Running reports are partial; keep manifest, Episode matrix and target metrics in sync until terminal state.
    const timer = window.setInterval(() => {
      agentApi.adminGetEvalRun(evalRunId).then(({ data }) => setRun(data)).catch(show);
      agentApi.adminListEvalEpisodes(evalRunId, { status, route, risk, language, agent }).then(({ data }) => setEpisodes(data || [])).catch(show);
      agentApi.adminGetEvalTargetReport(evalRunId).then(({ data }) => setTargetReport(data)).catch(show);
    }, 3000);
    return () => window.clearInterval(timer);
  }, [evalRunId, isRunActive, status, route, risk, language, agent]);
  const open = (episode: EvalEpisodeViewDTO) => { setArtifact(null); agentApi.adminGetEvalEpisode(evalRunId, episode.id).then(({ data }) => setDetail(data)).catch(show); };
  const openById = (episodeId: string) => { setArtifact(null); agentApi.adminGetEvalEpisode(evalRunId, episodeId).then(({ data }) => setDetail(data)).catch(show); };
  const action = (name: 'cancel' | 'retry-errors') => agentApi.adminEvalRunAction(evalRunId, name).then(() => agentApi.adminGetEvalRun(evalRunId).then(({ data }) => setRun(data))).catch(show);
  const evaluateGate = () => agentApi.adminEvaluateEvalGate(evalRunId).then(({ data }) => setInsights((value) => value ? { ...value, gate: data } : value)).catch(show);
  const overrideGate = () => { const reason = window.prompt('Release override reason:'); if (reason) agentApi.adminOverrideEvalGate(evalRunId, reason).then(({ data }) => setInsights((value) => value ? { ...value, gate: data } : value)).catch(show); };
  return <AdminShell active="evalRuns"><AdminPageHeading eyebrow="Evaluation Run" title={run ? `${run.datasetId}@${run.datasetVersion}` : 'Loading run'} description={run ? `${run.mode} · ${run.status} · candidate ${run.gitSha}` : 'Loading immutable manifest…'}
    action={<div className="flex gap-2">{run && ['QUEUED', 'RUNNING'].includes(run.status) && <Button onClick={() => action('cancel')}>Cancel</Button>}{run?.status === 'COMPLETED' && run.errorCount > 0 && <Button onClick={() => action('retry-errors')}>Retry errors</Button>}</div>} />
    <EvaluationWorkspace active="runs" />
    {error && <p className="mb-4 rounded-lg bg-rose-50 p-3 text-sm text-rose-700">{error}</p>}
    {run && <div className="mb-5 grid gap-3 sm:grid-cols-4"><Metric label="Progress" value={`${Math.round(run.progress * 100)}%`} /><Metric label="PASS / FAIL" value={`${run.passCount} / ${run.failCount}`} /><Metric label="ERROR / unavailable" value={`${run.errorCount} / ${run.unavailableCount}`} /><Metric label="Latency / cost" value={`${run.totalLatencyMs} ms · $${run.estimatedCost.toFixed(4)}`} />{run.baselineRef && run.candidateRef && <div className="sm:col-span-4 text-xs text-zinc-500">Baseline {run.baselineRef} → Candidate {run.candidateRef}</div>}</div>}
    {insights && <section className="mb-5 rounded-lg border border-stone-200 bg-white p-4"><div className="grid gap-3 sm:grid-cols-4"><Metric label="TSR@1 (95% CI)" value={`${pct(insights.statistics.tsrAtOne)} (${pct(insights.statistics.ciLower)}–${pct(insights.statistics.ciUpper)})`} /><Metric label="Availability" value={pct(insights.statistics.graderAvailability)} /><Metric label="Paired delta" value={insights.comparison.decision === 'READY' ? `${(insights.comparison.delta * 100).toFixed(1)} pp` : 'NO_DECISION'} /><Metric label="Gate" value={insights.gate?.outcome || 'Not evaluated'} /></div>
      <div className="mt-3 grid gap-2 text-xs text-zinc-600 sm:grid-cols-4"><span>Provider: {insights.readiness.providerCredentialReady ? 'ready' : 'missing'}</span><span>Text Judge: {insights.readiness.judgeCalibrationApproved ? 'approved' : 'not approved'}</span><span>Visual Judge: {insights.readiness.visualJudgeCalibrationApproved ? 'approved' : 'not approved'}</span><span>Sequestered: {insights.readiness.sequesteredCaseCount}/{insights.readiness.minimumSequesteredCases}</span></div>
      {insights.gate && <div className="mt-3 rounded bg-stone-50 p-3 text-xs"><div className="font-semibold">Release Gate {insights.gate.outcome}{insights.gate.overrideApproved ? ' · OVERRIDDEN' : ''}</div><ul className="mt-1 list-disc pl-5">{gateReasons(insights.gate.reasonsJson).map((reason) => <li key={reason}>{reason}</li>)}</ul></div>}
      {run?.mode === 'RELEASE' && <div className="mt-3 flex gap-2"><Button onClick={evaluateGate}>Evaluate Gate</Button>{insights.gate?.outcome === 'BLOCK' && !insights.gate.overrideApproved && <Button onClick={overrideGate}>Release Owner override</Button>}</div>}
    </section>}
    {targetReport && <TargetReportPanel report={targetReport} onOpenEpisode={openById} isPartial={isRunActive} />}
    {run?.status === 'COMPLETED' && <section className="mb-5 flex flex-col gap-3 rounded-xl border border-violet-200 bg-violet-50 p-4 sm:flex-row sm:items-center sm:justify-between"><div><p className="text-sm font-semibold text-violet-950">Close the feedback loop</p><p className="mt-1 text-xs text-violet-800">Fix a known regression and rerun this Dataset, or capture a newly discovered blind spot as another Case.</p></div><div className="flex shrink-0 flex-wrap gap-3 text-xs font-semibold text-violet-900"><Link href="/admin/eval-runs" className="hover:underline">Rerun Dataset →</Link><Link href="/admin/eval-cases/new" className="hover:underline">Create Case →</Link><Link href="/admin/trace-findings" className="hover:underline">Trace Findings →</Link></div></section>}
    <div className="mb-4 flex flex-wrap gap-2"><Select value={status} values={statuses} onChange={setStatus} label="Status" /><Input value={route} onChange={setRoute} label="Route" /><Input value={risk} onChange={setRisk} label="Risk" /><Input value={language} onChange={setLanguage} label="Language" /><Input value={agent} onChange={setAgent} label="Agent" /></div>
    <h2 className="mb-2 text-sm font-semibold text-zinc-700">Case Matrix</h2>
    <div className="overflow-x-auto rounded-lg border border-stone-200 bg-white"><table className="w-full min-w-[760px] text-left text-xs"><thead className="bg-stone-50 text-zinc-500"><tr><Th>Case</Th><Th>Route / agent</Th><Th>Status</Th><Th>Grader / Judge matrix</Th><Th>Latency</Th></tr></thead><tbody>{episodes.map((episode) => <tr key={episode.id} onClick={() => open(episode)} className="cursor-pointer border-t border-stone-100 hover:bg-stone-50"><Td>{episode.caseId}@{episode.caseVersion}<div className="text-zinc-400">rep {episode.repetition + 1} · attempt {episode.attempt}</div></Td><Td>{episode.route}<div className="text-zinc-400">{episode.agent} · {episode.risk} · {episode.language}</div></Td><Td><EpisodeStatus value={episode.status} /></Td><Td><div className="flex flex-wrap gap-1">{episode.graders.map((g) => <span key={`${g.graderName}:${g.graderVersion}`} className={`rounded px-1.5 py-0.5 ${g.status === 'PASS' ? 'bg-emerald-50 text-emerald-700' : 'bg-rose-50 text-rose-700'}`}>{g.graderName}</span>)}{episode.judge && <span className={`rounded px-1.5 py-0.5 ${episode.judge.status === 'PASS' ? 'bg-emerald-50 text-emerald-700' : 'bg-rose-50 text-rose-700'}`}>Judge</span>}</div></Td><Td>{episode.latencyMs} ms</Td></tr>)}</tbody></table></div>
    {detail && <div className="fixed inset-0 z-30 bg-black/20" onClick={() => setDetail(null)}><aside className="ml-auto h-full w-full max-w-2xl overflow-auto bg-white p-6 shadow-xl" onClick={(event) => event.stopPropagation()}><div className="flex justify-between"><h2 className="text-lg font-semibold">{detail.episode.caseId}</h2><button onClick={() => setDetail(null)}>Close</button></div><EpisodeStatus value={detail.episode.status} />
      {detail.episode.status === 'ERROR' && <p className="mt-3 rounded bg-amber-50 p-3 text-sm text-amber-800">Infrastructure error: {detail.episode.errorClass} · {detail.episode.errorMessage}</p>}
      {detail.episode.status === 'FAIL' && <p className="mt-3 rounded bg-rose-50 p-3 text-sm text-rose-800">Agent output was produced, but one or more evaluation assertions failed.</p>}
      {detail.episode.blockingReason && <p className="mt-3 text-sm font-medium text-zinc-700">Blocking reason: {detail.episode.blockingReason}</p>}
      <Block title="Input / expected" value={JSON.stringify({ input: detail.input, expected: detail.expected }, null, 2)} />
      <h3 className="mt-5 text-sm font-semibold">Grader evidence</h3>{detail.episode.graders.map((g) => <Block key={g.graderName} title={`${g.graderName}@${g.graderVersion} · ${g.status}`} value={g.evidenceJson} />)}
      {detail.episode.judge && <Block title={`Judge@${detail.episode.judge.judgeVersion} · ${detail.episode.judge.status}`} value={JSON.stringify({ score: JSON.parse(detail.episode.judge.scoreJson), evidence: JSON.parse(detail.episode.judge.evidenceJson) }, null, 2)} />}
      <Button onClick={() => agentApi.adminGetEvalEpisodeArtifact(evalRunId, detail.episode.id).then(({ data }) => setArtifact(data)).catch(show)}>Load authorized trace & canvas</Button>
      {artifact && <><Block title="Trace waterfall" value={JSON.stringify(artifact.trace, null, 2)} /><Block title="Semantic diff" value={artifact.semanticDiff.join('\n')} /><CanvasComparison artifact={artifact} /><Block title="Canvas before XML" value={artifact.initialCanvasXml || 'Unavailable'} /><Block title="Canvas after XML" value={artifact.finalCanvasXml || 'Unavailable'} /></>}
    </aside></div>}
  </AdminShell>;
}

function EpisodeStatus({ value }: { value: string }) { const style = value === 'PASS' ? 'bg-emerald-50 text-emerald-700' : value === 'FAIL' ? 'bg-rose-50 text-rose-700' : value === 'ERROR' ? 'bg-amber-50 text-amber-800' : 'bg-stone-100 text-zinc-600'; return <span className={`rounded-full px-2 py-1 font-mono text-[10px] ${style}`}>{value}</span>; }
function Metric({ label, value }: { label: string; value: string }) { return <div className="rounded-lg border border-stone-200 bg-white p-3"><div className="text-xs text-zinc-400">{label}</div><div className="mt-1 font-medium">{value}</div></div>; }
function Button({ children, onClick }: { children: React.ReactNode; onClick: () => void }) { return <button type="button" onClick={onClick} className="rounded bg-zinc-800 px-3 py-1.5 text-xs text-white">{children}</button>; }
function Select({ label, value, values, onChange }: { label: string; value: string; values: string[]; onChange: (value: string) => void }) { return <label className="text-xs text-zinc-500">{label}<select value={value} onChange={(e) => onChange(e.target.value)} className="ml-1 rounded border border-stone-200 p-1.5"><option value="">All</option>{values.filter(Boolean).map((v) => <option key={v}>{v}</option>)}</select></label>; }
function Input({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }) { return <label className="text-xs text-zinc-500">{label}<input value={value} onChange={(e) => onChange(e.target.value)} className="ml-1 w-28 rounded border border-stone-200 p-1.5" /></label>; }
function Block({ title, value }: { title: string; value: string }) { return <div className="mt-4"><h3 className="text-xs font-semibold text-zinc-600">{title}</h3><pre className="mt-1 max-h-64 overflow-auto whitespace-pre-wrap break-all rounded bg-stone-50 p-3 text-[10px] text-zinc-600">{value}</pre></div>; }
function CanvasComparison({ artifact }: { artifact: EvalEpisodeArtifactDTO }) { return <div className="mt-4"><h3 className="text-xs font-semibold text-zinc-600">Canvas before / after pixels</h3><div className="mt-1 grid gap-2 sm:grid-cols-2">{[['Before', artifact.initialCanvasImageDataUrl], ['After', artifact.finalCanvasImageDataUrl]].map(([label, src]) => <figure key={label} className="rounded border border-stone-200 bg-white p-2"><figcaption className="mb-1 text-[10px] text-zinc-500">{label}</figcaption>{src ? <img src={src} alt={`${label} evaluated canvas`} className="h-56 w-full object-contain" /> : <div className="flex h-56 items-center justify-center text-xs text-zinc-400">Unavailable</div>}</figure>)}</div></div>; }
function Th({ children }: { children: React.ReactNode }) { return <th className="px-3 py-2 font-medium">{children}</th>; }
function Td({ children }: { children: React.ReactNode }) { return <td className="px-3 py-3 align-top">{children}</td>; }
function pct(value: number) { return `${(value * 100).toFixed(1)}%`; }
function gateReasons(value: string): string[] { try { const parsed = JSON.parse(value); return Array.isArray(parsed) ? parsed.map(String) : ['Invalid Gate reason data']; } catch { return ['Invalid Gate reason data']; } }
