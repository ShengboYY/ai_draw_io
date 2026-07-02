'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';

import { agentApi } from '@/api/agent';
import type { LoginStatus } from '@/types/api';
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

  useEffect(() => {
    // Recover an existing session so a returning user does not have to sign in twice.
    let cancelled = false;
    agentApi.me().then(({ data }) => {
      if (cancelled) return;
      if (data.status === 'SUCCESS' && data.email) {
        setSignedInAs(data.email);
        setUserInfo(data.email);
        router.push('/drawio');
      }
    }).catch(() => {
      // Silent — treat as no session; nothing to persist.
    });
    return () => { cancelled = true; };
  }, [router]);

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
        setTimeout(() => router.push('/drawio'), 400);
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
    <div className="min-h-screen flex justify-center items-stretch p-7 theme-bg-gradient">
      <div className="w-full max-w-[1120px] grid grid-cols-1 lg:grid-cols-[1.25fr_0.75fr] gap-[18px]">
        <section className="theme-card rounded-[18px] overflow-hidden relative flex flex-col gap-[18px] p-[28px_28px_22px_28px]">
          <div className="flex items-center gap-3">
            <div className="w-11 h-11 rounded-[14px] grid place-items-center bg-gradient-to-br from-[#62f6c7] to-[#5aa9ff] shadow-[0_10px_24px_rgba(0,0,0,0.4)] text-[rgba(7,10,18,0.92)] font-extrabold text-lg tracking-[0.5px]">
              AI
            </div>
            <div className="flex flex-col gap-1">
              <strong className="text-base leading-[1.1] tracking-[0.2px] text-[rgba(255,255,255,0.92)]">
                AI Agent Workspace</strong>
              <span className="text-xs text-[rgba(255,255,255,0.56)]">Build faster · Run reliably · Operate clearly</span>
            </div>
          </div>

          <h1 className="mt-[6px] text-[30px] leading-[1.2] tracking-[0.2px] text-[rgba(255,255,255,0.92)] font-bold">
            An AI workspace for getting diagrams done
          </h1>
          <p className="m-0 text-[rgba(255,255,255,0.72)] leading-[1.7] max-w-[52ch] text-sm">
            Sign in with the email and password from your verified account. Sessions last seven days on
            this browser; you can sign out any time.
          </p>
        </section>

        <section className="p-[28px] flex flex-col justify-center gap-[14px]">
          <div className="theme-card rounded-[16px] p-5">
            <h2 className="m-0 mb-[6px] text-[18px] text-[rgba(255,255,255,0.92)] font-bold">Sign in</h2>
            <p className="m-0 mb-4 text-[rgba(255,255,255,0.56)] text-xs leading-[1.5]">
              Use your registered email. New here? Create an account below.
            </p>

            {!signedInAs ? (
              <form onSubmit={handleLogin} autoComplete="on">
                <div className="flex flex-col gap-2 mb-3">
                  <label htmlFor="email" className="text-xs text-[rgba(255,255,255,0.72)] tracking-[0.2px]">Email</label>
                  <input
                    id="email"
                    type="email"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    placeholder="you@example.com"
                    autoComplete="email"
                    className="w-full rounded-[12px] theme-input p-3 outline-none transition-all duration-180 text-sm"
                  />
                  {errors.email && <span className="text-[#ff5a7a] text-xs">{errors.email}</span>}
                </div>

                <div className="flex flex-col gap-2 mb-3">
                  <label htmlFor="password" className="text-xs text-[rgba(255,255,255,0.72)] tracking-[0.2px]">Password</label>
                  <input
                    id="password"
                    type="password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    placeholder="Your password"
                    autoComplete="current-password"
                    className="w-full rounded-[12px] theme-input p-3 outline-none transition-all duration-180 text-sm"
                  />
                  {errors.password && <span className="text-[#ff5a7a] text-xs">{errors.password}</span>}
                </div>

                <div className="flex gap-[10px] items-center justify-between mt-[6px]">
                  <button
                    type="submit"
                    disabled={phase === 'submitting'}
                    className="theme-btn rounded-[12px] p-[11px_14px] font-bold cursor-pointer border-0 transition-transform active:translate-y-[1px] active:brightness-[0.98] text-sm disabled:opacity-60"
                  >
                    {phase === 'submitting' ? 'Signing in…' : 'Sign in'}
                  </button>
                </div>
              </form>
            ) : (
              <div className="flex gap-[10px] items-center justify-between p-3 border border-dashed border-[rgba(255,255,255,0.18)] rounded-[12px] bg-[rgba(255,255,255,0.04)] mt-3">
                <div>
                  <strong className="block text-[13px] text-[rgba(255,255,255,0.92)]">Signed in as {signedInAs}</strong>
                  <span className="block text-xs text-[rgba(255,255,255,0.56)] mt-[2px]">Session cookie active</span>
                </div>
                <button onClick={handleLogout} className="theme-btn-secondary rounded-[12px] p-[8px_12px] font-semibold cursor-pointer text-xs">
                  Sign out
                </button>
              </div>
            )}

            {statusDisplay && (
              <div className={`min-h-[18px] text-xs mt-3 ${
                statusDisplay.variant === 'error'
                  ? 'text-[#ff5a7a]'
                  : statusDisplay.variant === 'warning'
                    ? 'text-[#ffb454]'
                    : 'text-[rgba(255,255,255,0.72)]'
              }`}>
                {statusDisplay.message}
                {statusDisplay.offerResend && (
                  <button
                    type="button"
                    onClick={handleResend}
                    className="ml-2 underline text-[rgba(255,255,255,0.72)]"
                  >
                    Resend verification email
                  </button>
                )}
              </div>
            )}
            {serverError && (
              <div className="text-xs text-[#ff5a7a] mt-2">{serverError}</div>
            )}
          </div>

          <div className="text-xs text-[rgba(255,255,255,0.56)] text-center">
            No account yet?{' '}
            <Link href="/register" className="underline text-[rgba(255,255,255,0.72)]">Create one</Link>
          </div>

          <div className="mt-[14px] text-[rgba(255,255,255,0.35)] text-xs text-center">
            © AI Draw.io Builder · Next.js demo page
          </div>
        </section>
      </div>
    </div>
  );
}
