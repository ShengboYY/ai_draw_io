'use client';

import { useState } from 'react';
import Link from 'next/link';

import { agentApi } from '@/api/agent';
import {
  hasErrors,
  validateRegisterForm,
  type RegisterFormErrors,
} from '@/utils/register-form';

type Phase = 'idle' | 'submitting' | 'submitted';

export default function Register() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [errors, setErrors] = useState<RegisterFormErrors>({});
  const [phase, setPhase] = useState<Phase>('idle');
  const [serverError, setServerError] = useState<string>('');

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setServerError('');
    const validation = validateRegisterForm(email, password, confirm);
    setErrors(validation);
    if (hasErrors(validation)) return;

    setPhase('submitting');
    try {
      await agentApi.registerAccount({ email: email.trim(), password });
      setPhase('submitted');
    } catch (err: unknown) {
      setPhase('idle');
      setServerError(err instanceof Error ? err.message : 'Registration failed.');
    }
  };

  const handleResend = async () => {
    setServerError('');
    try {
      await agentApi.resendVerification({ email: email.trim() });
    } catch (err: unknown) {
      setServerError(err instanceof Error ? err.message : 'Could not request a new email.');
    }
  };

  if (phase === 'submitted') {
    return (
      <main className="min-h-screen flex justify-center items-center p-7 theme-bg-gradient">
        <section className="theme-card rounded-[16px] p-6 w-full max-w-[480px] flex flex-col gap-3">
          <h1 className="m-0 text-xl font-bold text-[rgba(255,255,255,0.92)]">Check your email</h1>
          <p className="m-0 text-sm text-[rgba(255,255,255,0.72)] leading-[1.6]">
            If <b>{email}</b> is available, we sent a verification link. It expires in 30 minutes.
            The same message shows for every submission so we never reveal which addresses exist.
          </p>
          <div className="flex gap-3 mt-2">
            <button
              type="button"
              onClick={handleResend}
              className="theme-btn-secondary rounded-[12px] p-[10px_14px] font-semibold text-sm"
            >
              Resend verification email
            </button>
            <Link href="/login" className="text-sm text-[rgba(255,255,255,0.72)] self-center underline">
              Back to login
            </Link>
          </div>
          {serverError && (
            <p className="m-0 text-xs text-[#ff5a7a]">{serverError}</p>
          )}
        </section>
      </main>
    );
  }

  return (
    <main className="min-h-screen flex justify-center items-center p-7 theme-bg-gradient">
      <section className="theme-card rounded-[16px] p-6 w-full max-w-[480px] flex flex-col gap-3">
        <h1 className="m-0 text-xl font-bold text-[rgba(255,255,255,0.92)]">Create your account</h1>
        <p className="m-0 text-xs text-[rgba(255,255,255,0.56)]">
          You&apos;ll receive a verification link before you can sign in.
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

          <label className="flex flex-col gap-2 text-xs text-[rgba(255,255,255,0.72)]">
            Password
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

          <button
            type="submit"
            disabled={phase === 'submitting'}
            className="theme-btn rounded-[12px] p-[11px_14px] font-bold text-sm disabled:opacity-60 mt-2"
          >
            {phase === 'submitting' ? 'Creating account…' : 'Create account'}
          </button>

          {serverError && (
            <p className="m-0 text-xs text-[#ff5a7a]">{serverError}</p>
          )}

          <div className="text-xs text-[rgba(255,255,255,0.56)] mt-2">
            Already have an account?{' '}
            <Link href="/login" className="underline text-[rgba(255,255,255,0.72)]">Sign in</Link>
          </div>
        </form>
      </section>
    </main>
  );
}
