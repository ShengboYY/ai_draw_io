import Link from 'next/link';
import { AdminPageHeading, AdminShell } from '../admin-shell';
import { EvaluationWorkspace } from '../evaluation-workspace';

export default function AdminEvaluationsPage() {
  return (
    <AdminShell active="evalRuns">
      <AdminPageHeading
        eyebrow="Agent quality"
        title="Evaluation workspace"
        description="Use repeatable Cases to test the Agent, then turn useful development Traces into new regression coverage."
      />
      <EvaluationWorkspace active="overview" />

      <section className="grid gap-4 lg:grid-cols-2">
        <PathCard
          eyebrow="Recommended now"
          title="Start with offline evaluation"
          description="No production traffic required. A Recorded-model replay runs the real router, tool policy, XML mutation and quality graders with stable recorded model responses."
          href="/admin/eval-cases/new"
          action="Create your first Case"
          tone="primary"
          steps={['Create or import a synthetic Case', 'Validate, dry-run and publish it', 'Add it to a Dataset and start a Mode B Run']}
        />
        <PathCard
          eyebrow="Grow coverage"
          title="Turn development traces into regression Cases"
          description="Use rule or LLM discovery to find suspicious runs. A finding stays a Candidate until you review it and create a sanitized Case."
          href="/admin/eval-candidates"
          action="Open Trace Inbox"
          steps={['Run the Agent while developing', 'Screen sanitized Trace evidence', 'Review the Draft before it becomes a Case']}
        />
      </section>

      <section className="mt-5 rounded-2xl border border-stone-200 bg-stone-50 p-5">
        <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h2 className="font-display text-lg font-semibold text-zinc-900">What should I use?</h2>
            <p className="mt-1 max-w-3xl text-sm text-zinc-500">Use Mode B for fast deterministic regression checks. Use Mode C only when you want to measure the live model. Release comparison and canary operations can wait until you have a baseline and real traffic.</p>
          </div>
          <Link href="/admin/eval-runs" className="shrink-0 text-sm font-semibold text-zinc-700 hover:underline">View evaluation history →</Link>
        </div>
      </section>
    </AdminShell>
  );
}

function PathCard({ eyebrow, title, description, href, action, steps, tone }: {
  eyebrow: string;
  title: string;
  description: string;
  href: string;
  action: string;
  steps: string[];
  tone?: 'primary';
}) {
  return (
    <article className={`rounded-2xl border p-6 shadow-sm ${tone === 'primary' ? 'border-zinc-800 bg-zinc-900 text-white' : 'border-stone-200 bg-white text-zinc-900'}`}>
      <p className={`font-mono text-[11px] font-semibold uppercase tracking-[0.12em] ${tone === 'primary' ? 'text-emerald-300' : 'text-violet-600'}`}>{eyebrow}</p>
      <h2 className="mt-3 font-display text-2xl font-semibold">{title}</h2>
      <p className={`mt-2 text-sm leading-6 ${tone === 'primary' ? 'text-zinc-300' : 'text-zinc-500'}`}>{description}</p>
      <ol className={`mt-5 space-y-2 border-l pl-4 text-sm ${tone === 'primary' ? 'border-zinc-700 text-zinc-300' : 'border-stone-200 text-zinc-600'}`}>
        {steps.map((step, index) => <li key={step}><span className="mr-2 font-mono text-[10px] opacity-60">0{index + 1}</span>{step}</li>)}
      </ol>
      <Link href={href} className={`mt-6 inline-flex h-10 items-center rounded-lg px-4 text-sm font-semibold transition ${tone === 'primary' ? 'bg-white text-zinc-900 hover:bg-zinc-100' : 'bg-zinc-800 text-white hover:bg-zinc-700'}`}>{action} →</Link>
    </article>
  );
}
