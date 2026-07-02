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
    <main className="min-h-screen flex justify-center items-center p-7 theme-bg-gradient">
      <section className="theme-card rounded-[16px] p-6 w-full max-w-[480px] flex flex-col gap-3">
        <h1 className="m-0 text-xl font-bold text-[rgba(255,255,255,0.92)]">Loading reset link...</h1>
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
      <main className="min-h-screen flex justify-center items-center p-7 theme-bg-gradient">
        <section className="theme-card rounded-[16px] p-6 w-full max-w-[480px] flex flex-col gap-3">
          <h1
            className={`m-0 text-xl font-bold ${
              display.variant === 'success'
                ? 'text-[#62f6c7]'
                : display.variant === 'warning'
                  ? 'text-[#ffb85a]'
                  : 'text-[#ff5a7a]'
            }`}
          >
            {display.title}
          </h1>
          <p className="m-0 text-sm text-[rgba(255,255,255,0.72)] leading-[1.6]">
            {display.message}
          </p>
          <div className="flex gap-3 mt-2">
            {display.canSignIn && (
              <Link
                href="/login"
                className="theme-btn rounded-[12px] p-[10px_14px] font-bold text-sm text-center"
              >
                Go to sign in
              </Link>
            )}
            {display.canRequestNew && (
              <Link href="/reset-password" className="text-sm text-[rgba(255,255,255,0.72)] self-center underline">
                Request new link
              </Link>
            )}
          </div>
        </section>
      </main>
    );
  }

  return (
    <main className="min-h-screen flex justify-center items-center p-7 theme-bg-gradient">
      <section className="theme-card rounded-[16px] p-6 w-full max-w-[480px] flex flex-col gap-3">
        <h1 className="m-0 text-xl font-bold text-[rgba(255,255,255,0.92)]">Set a new password</h1>
        <p className="m-0 text-xs text-[rgba(255,255,255,0.56)]">
          This reset link can be used once and expires after 30 minutes.
        </p>

        <form onSubmit={handleSubmit} className="flex flex-col gap-3 mt-2">
          <label className="flex flex-col gap-2 text-xs text-[rgba(255,255,255,0.72)]">
            New password
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="new-password"
              className="rounded-[12px] theme-input p-3 outline-none text-sm"
            />
            {errors.password && <span className="text-[#ff5a7a] text-xs">{errors.password}</span>}
          </label>

          <label className="flex flex-col gap-2 text-xs text-[rgba(255,255,255,0.72)]">
            Confirm password
            <input
              type="password"
              value={confirm}
              onChange={(e) => setConfirm(e.target.value)}
              autoComplete="new-password"
              className="rounded-[12px] theme-input p-3 outline-none text-sm"
            />
            {errors.confirm && <span className="text-[#ff5a7a] text-xs">{errors.confirm}</span>}
          </label>

          {errors.token && <p className="m-0 text-xs text-[#ff5a7a]">{errors.token}</p>}

          <button
            type="submit"
            disabled={phase === 'submitting'}
            className="theme-btn rounded-[12px] p-[11px_14px] font-bold text-sm disabled:opacity-60 mt-2"
          >
            {phase === 'submitting' ? 'Updating...' : 'Update password'}
          </button>

          {serverError && (
            <p className="m-0 text-xs text-[#ff5a7a]">{serverError}</p>
          )}
        </form>
      </section>
    </main>
  );
}
