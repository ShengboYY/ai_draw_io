'use client';

import { Suspense, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';

import { agentApi } from '@/api/agent';
import type { EmailVerificationStatus } from '@/types/api';
import { verificationDisplay } from '@/utils/register-form';

type Phase = 'verifying' | 'done' | 'error';

export default function VerifyEmail() {
  return (
    <Suspense fallback={<VerifyEmailLoading />}>
      <VerifyEmailContent />
    </Suspense>
  );
}

function VerifyEmailLoading() {
  return (
    <main className="app-page theme-bg-gradient flex items-center justify-center p-6">
      <section className="codex-card flex w-full max-w-[480px] flex-col gap-3 p-6">
        <h1 className="m-0 text-xl font-semibold text-zinc-800">Verifying...</h1>
      </section>
    </main>
  );
}

function VerifyEmailContent() {
  const searchParams = useSearchParams();
  const token = searchParams.get('token') ?? '';

  const [status, setStatus] = useState<EmailVerificationStatus | null>(null);
  const [phase, setPhase] = useState<Phase>('verifying');
  const [resendEmail, setResendEmail] = useState('');
  const [resendMsg, setResendMsg] = useState('');

  useEffect(() => {
    let cancelled = false;
    (async () => {
      if (!token) {
        if (!cancelled) {
          setStatus('INVALID');
          setPhase('done');
        }
        return;
      }
      try {
        const res = await agentApi.verifyEmail(token);
        if (!cancelled) {
          setStatus(res.data?.status ?? 'INVALID');
          setPhase('done');
        }
      } catch {
        if (!cancelled) {
          setStatus('INVALID');
          setPhase('error');
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [token]);

  const display = useMemo(() => verificationDisplay(status), [status]);

  const handleResend = async () => {
    if (!resendEmail.trim()) return;
    setResendMsg('');
    try {
      await agentApi.resendVerification({ email: resendEmail.trim() });
      setResendMsg('If the email is registered, a new verification link has been sent.');
    } catch {
      setResendMsg('Could not request a new email. Please try again later.');
    }
  };

  return (
    <main className="app-page theme-bg-gradient flex items-center justify-center p-6">
      <section className="codex-card flex w-full max-w-[480px] flex-col gap-3 p-6">
        {phase === 'verifying' ? (
          <>
            <h1 className="m-0 text-xl font-semibold text-zinc-800">Verifying…</h1>
            <p className="m-0 text-sm text-zinc-600">
              Hang tight while we check your verification link.
            </p>
          </>
        ) : (
          <>
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

            {display.variant === 'success' && (
              <Link
                href="/login"
                className="theme-btn mt-2 rounded-lg px-4 py-2.5 text-center text-sm font-semibold"
              >
                Go to sign in
              </Link>
            )}

            {display.canResend && (
              <div className="mt-2 flex flex-col gap-2">
                <label className="text-xs font-medium text-zinc-600">Resend verification email</label>
                <input
                  type="email"
                  value={resendEmail}
                  onChange={(e) => setResendEmail(e.target.value)}
                  placeholder="you@example.com"
                  autoComplete="email"
                  className="theme-input rounded-lg p-3 text-sm"
                />
                <button
                  type="button"
                  onClick={handleResend}
                  className="theme-btn-secondary rounded-lg px-4 py-2.5 text-sm font-semibold"
                >
                  Send new link
                </button>
                {resendMsg && <p className="m-0 text-xs text-zinc-600">{resendMsg}</p>}
              </div>
            )}
          </>
        )}
      </section>
    </main>
  );
}
