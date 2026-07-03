'use client';

import { Suspense, useMemo, useState } from 'react';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';

import { agentApi } from '@/api/agent';
import type { PasswordResetStatus } from '@/types/api';
import {
  hasPasswordResetConfirmErrors,
  passwordResetStatusDisplay,
  validatePasswordResetConfirm,
  type PasswordResetConfirmErrors,
} from '@/utils/reset-password-form';

type Phase = 'editing' | 'submitting' | 'done';

export default function ResetPasswordConfirmPage() {
  return (
    <Suspense fallback={<ResetPasswordConfirmLoading />}>
      <ResetPasswordConfirm />
    </Suspense>
  );
}

function ResetPasswordConfirmLoading() {
  return (
    <main className="app-page theme-bg-gradient flex items-center justify-center p-6">
      <section className="codex-card flex w-full max-w-[480px] flex-col gap-3 p-6">
        <h1 className="m-0 text-xl font-semibold text-zinc-800">Loading reset link...</h1>
      </section>
    </main>
  );
}

function ResetPasswordConfirm() {
  const searchParams = useSearchParams();
  const token = searchParams.get('token') ?? '';
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [errors, setErrors] = useState<PasswordResetConfirmErrors>({});
  const [status, setStatus] = useState<PasswordResetStatus | null>(token ? null : 'INVALID');
  const [phase, setPhase] = useState<Phase>(token ? 'editing' : 'done');
  const [serverError, setServerError] = useState('');

  const display = useMemo(() => passwordResetStatusDisplay(status), [status]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setServerError('');
    const validation = validatePasswordResetConfirm(token, password, confirm);
    setErrors(validation);
    if (hasPasswordResetConfirmErrors(validation)) return;

    setPhase('submitting');
    try {
      const res = await agentApi.confirmPasswordReset({ token, password });
      setStatus(res.data?.status ?? 'INVALID');
      setPhase('done');
    } catch (err: unknown) {
      setPhase('editing');
      setServerError(err instanceof Error ? err.message : 'Could not reset password.');
    }
  };

  if (phase === 'done') {
    return (
      <main className="app-page theme-bg-gradient flex items-center justify-center p-6">
        <section className="codex-card flex w-full max-w-[480px] flex-col gap-3 p-6">
          <h1
            className={`m-0 text-xl font-semibold ${
              display.variant === 'success'
                ? 'text-emerald-700'
                : display.variant === 'warning'
                  ? 'text-amber-700'
                  : 'text-rose-700'
            }`}
          >
            {display.title}
          </h1>
          <p className="m-0 text-sm leading-6 text-zinc-600">
            {display.message}
          </p>
          <div className="flex gap-3 mt-2">
            {display.canSignIn && (
              <Link
                href="/login"
                className="theme-btn rounded-lg px-4 py-2.5 text-center text-sm font-semibold"
              >
                Go to sign in
              </Link>
            )}
            {display.canRequestNew && (
              <Link href="/reset-password" className="codex-link self-center text-sm">
                Request new link
              </Link>
            )}
          </div>
        </section>
      </main>
    );
  }

  return (
    <main className="app-page theme-bg-gradient flex items-center justify-center p-6">
      <section className="codex-card flex w-full max-w-[480px] flex-col gap-3 p-6">
        <h1 className="m-0 text-xl font-semibold text-zinc-800">Set a new password</h1>
        <p className="m-0 text-xs text-zinc-500">
          This reset link can be used once and expires after 30 minutes.
        </p>

        <form onSubmit={handleSubmit} className="flex flex-col gap-3 mt-2">
          <label className="flex flex-col gap-2 text-xs font-medium text-zinc-600">
            New password
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="new-password"
              className="theme-input rounded-lg p-3 text-sm font-normal"
            />
            {errors.password && <span className="text-xs font-normal text-rose-700">{errors.password}</span>}
          </label>

          <label className="flex flex-col gap-2 text-xs font-medium text-zinc-600">
            Confirm password
            <input
              type="password"
              value={confirm}
              onChange={(e) => setConfirm(e.target.value)}
              autoComplete="new-password"
              className="theme-input rounded-lg p-3 text-sm font-normal"
            />
            {errors.confirm && <span className="text-xs font-normal text-rose-700">{errors.confirm}</span>}
          </label>

          {errors.token && <p className="m-0 text-xs text-rose-700">{errors.token}</p>}

          <button
            type="submit"
            disabled={phase === 'submitting'}
            className="theme-btn mt-2 rounded-lg px-4 py-2.5 text-sm font-semibold disabled:opacity-60"
          >
            {phase === 'submitting' ? 'Updating...' : 'Update password'}
          </button>

          {serverError && (
            <p className="m-0 text-xs text-rose-700">{serverError}</p>
          )}
        </form>
      </section>
    </main>
  );
}
