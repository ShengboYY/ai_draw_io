import type { ReactNode } from 'react';

// Shared visual language for the evaluation workspace: every status enum gets one
// human-readable label and one semantic tone so operators never read raw constants.

type Tone = 'neutral' | 'info' | 'progress' | 'success' | 'warn' | 'danger' | 'violet';

const TONE_CLASSES: Record<Tone, string> = {
  neutral: 'bg-stone-100 text-zinc-600 ring-stone-200',
  info: 'bg-sky-50 text-sky-700 ring-sky-200',
  progress: 'bg-blue-50 text-blue-700 ring-blue-200',
  success: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
  warn: 'bg-amber-50 text-amber-800 ring-amber-200',
  danger: 'bg-rose-50 text-rose-700 ring-rose-200',
  violet: 'bg-violet-50 text-violet-700 ring-violet-200',
};

const STATUS_PRESETS: Record<string, { label: string; tone: Tone }> = {
  // Case working copy lifecycle
  DRAFT: { label: 'Draft', tone: 'neutral' },
  VALIDATED: { label: 'Validated', tone: 'info' },
  DRY_RUN_PASSED: { label: 'Dry-run passed', tone: 'info' },
  UNDER_REVIEW: { label: 'In review', tone: 'warn' },
  APPROVED: { label: 'Approved', tone: 'success' },
  REJECTED: { label: 'Rejected', tone: 'danger' },
  // Published cases & dataset versions
  PUBLISHED: { label: 'Published', tone: 'success' },
  RETIRED: { label: 'Retired', tone: 'neutral' },
  // Eval runs
  QUEUED: { label: 'Queued', tone: 'neutral' },
  RUNNING: { label: 'Running', tone: 'progress' },
  COMPLETED: { label: 'Completed', tone: 'success' },
  FAILED: { label: 'Failed', tone: 'danger' },
  CANCELLED: { label: 'Cancelled', tone: 'neutral' },
  // Episodes
  PASS: { label: 'Pass', tone: 'success' },
  FAIL: { label: 'Fail', tone: 'danger' },
  ERROR: { label: 'Infra error', tone: 'warn' },
  UNAVAILABLE: { label: 'Unavailable', tone: 'neutral' },
  // Trace candidates
  DETECTED: { label: 'Awaiting review', tone: 'progress' },
  TRIAGED: { label: 'Draft retry ready', tone: 'warn' },
  DRAFT_READY: { label: 'Draft ready', tone: 'success' },
  NEEDS_MANUAL_RECONSTRUCTION: { label: 'Needs manual rebuild', tone: 'warn' },
  EXPIRED: { label: 'Expired', tone: 'neutral' },
  PURGED: { label: 'Purged', tone: 'neutral' },
  // Case origin routes
  TRACE_DRAFT: { label: 'From trace', tone: 'violet' },
  // Case health
  HEALTHY: { label: 'Healthy', tone: 'success' },
  FLAKY: { label: 'Flaky', tone: 'danger' },
  STALE_REVIEW: { label: 'Stale review', tone: 'warn' },
  BROKEN_BASELINE: { label: 'Broken baseline', tone: 'danger' },
  ALWAYS_PASS_REVIEW: { label: 'Always-pass review', tone: 'warn' },
  UNSCORABLE: { label: 'Unscorable', tone: 'neutral' },
  // Canary recommendations
  CONTINUE: { label: 'Continue', tone: 'success' },
  HALT_RECOMMENDED: { label: 'Halt recommended', tone: 'danger' },
  NO_DECISION: { label: 'No decision', tone: 'warn' },
  // Risk levels
  critical: { label: 'Critical risk', tone: 'danger' },
  high: { label: 'High risk', tone: 'warn' },
  medium: { label: 'Medium risk', tone: 'info' },
  low: { label: 'Low risk', tone: 'neutral' },
};

export function statusLabel(value: string): string {
  return STATUS_PRESETS[value]?.label
    || value.toLowerCase().replaceAll('_', ' ').replace(/^./, (char) => char.toUpperCase());
}

export function StatusBadge({ value, label }: { value: string; label?: string }) {
  const tone = STATUS_PRESETS[value]?.tone || 'neutral';
  return (
    <span className={`inline-flex items-center whitespace-nowrap rounded-full px-2.5 py-0.5 text-[11px] font-medium ring-1 ring-inset ${TONE_CLASSES[tone]}`}>
      {label || statusLabel(value)}
    </span>
  );
}

const MODE_LABELS: Record<string, string> = {
  MODE_B: 'Recorded replay · Mode B',
  MODE_C: 'Live model · Mode C',
  RELEASE: 'Release comparison',
};

export function modeLabel(mode: string): string {
  return MODE_LABELS[mode] || mode;
}

const BTN_VARIANTS = {
  primary: 'bg-zinc-900 text-white hover:bg-zinc-700 disabled:hover:bg-zinc-900',
  secondary: 'border border-stone-300 bg-white text-zinc-700 hover:bg-stone-50',
  danger: 'border border-rose-200 bg-white text-rose-700 hover:bg-rose-50',
} as const;

const BTN_SIZES = { sm: 'h-8 px-3 text-xs', md: 'h-9 px-4 text-sm' } as const;

export function Btn({ children, onClick, variant = 'primary', size = 'sm', disabled = false, type = 'button' }: {
  children: ReactNode;
  onClick?: () => void;
  variant?: keyof typeof BTN_VARIANTS;
  size?: keyof typeof BTN_SIZES;
  disabled?: boolean;
  type?: 'button' | 'submit';
}) {
  return (
    <button
      type={type}
      disabled={disabled}
      onClick={onClick}
      className={`inline-flex shrink-0 items-center justify-center gap-1 rounded-lg font-medium transition disabled:cursor-not-allowed disabled:opacity-45 ${BTN_VARIANTS[variant]} ${BTN_SIZES[size]}`}
    >
      {children}
    </button>
  );
}

export const inputCls = 'w-full rounded-lg border border-stone-200 bg-white px-2.5 py-2 text-sm text-zinc-800 placeholder:text-zinc-400 focus:border-zinc-400 focus:outline-none disabled:opacity-50';

export function Field({ label, children, className = '' }: { label: string; children: ReactNode; className?: string }) {
  return (
    <label className={`block text-xs font-medium text-zinc-500 ${className}`}>
      {label}
      <span className="mt-1 block font-normal">{children}</span>
    </label>
  );
}

export function ErrorNote({ message }: { message: string | null }) {
  if (!message) return null;
  return <p className="mb-4 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{message}</p>;
}

export function EmptyState({ title, hint, action }: { title: string; hint: string; action?: ReactNode }) {
  return (
    <div className="rounded-xl border border-dashed border-stone-300 bg-stone-50 px-5 py-12 text-center">
      <p className="text-sm font-medium text-zinc-700">{title}</p>
      <p className="mx-auto mt-1 max-w-lg text-xs leading-5 text-zinc-500">{hint}</p>
      {action && <div className="mt-4 flex justify-center">{action}</div>}
    </div>
  );
}

export function MetricTile({ label, value, hint }: { label: string; value: ReactNode; hint?: string }) {
  return (
    <div className="rounded-xl border border-stone-200 bg-white px-4 py-3 shadow-sm">
      <p className="text-xs text-zinc-500">{label}</p>
      <p className="mt-1 font-display text-xl font-semibold text-zinc-900">{value}</p>
      {hint && <p className="mt-0.5 text-[11px] text-zinc-400">{hint}</p>}
    </div>
  );
}
