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
      <main className="min-h-screen flex justify-center items-center p-7 theme-bg-gradient">
        <section className="theme-card rounded-[16px] p-6 w-full max-w-[480px] flex flex-col gap-3">
          <h1 className="m-0 text-xl font-bold text-[rgba(255,255,255,0.92)]">Check your email</h1>
          <p className="m-0 text-sm text-[rgba(255,255,255,0.72)] leading-[1.6]">
            If an active account exists for <b>{email}</b>, a password-reset link has been sent.
            It expires in 30 minutes.
          </p>
          <div className="flex gap-3 mt-2">
            <button
              type="button"
              onClick={() => setPhase('idle')}
              className="theme-btn-secondary rounded-[12px] p-[10px_14px] font-semibold text-sm"
            >
              Send another
            </button>
            <Link href="/login" className="text-sm text-[rgba(255,255,255,0.72)] self-center underline">
              Back to login
            </Link>
          </div>
        </section>
      </main>
    );
  }

  return (
    <main className="min-h-screen flex justify-center items-center p-7 theme-bg-gradient">
      <section className="theme-card rounded-[16px] p-6 w-full max-w-[480px] flex flex-col gap-3">
        <h1 className="m-0 text-xl font-bold text-[rgba(255,255,255,0.92)]">Reset your password</h1>
        <p className="m-0 text-xs text-[rgba(255,255,255,0.56)]">
          Enter your verified account email and we&apos;ll send a reset link.
        </p>

        <form onSubmit={handleSubmit} className="flex flex-col gap-3 mt-2">
          <label className="flex flex-col gap-2 text-xs text-[rgba(255,255,255,0.72)]">
            Email
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              autoComplete="email"
              className="rounded-[12px] theme-input p-3 outline-none text-sm"
            />
            {errors.email && <span className="text-[#ff5a7a] text-xs">{errors.email}</span>}
          </label>

          <button
            type="submit"
            disabled={phase === 'submitting'}
            className="theme-btn rounded-[12px] p-[11px_14px] font-bold text-sm disabled:opacity-60 mt-2"
          >
            {phase === 'submitting' ? 'Sending...' : 'Send reset link'}
          </button>

          {serverError && (
            <p className="m-0 text-xs text-[#ff5a7a]">{serverError}</p>
          )}

          <div className="text-xs text-[rgba(255,255,255,0.56)] mt-2">
            Remembered your password?{' '}
            <Link href="/login" className="underline text-[rgba(255,255,255,0.72)]">Sign in</Link>
          </div>
        </form>
      </section>
    </main>
  );
}
