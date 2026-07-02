'use client';

import { useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';

import { agentApi } from '@/api/agent';
import type { EmailVerificationStatus } from '@/types/api';
import { verificationDisplay } from '@/utils/register-form';

type Phase = 'verifying' | 'done' | 'error';

export default function VerifyEmail() {
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
    <main className="min-h-screen flex justify-center items-center p-7 theme-bg-gradient">
      <section className="theme-card rounded-[16px] p-6 w-full max-w-[480px] flex flex-col gap-3">
        {phase === 'verifying' ? (
          <>
            <h1 className="m-0 text-xl font-bold text-[rgba(255,255,255,0.92)]">Verifying…</h1>
            <p className="m-0 text-sm text-[rgba(255,255,255,0.72)]">
              Hang tight while we check your verification link.
            </p>
          </>
        ) : (
          <>
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

            {display.variant === 'success' && (
              <Link
                href="/login"
                className="theme-btn rounded-[12px] p-[10px_14px] font-bold text-sm text-center mt-2"
              >
                Go to sign in
              </Link>
            )}

            {display.canResend && (
              <div className="mt-2 flex flex-col gap-2">
                <label className="text-xs text-[rgba(255,255,255,0.72)]">Resend verification email</label>
                <input
                  type="email"
                  value={resendEmail}
                  onChange={(e) => setResendEmail(e.target.value)}
                  placeholder="you@example.com"
                  autoComplete="email"
                  className="rounded-[12px] theme-input p-3 outline-none text-sm"
                />
                <button
                  type="button"
                  onClick={handleResend}
                  className="theme-btn-secondary rounded-[12px] p-[10px_14px] font-semibold text-sm"
                >
                  Send new link
                </button>
                {resendMsg && <p className="m-0 text-xs text-[rgba(255,255,255,0.72)]">{resendMsg}</p>}
              </div>
            )}
          </>
        )}
      </section>
    </main>
  );
}
