'use client';

import { use, useEffect, useState } from 'react';
import { agentApi } from '@/api/agent';
import type { EvalEpisodeArtifactDTO, EvalEpisodeDetailDTO, EvalEpisodeViewDTO, EvalRunSummaryDTO } from '@/types/api';
import { AdminPageHeading, AdminShell } from '../../admin-shell';

const statuses = ['', 'PASS', 'FAIL', 'ERROR', 'UNAVAILABLE'];
export default function AdminEvalRunDetailPage({ params }: { params: Promise<{ evalRunId: string }> }) {
  const { evalRunId } = use(params); const [run, setRun] = useState<EvalRunSummaryDTO | null>(null);
  const [episodes, setEpisodes] = useState<EvalEpisodeViewDTO[]>([]); const [status, setStatus] = useState('');
  const [route, setRoute] = useState(''); const [risk, setRisk] = useState(''); const [language, setLanguage] = useState(''); const [agent, setAgent] = useState('');
  const [detail, setDetail] = useState<EvalEpisodeDetailDTO | null>(null); const [artifact, setArtifact] = useState<EvalEpisodeArtifactDTO | null>(null);
  const [error, setError] = useState<string | null>(null);
  const show = (reason: unknown) => setError(reason instanceof Error ? reason.message : 'Evaluation query failed');
  useEffect(() => { agentApi.adminGetEvalRun(evalRunId).then(({ data }) => setRun(data)).catch((reason) => setError(reason instanceof Error ? reason.message : 'Evaluation query failed')); }, [evalRunId]);
  useEffect(() => { agentApi.adminListEvalEpisodes(evalRunId, { status, route, risk, language, agent }).then(({ data }) => setEpisodes(data || [])).catch((reason) => setError(reason instanceof Error ? reason.message : 'Evaluation query failed')); }, [evalRunId, status, route, risk, language, agent]);
  const open = (episode: EvalEpisodeViewDTO) => { setArtifact(null); agentApi.adminGetEvalEpisode(evalRunId, episode.id).then(({ data }) => setDetail(data)).catch(show); };
  const action = (name: 'cancel' | 'retry-errors') => agentApi.adminEvalRunAction(evalRunId, name).then(() => agentApi.adminGetEvalRun(evalRunId).then(({ data }) => setRun(data))).catch(show);
  return <AdminShell active="evalRuns"><AdminPageHeading eyebrow="Evaluation Run" title={run ? `${run.datasetId}@${run.datasetVersion}` : 'Loading run'} description={run ? `${run.mode} · ${run.status} · candidate ${run.gitSha}` : 'Loading immutable manifest…'}
    action={<div className="flex gap-2">{run && ['QUEUED', 'RUNNING'].includes(run.status) && <Button onClick={() => action('cancel')}>Cancel</Button>}{run?.status === 'COMPLETED' && run.errorCount > 0 && <Button onClick={() => action('retry-errors')}>Retry errors</Button>}</div>} />
    {error && <p className="mb-4 rounded-lg bg-rose-50 p-3 text-sm text-rose-700">{error}</p>}
    {run && <div className="mb-5 grid gap-3 sm:grid-cols-4"><Metric label="Progress" value={`${Math.round(run.progress * 100)}%`} /><Metric label="PASS / FAIL" value={`${run.passCount} / ${run.failCount}`} /><Metric label="ERROR / unavailable" value={`${run.errorCount} / ${run.unavailableCount}`} /><Metric label="Latency / cost" value={`${run.totalLatencyMs} ms · $${run.estimatedCost.toFixed(4)}`} />{run.baselineRef && run.candidateRef && <div className="sm:col-span-4 text-xs text-zinc-500">Baseline {run.baselineRef} → Candidate {run.candidateRef}</div>}</div>}
    <div className="mb-4 flex flex-wrap gap-2"><Select value={status} values={statuses} onChange={setStatus} label="Status" /><Input value={route} onChange={setRoute} label="Route" /><Input value={risk} onChange={setRisk} label="Risk" /><Input value={language} onChange={setLanguage} label="Language" /><Input value={agent} onChange={setAgent} label="Agent" /></div>
    <div className="overflow-x-auto rounded-lg border border-stone-200 bg-white"><table className="w-full min-w-[760px] text-left text-xs"><thead className="bg-stone-50 text-zinc-500"><tr><Th>Case</Th><Th>Route / agent</Th><Th>Status</Th><Th>Grader matrix</Th><Th>Latency</Th></tr></thead><tbody>{episodes.map((episode) => <tr key={episode.id} onClick={() => open(episode)} className="cursor-pointer border-t border-stone-100 hover:bg-stone-50"><Td>{episode.caseId}@{episode.caseVersion}<div className="text-zinc-400">rep {episode.repetition + 1} · attempt {episode.attempt}</div></Td><Td>{episode.route}<div className="text-zinc-400">{episode.agent} · {episode.risk} · {episode.language}</div></Td><Td><EpisodeStatus value={episode.status} /></Td><Td><div className="flex flex-wrap gap-1">{episode.graders.map((g) => <span key={`${g.graderName}:${g.graderVersion}`} className={`rounded px-1.5 py-0.5 ${g.status === 'PASS' ? 'bg-emerald-50 text-emerald-700' : 'bg-rose-50 text-rose-700'}`}>{g.graderName}</span>)}</div></Td><Td>{episode.latencyMs} ms</Td></tr>)}</tbody></table></div>
    {detail && <div className="fixed inset-0 z-30 bg-black/20" onClick={() => setDetail(null)}><aside className="ml-auto h-full w-full max-w-2xl overflow-auto bg-white p-6 shadow-xl" onClick={(event) => event.stopPropagation()}><div className="flex justify-between"><h2 className="text-lg font-semibold">{detail.episode.caseId}</h2><button onClick={() => setDetail(null)}>Close</button></div><EpisodeStatus value={detail.episode.status} />
      {detail.episode.status === 'ERROR' && <p className="mt-3 rounded bg-amber-50 p-3 text-sm text-amber-800">Infrastructure error: {detail.episode.errorClass} · {detail.episode.errorMessage}</p>}
      {detail.episode.status === 'FAIL' && <p className="mt-3 rounded bg-rose-50 p-3 text-sm text-rose-800">Agent output was produced, but one or more evaluation assertions failed.</p>}
      {detail.episode.blockingReason && <p className="mt-3 text-sm font-medium text-zinc-700">Blocking reason: {detail.episode.blockingReason}</p>}
      <Block title="Input / expected" value={JSON.stringify({ input: detail.input, expected: detail.expected }, null, 2)} />
      <h3 className="mt-5 text-sm font-semibold">Grader evidence</h3>{detail.episode.graders.map((g) => <Block key={g.graderName} title={`${g.graderName}@${g.graderVersion} · ${g.status}`} value={g.evidenceJson} />)}
      <Button onClick={() => agentApi.adminGetEvalEpisodeArtifact(evalRunId, detail.episode.id).then(({ data }) => setArtifact(data)).catch(show)}>Load authorized trace & canvas</Button>
      {artifact && <><Block title="Trace waterfall" value={JSON.stringify(artifact.trace, null, 2)} /><Block title="Semantic diff" value={artifact.semanticDiff.join('\n')} /><Block title="Canvas before" value={artifact.initialCanvasXml || 'Unavailable'} /><Block title="Canvas after" value={artifact.finalCanvasXml || 'Unavailable'} /></>}
    </aside></div>}
  </AdminShell>;
}

function EpisodeStatus({ value }: { value: string }) { const style = value === 'PASS' ? 'bg-emerald-50 text-emerald-700' : value === 'FAIL' ? 'bg-rose-50 text-rose-700' : value === 'ERROR' ? 'bg-amber-50 text-amber-800' : 'bg-stone-100 text-zinc-600'; return <span className={`rounded-full px-2 py-1 font-mono text-[10px] ${style}`}>{value}</span>; }
function Metric({ label, value }: { label: string; value: string }) { return <div className="rounded-lg border border-stone-200 bg-white p-3"><div className="text-xs text-zinc-400">{label}</div><div className="mt-1 font-medium">{value}</div></div>; }
function Button({ children, onClick }: { children: React.ReactNode; onClick: () => void }) { return <button type="button" onClick={onClick} className="rounded bg-zinc-800 px-3 py-1.5 text-xs text-white">{children}</button>; }
function Select({ label, value, values, onChange }: { label: string; value: string; values: string[]; onChange: (value: string) => void }) { return <label className="text-xs text-zinc-500">{label}<select value={value} onChange={(e) => onChange(e.target.value)} className="ml-1 rounded border border-stone-200 p-1.5"><option value="">All</option>{values.filter(Boolean).map((v) => <option key={v}>{v}</option>)}</select></label>; }
function Input({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }) { return <label className="text-xs text-zinc-500">{label}<input value={value} onChange={(e) => onChange(e.target.value)} className="ml-1 w-28 rounded border border-stone-200 p-1.5" /></label>; }
function Block({ title, value }: { title: string; value: string }) { return <div className="mt-4"><h3 className="text-xs font-semibold text-zinc-600">{title}</h3><pre className="mt-1 max-h-64 overflow-auto whitespace-pre-wrap break-all rounded bg-stone-50 p-3 text-[10px] text-zinc-600">{value}</pre></div>; }
function Th({ children }: { children: React.ReactNode }) { return <th className="px-3 py-2 font-medium">{children}</th>; }
function Td({ children }: { children: React.ReactNode }) { return <td className="px-3 py-3 align-top">{children}</td>; }
