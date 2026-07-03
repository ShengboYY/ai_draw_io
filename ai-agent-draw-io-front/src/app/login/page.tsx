'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';

import { agentApi } from '@/api/agent';
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
    <div className="app-page theme-bg-gradient flex items-center justify-center p-6">
      <div className="grid w-full max-w-[1040px] grid-cols-1 gap-4 lg:grid-cols-[1.1fr_0.9fr]">
        <section className="codex-card relative flex flex-col gap-5 overflow-hidden p-7">
          <div className="flex items-center gap-3">
            <div className="grid h-11 w-11 place-items-center rounded-lg bg-zinc-700 text-lg font-bold text-white shadow-sm">
              FD
            </div>
            <div className="flex flex-col gap-1">
              <strong className="text-base leading-[1.1] text-zinc-800">
                free draw</strong>
              <span className="text-xs text-zinc-500">Sketch freely · Shape with AI · Save your diagrams</span>
            </div>
          </div>

          <h1 className="mt-2 max-w-[14ch] text-[32px] font-semibold leading-[1.12] tracking-normal text-zinc-800">
            Draw diagrams freely with AI
          </h1>
          <p className="m-0 max-w-[52ch] text-sm leading-7 text-zinc-600">
            Sign in to keep your free draw workspace, diagrams, and AI conversations available on this
            browser. You can sign out any time.
          </p>
        </section>

        <section className="flex flex-col justify-center gap-4">
          <div className="codex-card p-5">
            <h2 className="m-0 mb-1.5 text-lg font-semibold text-zinc-800">Sign in to free draw</h2>
            <p className="m-0 mb-5 text-xs leading-5 text-zinc-500">
              Use your registered email to save and continue your diagrams.
            </p>

            {!signedInAs ? (
              <form onSubmit={handleLogin} autoComplete="on">
                <div className="flex flex-col gap-2 mb-3">
                  <label htmlFor="email" className="text-xs font-medium text-zinc-600">Email</label>
                  <input
                    id="email"
                    type="email"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    placeholder="you@example.com"
                    autoComplete="email"
                    className="theme-input w-full rounded-lg p-3 text-sm transition-all duration-150"
                  />
                  {errors.email && <span className="text-xs text-rose-700">{errors.email}</span>}
                </div>

                <div className="flex flex-col gap-2 mb-3">
                  <label htmlFor="password" className="text-xs font-medium text-zinc-600">Password</label>
                  <input
                    id="password"
                    type="password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    placeholder="Your password"
                    autoComplete="current-password"
                    className="theme-input w-full rounded-lg p-3 text-sm transition-all duration-150"
                  />
                  {errors.password && <span className="text-xs text-rose-700">{errors.password}</span>}
                </div>

                <div className="flex gap-[10px] items-center justify-between mt-[6px]">
                  <button
                    type="submit"
                    disabled={phase === 'submitting'}
                    className="theme-btn cursor-pointer rounded-lg border-0 px-4 py-2.5 text-sm font-semibold transition-transform active:translate-y-px disabled:opacity-60"
                  >
                    {phase === 'submitting' ? 'Signing in…' : 'Sign in'}
                  </button>
                  <Link href="/reset-password" className="codex-link text-xs">
                    Forgot password?
                  </Link>
                </div>
              </form>
            ) : (
              <div className="mt-3 flex items-center justify-between gap-3 rounded-lg border border-dashed border-stone-300 bg-stone-50 p-3">
                <div>
                  <strong className="block text-[13px] text-zinc-800">Signed in as {signedInAs}</strong>
                  <span className="mt-0.5 block text-xs text-zinc-500">free draw session active</span>
                </div>
                <button onClick={handleLogout} className="theme-btn-secondary cursor-pointer rounded-lg px-3 py-2 text-xs font-semibold">
                  Sign out
                </button>
              </div>
            )}

            {statusDisplay && (
              <div className={`min-h-[18px] text-xs mt-3 ${
                statusDisplay.variant === 'error'
                  ? 'text-rose-700'
                  : statusDisplay.variant === 'warning'
                    ? 'text-amber-700'
                    : 'text-zinc-600'
              }`}>
                {statusDisplay.message}
                {statusDisplay.offerResend && (
                  <button
                    type="button"
                    onClick={handleResend}
                    className="ml-2 underline text-zinc-700"
                  >
                    Resend verification email
                  </button>
                )}
              </div>
            )}
            {serverError && (
              <div className="mt-2 text-xs text-rose-700">{serverError}</div>
            )}
          </div>

          <div className="text-center text-xs text-zinc-500">
            New to free draw?{' '}
            <Link href="/register" className="codex-link">Create one</Link>
          </div>

          <div className="mt-2 text-center text-xs text-zinc-400">
            © free draw
          </div>
        </section>
      </div>
    </div>
  );
}
