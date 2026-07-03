'use client';

import { useState } from 'react';
import Link from 'next/link';

import { agentApi } from '@/api/agent';
import {
  hasPasswordResetRequestErrors,
  validatePasswordResetRequest,
  type PasswordResetRequestErrors,
} from '@/utils/reset-password-form';

type Phase = 'idle' | 'submitting' | 'submitted';

export default function ResetPasswordRequest() {
  const [email, setEmail] = useState('');
  const [errors, setErrors] = useState<PasswordResetRequestErrors>({});
  const [phase, setPhase] = useState<Phase>('idle');
  const [serverError, setServerError] = useState('');

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setServerError('');
    const validation = validatePasswordResetRequest(email);
    setErrors(validation);
    if (hasPasswordResetRequestErrors(validation)) return;

    setPhase('submitting');
    try {
      await agentApi.requestPasswordReset({ email: email.trim() });
      setPhase('submitted');
    } catch (err: unknown) {
      setPhase('idle');
      setServerError(err instanceof Error ? err.message : 'Could not request a reset email.');
    }
  };

  if (phase === 'submitted') {
    return (
      <main className="app-page theme-bg-gradient flex items-center justify-center p-6">
        <section className="codex-card flex w-full max-w-[480px] flex-col gap-3 p-6">
          <h1 className="m-0 text-xl font-semibold text-zinc-800">Check your email</h1>
          <p className="m-0 text-sm leading-6 text-zinc-600">
            If an active account exists for <b>{email}</b>, a password-reset link has been sent.
            It expires in 30 minutes.
          </p>
          <div className="flex gap-3 mt-2">
            <button
              type="button"
              onClick={() => setPhase('idle')}
              className="theme-btn-secondary rounded-lg px-4 py-2.5 text-sm font-semibold"
            >
              Send another
            </button>
            <Link href="/login" className="codex-link self-center text-sm">
              Back to login
            </Link>
          </div>
        </section>
      </main>
    );
  }

  return (
    <main className="app-page theme-bg-gradient flex items-center justify-center p-6">
      <section className="codex-card flex w-full max-w-[480px] flex-col gap-3 p-6">
        <h1 className="m-0 text-xl font-semibold text-zinc-800">Reset your password</h1>
        <p className="m-0 text-xs text-zinc-500">
          Enter your verified account email and we&apos;ll send a reset link.
        </p>

        <form onSubmit={handleSubmit} className="flex flex-col gap-3 mt-2">
          <label className="flex flex-col gap-2 text-xs font-medium text-zinc-600">
            Email
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              autoComplete="email"
              className="theme-input rounded-lg p-3 text-sm font-normal"
            />
            {errors.email && <span className="text-xs font-normal text-rose-700">{errors.email}</span>}
          </label>

          <button
            type="submit"
            disabled={phase === 'submitting'}
            className="theme-btn mt-2 rounded-lg px-4 py-2.5 text-sm font-semibold disabled:opacity-60"
          >
            {phase === 'submitting' ? 'Sending...' : 'Send reset link'}
          </button>

          {serverError && (
            <p className="m-0 text-xs text-rose-700">{serverError}</p>
          )}

          <div className="mt-2 text-xs text-zinc-500">
            Remembered your password?{' '}
            <Link href="/login" className="codex-link">Sign in</Link>
          </div>
        </form>
      </section>
    </main>
  );
}
