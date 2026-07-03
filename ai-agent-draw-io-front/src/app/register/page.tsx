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
      <main className="app-page theme-bg-gradient flex items-center justify-center p-6">
        <section className="codex-card flex w-full max-w-[480px] flex-col gap-3 p-6">
          <h1 className="m-0 text-xl font-semibold text-zinc-800">Check your email</h1>
          <p className="m-0 text-sm leading-6 text-zinc-600">
            If <b>{email}</b> is available, we sent a verification link. It expires in 30 minutes.
            The same message shows for every submission so we never reveal which addresses exist.
          </p>
          <div className="flex gap-3 mt-2">
            <button
              type="button"
              onClick={handleResend}
              className="theme-btn-secondary rounded-lg px-4 py-2.5 text-sm font-semibold"
            >
              Resend verification email
            </button>
            <Link href="/login" className="codex-link self-center text-sm">
              Back to login
            </Link>
          </div>
          {serverError && (
            <p className="m-0 text-xs text-rose-700">{serverError}</p>
          )}
        </section>
      </main>
    );
  }

  return (
    <main className="app-page theme-bg-gradient flex items-center justify-center p-6">
      <section className="codex-card flex w-full max-w-[480px] flex-col gap-3 p-6">
        <h1 className="m-0 text-xl font-semibold text-zinc-800">Create your account</h1>
        <p className="m-0 text-xs text-zinc-500">
          You&apos;ll receive a verification link before you can sign in.
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

          <label className="flex flex-col gap-2 text-xs font-medium text-zinc-600">
            Password
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

          <button
            type="submit"
            disabled={phase === 'submitting'}
            className="theme-btn mt-2 rounded-lg px-4 py-2.5 text-sm font-semibold disabled:opacity-60"
          >
            {phase === 'submitting' ? 'Creating account…' : 'Create account'}
          </button>

          {serverError && (
            <p className="m-0 text-xs text-rose-700">{serverError}</p>
          )}

          <div className="mt-2 text-xs text-zinc-500">
            Already have an account?{' '}
            <Link href="/login" className="codex-link">Sign in</Link>
          </div>
        </form>
      </section>
    </main>
  );
}
