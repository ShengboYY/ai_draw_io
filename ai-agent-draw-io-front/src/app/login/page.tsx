'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';

import { agentApi } from '@/api/agent';
import { AuthBrandMark, EnvelopeIcon, LockIcon } from '@/app/auth-visuals';
import type { LoginStatus } from '@/types/api';
import {
  clearImportedAnonymousWorkspace,
  shouldPromptAnonymousWorkspaceImport,
} from '@/utils/anonymous-workspace-import';
import { setUserInfo, clearUserInfo } from '@/utils/cookie';
import {
  hasLoginErrors,
  loginStatusDisplay,
  validateLoginForm,
  type LoginFormErrors,
} from '@/utils/login-form';

type Phase = 'idle' | 'submitting';

export default function Login() {
  const router = useRouter();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [errors, setErrors] = useState<LoginFormErrors>({});
  const [status, setStatus] = useState<LoginStatus | null>(null);
  const [signedInAs, setSignedInAs] = useState<string | null>(null);
  const [phase, setPhase] = useState<Phase>('idle');
  const [serverError, setServerError] = useState('');

  const maybeImportAnonymousWorkspace = useCallback(async () => {
    const storage = typeof window === 'undefined' ? null : window.localStorage;
    const decision = shouldPromptAnonymousWorkspaceImport({
      loginStatus: 'SUCCESS',
      storage,
    });
    if (!decision.shouldPrompt) return;

    const confirmed = window.confirm(
      'Import diagrams saved in this browser into your signed-in account?',
    );
    if (!confirmed) return;

    try {
      await agentApi.importAnonymousWorkspace({
        anonymousWorkspaceId: decision.anonymousWorkspaceId,
      });
      clearImportedAnonymousWorkspace(storage, decision.anonymousWorkspaceId);
    } catch (err: unknown) {
      // The user is already signed in, so never strand them on the login page.
      // The import marker stays in localStorage, so the next sign-in prompts again.
      const message = err instanceof Error ? err.message : 'Could not import diagrams from this browser.';
      window.alert(`${message} Your local diagrams stay in this browser; sign in again later to retry the import.`);
    }
  }, []);

  useEffect(() => {
    // Recover an existing session so a returning user does not have to sign in twice.
    let cancelled = false;
    agentApi.me().then(({ data }) => {
      if (cancelled) return;
      if (data.status === 'SUCCESS' && data.email) {
        const signedInEmail = data.email;
        void (async () => {
          setSignedInAs(signedInEmail);
          setUserInfo(signedInEmail);
          await maybeImportAnonymousWorkspace();
          router.push('/');
        })();
      }
    }).catch(() => {
      // Silent — treat as no session; nothing to persist.
    });
    return () => { cancelled = true; };
  }, [maybeImportAnonymousWorkspace, router]);

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setServerError('');
    const validation = validateLoginForm(email, password);
    setErrors(validation);
    if (hasLoginErrors(validation)) return;

    setPhase('submitting');
    try {
      const { data } = await agentApi.login({ email: email.trim(), password });
      setStatus(data.status);
      if (data.status === 'SUCCESS' && data.email) {
        setUserInfo(data.email);
        setSignedInAs(data.email);
        await maybeImportAnonymousWorkspace();
        setTimeout(() => router.push('/'), 400);
        return;
      }
    } catch (err: unknown) {
      setServerError(err instanceof Error ? err.message : 'Sign-in failed.');
    } finally {
      setPhase('idle');
    }
  };

  const handleLogout = async () => {
    try {
      await agentApi.logout();
    } catch {
      // Best-effort — server-side session may already be gone.
    }
    clearUserInfo();
    setSignedInAs(null);
    setStatus('ANONYMOUS');
  };

  const handleResend = async () => {
    if (!email) return;
    try {
      await agentApi.resendVerification({ email: email.trim() });
      setServerError('Verification email requested. Check your inbox.');
    } catch (err: unknown) {
      setServerError(err instanceof Error ? err.message : 'Could not request a new email.');
    }
  };

  const statusDisplay = status ? loginStatusDisplay(status) : null;

  return (
    <main className="app-page flex items-center justify-center bg-[#faf9f7] px-4 py-8 text-[#202024] sm:px-6">
      <section className="grid w-full max-w-[880px] overflow-hidden rounded-2xl border border-stone-200 bg-white shadow-[0_18px_44px_rgba(24,24,27,0.08)] lg:grid-cols-[1.02fr_1fr]">
        <div className="flex flex-col justify-between gap-10 bg-[#fbfaf8] px-8 py-9 lg:px-10 lg:py-11">
          <div className="flex items-center gap-3">
            <AuthBrandMark className="h-10 w-10 rounded-xl" iconClassName="h-5 w-5" />
            <strong className="font-display text-lg font-semibold tracking-tight text-[#242329]">FreeDraw</strong>
          </div>

          <div className="max-w-[360px]">
            <h1 className="m-0 text-2xl font-semibold leading-[1.15] tracking-tight text-[#202024] sm:text-[28px]">
              The full draw.io editor, with AI built in.
            </h1>
            <p className="m-0 mt-4 text-sm leading-relaxed text-[#85817b]">
              Every draw.io shape and tool you know, plus a copilot that draws and edits for you.
            </p>
          </div>
        </div>

        <div className="flex items-center border-t border-stone-200 px-8 py-9 lg:border-l lg:border-t-0 lg:px-10 lg:py-11">
          <div className="mx-auto w-full max-w-[340px]">
            <h2 className="m-0 text-2xl font-semibold leading-tight tracking-tight text-[#202024]">
              Welcome back
            </h2>
            <p className="m-0 mt-1.5 text-sm text-[#85817b]">
              Sign in to continue your diagrams.
            </p>

            {!signedInAs ? (
              <form onSubmit={handleLogin} autoComplete="on" className="mt-6 flex flex-col gap-4">
                <div className="flex flex-col gap-1.5">
                  <label htmlFor="email" className="text-sm font-medium text-[#4c4a50]">Email</label>
                  <div className="relative">
                    <EnvelopeIcon className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-[#aaa79f]" />
                    <input
                      id="email"
                      type="email"
                      value={email}
                      onChange={(e) => setEmail(e.target.value)}
                      placeholder="you@company.com"
                      autoComplete="email"
                      className="h-11 w-full rounded-xl border border-[#e1dfdc] bg-white pl-10 pr-4 text-sm text-[#39373d] outline-none transition focus:border-[#34333d] focus:ring-4 focus:ring-[#34333d]/10"
                    />
                  </div>
                  {errors.email && <span className="text-xs text-rose-700">{errors.email}</span>}
                </div>

                <div className="flex flex-col gap-1.5">
                  <div className="flex items-center justify-between gap-4">
                    <label htmlFor="password" className="text-sm font-medium text-[#4c4a50]">Password</label>
                    <Link href="/reset-password" className="text-sm font-medium text-[#34333d] transition hover:text-[#55525f]">
                      Forgot?
                    </Link>
                  </div>
                  <div className="relative">
                    <LockIcon className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-[#aaa79f]" />
                    <input
                      id="password"
                      type="password"
                      value={password}
                      onChange={(e) => setPassword(e.target.value)}
                      placeholder="Your password"
                      autoComplete="current-password"
                      className="h-11 w-full rounded-xl border border-[#e1dfdc] bg-white pl-10 pr-4 text-sm text-[#39373d] outline-none transition placeholder:text-[#85817b] focus:border-[#34333d] focus:ring-4 focus:ring-[#34333d]/10"
                    />
                  </div>
                  {errors.password && <span className="text-xs text-rose-700">{errors.password}</span>}
                </div>

                <button
                  type="submit"
                  disabled={phase === 'submitting'}
                  className="mt-1 h-11 cursor-pointer rounded-xl border-0 bg-[#34333d] px-5 text-sm font-semibold text-white shadow-[0_10px_18px_rgba(52,51,61,0.16)] transition hover:bg-[#474553] active:translate-y-px disabled:cursor-not-allowed disabled:opacity-60"
                >
                  {phase === 'submitting' ? 'Signing in...' : 'Sign in'}
                </button>
              </form>
            ) : (
              <div className="mt-6 flex flex-col gap-3 rounded-xl border border-dashed border-stone-300 bg-stone-50 p-4 sm:flex-row sm:items-center sm:justify-between">
                <div>
                  <strong className="block text-sm font-semibold text-[#202024]">Signed in as {signedInAs}</strong>
                  <span className="mt-0.5 block text-xs text-[#85817b]">FreeDraw session active</span>
                </div>
                <button
                  type="button"
                  onClick={handleLogout}
                  className="cursor-pointer rounded-lg border border-stone-300 bg-white px-3.5 py-2 text-sm font-semibold text-[#34333d] transition hover:bg-stone-50"
                >
                  Sign out
                </button>
              </div>
            )}

            {statusDisplay && (
              <div className={`mt-4 min-h-[20px] text-sm ${
                statusDisplay.variant === 'error'
                  ? 'text-rose-700'
                  : statusDisplay.variant === 'warning'
                    ? 'text-amber-700'
                    : 'text-[#6f6b65]'
              }`}>
                {statusDisplay.message}
                {statusDisplay.offerResend && (
                  <button
                    type="button"
                    onClick={handleResend}
                    className="ml-2 font-semibold underline text-[#34333d]"
                  >
                    Resend verification email
                  </button>
                )}
              </div>
            )}
            {serverError && (
              <div className="mt-3 text-sm text-rose-700">{serverError}</div>
            )}

            <div className="mt-6 text-center text-sm text-[#969189]">
              New here?{' '}
              <Link href="/register" className="font-semibold text-[#34333d] transition hover:text-[#55525f]">
                Create an account
              </Link>
            </div>
          </div>
        </div>
      </section>
    </main>
  );
}
