'use client';

import { useState } from 'react';
import Link from 'next/link';

import { agentApi } from '@/api/agent';
import { AuthBrandMark, LockIcon } from '@/app/auth-visuals';
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
      <main className="app-page flex items-center justify-center bg-[#faf9f7] px-4 py-10 text-[#202024]">
        <section className="flex w-full max-w-[420px] flex-col items-center">
          <AuthBrandMark className="h-12 w-12 rounded-xl" iconClassName="h-6 w-6" />
          <div className="mt-6 w-full rounded-2xl border border-stone-200 bg-white px-6 py-7 shadow-[0_18px_44px_rgba(24,24,27,0.08)] sm:px-8">
            <h1 className="m-0 text-2xl font-semibold tracking-tight text-[#202024]">Check your email</h1>
            <p className="m-0 mt-3 text-sm leading-relaxed text-[#6f6b65]">
              If <b>{email}</b> is available, we sent a verification link. It expires in 30 minutes.
              The same message shows for every submission so we never reveal which addresses exist.
            </p>
            <div className="mt-5 flex flex-col gap-3 sm:flex-row sm:items-center">
              <button
                type="button"
                onClick={handleResend}
                className="rounded-lg border border-stone-300 bg-white px-3.5 py-2 text-sm font-semibold text-[#34333d] transition hover:bg-stone-50"
              >
                Resend verification email
              </button>
              <Link href="/login" className="text-sm font-semibold text-[#34333d] transition hover:text-[#55525f]">
                Back to login
              </Link>
            </div>
            {serverError && (
              <p className="m-0 mt-4 text-sm text-rose-700">{serverError}</p>
            )}
          </div>
        </section>
      </main>
    );
  }

  return (
    <main className="app-page flex items-center justify-center bg-[#faf9f7] px-4 py-10 text-[#202024]">
      <section className="flex w-full max-w-[420px] flex-col items-center">
        <AuthBrandMark className="h-12 w-12 rounded-xl" iconClassName="h-6 w-6" />
        <h1 className="m-0 mt-5 text-center text-2xl font-semibold leading-tight tracking-tight text-[#202024]">
          Create your account
        </h1>
        <p className="m-0 mt-1.5 text-center text-sm text-[#8c8983]">
          Free to start - no card required.
        </p>

        <div className="mt-6 w-full rounded-2xl border border-stone-200 bg-white px-6 py-7 shadow-[0_18px_44px_rgba(24,24,27,0.08)] sm:px-8">
          <form onSubmit={handleSubmit} className="flex flex-col gap-4">
            <label className="flex flex-col gap-1.5 text-sm font-medium text-[#4c4a50]">
              Email
              <input
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="you@company.com"
                autoComplete="email"
                className="h-11 w-full rounded-xl border border-[#e1dfdc] bg-white px-4 text-sm font-normal text-[#39373d] outline-none transition placeholder:text-[#85817b] focus:border-[#34333d] focus:ring-4 focus:ring-[#34333d]/10"
              />
              {errors.email && <span className="text-xs font-normal text-rose-700">{errors.email}</span>}
            </label>

            <label className="flex flex-col gap-1.5 text-sm font-medium text-[#4c4a50]">
              Password
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="At least 8 characters"
                autoComplete="new-password"
                className="h-11 w-full rounded-xl border border-[#e1dfdc] bg-white px-4 text-sm font-normal text-[#39373d] outline-none transition placeholder:text-[#85817b] focus:border-[#34333d] focus:ring-4 focus:ring-[#34333d]/10"
              />
              {errors.password && <span className="text-xs font-normal text-rose-700">{errors.password}</span>}
            </label>

            <label className="flex flex-col gap-1.5 text-sm font-medium text-[#4c4a50]">
              Confirm password
              <input
                type="password"
                value={confirm}
                onChange={(e) => setConfirm(e.target.value)}
                placeholder="Re-enter your password"
                autoComplete="new-password"
                className="h-11 w-full rounded-xl border border-[#e1dfdc] bg-white px-4 text-sm font-normal text-[#39373d] outline-none transition placeholder:text-[#85817b] focus:border-[#34333d] focus:ring-4 focus:ring-[#34333d]/10"
              />
              {errors.confirm && <span className="text-xs font-normal text-rose-700">{errors.confirm}</span>}
            </label>

            <button
              type="submit"
              disabled={phase === 'submitting'}
              className="mt-1 h-11 cursor-pointer rounded-xl bg-[#34333d] px-5 text-sm font-semibold text-white shadow-[0_10px_18px_rgba(52,51,61,0.16)] transition hover:bg-[#474553] active:translate-y-px disabled:cursor-not-allowed disabled:opacity-60"
            >
              {phase === 'submitting' ? 'Creating account...' : 'Create account'}
            </button>

            {serverError && (
              <p className="m-0 text-sm text-rose-700">{serverError}</p>
            )}

            <p className="m-0 flex items-center gap-2 text-xs leading-5 text-[#9b968f]">
              <LockIcon className="h-4 w-4 shrink-0 text-[#aaa79f]" />
              You&apos;ll get a verification link before signing in.
            </p>
          </form>
        </div>

        <div className="mt-6 text-center text-sm text-[#8c8983]">
          Already have an account?{' '}
          <Link href="/login" className="font-semibold text-[#34333d] transition hover:text-[#55525f]">Sign in</Link>
        </div>
      </section>
    </main>
  );
}
