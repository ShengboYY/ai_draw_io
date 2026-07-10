'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { agentApi } from '@/api/agent';
import { isAccountMenuTarget } from '@/app/home-menu-click-away';
import { buildLoginHref } from '@/utils/login-form';
import { clearUserInfo, setUserInfo } from '@/utils/cookie';

const displayName = (email?: string | null) => {
  const value = email?.trim();
  if (!value) return 'Account';
  return value.includes('@') ? value.split('@')[0] : value;
};

const initials = (email?: string | null) =>
  displayName(email)
    .split(/[\s._-]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0])
    .join('')
    .toUpperCase() || 'A';

// Mirrors the workspace account affordance while keeping admin route recovery local.
export function AdminAccountMenu() {
  const pathname = usePathname();
  const router = useRouter();
  const [email, setEmail] = useState<string | null>(null);
  const [isOpen, setIsOpen] = useState(false);
  const [sessionResolved, setSessionResolved] = useState(false);

  useEffect(() => {
    let cancelled = false;
    agentApi.me()
      .then(({ data }) => {
        if (cancelled) return;
        if (data.status === 'SUCCESS' && data.email) {
          setUserInfo(data.email);
          setEmail(data.email);
          return;
        }
        clearUserInfo();
        setEmail(null);
      })
      .catch(() => {
        // The admin page will present its own request error if the backend is unavailable.
      })
      .finally(() => {
        if (!cancelled) setSessionResolved(true);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!isOpen) return;
    const closeOnOutsidePointerDown = (event: PointerEvent) => {
      if (!isAccountMenuTarget(event.target)) setIsOpen(false);
    };
    document.addEventListener('pointerdown', closeOnOutsidePointerDown);
    return () => document.removeEventListener('pointerdown', closeOnOutsidePointerDown);
  }, [isOpen]);

  const logout = async () => {
    setIsOpen(false);
    try {
      await agentApi.logout();
    } catch {
      // Clearing local state still gives the user a reliable way out of a stale session.
    }
    clearUserInfo();
    setEmail(null);
    router.replace('/diagrams');
  };

  if (!sessionResolved) {
    return <span className="block h-10 w-10 shrink-0 rounded-lg bg-stone-100" aria-label="Loading account" />;
  }

  if (!email) {
    return (
      <div className="flex shrink-0 items-center gap-3">
        <Link
          href={buildLoginHref(pathname)}
          className="theme-btn-secondary hidden h-10 items-center rounded-lg px-3 text-sm font-medium transition sm:inline-flex"
        >
          Sign in
        </Link>
        <Link
          href={buildLoginHref(pathname)}
          aria-label="Sign in"
          className="flex h-10 w-10 items-center justify-center rounded-lg bg-zinc-700 text-sm font-semibold text-white shadow-sm transition hover:bg-zinc-600"
          title="Sign in"
        >
          A
        </Link>
      </div>
    );
  }

  const name = displayName(email);
  return (
    <div className="relative flex min-w-0 items-center" data-account-menu onMouseEnter={() => setIsOpen(true)}>
      <button
        type="button"
        onClick={() => setIsOpen(true)}
        aria-label={`${name} account menu`}
        aria-haspopup="menu"
        aria-expanded={isOpen}
        className="flex min-w-0 items-center gap-2 rounded-lg transition hover:bg-stone-100 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-zinc-700/20"
        title={name}
      >
        <span className="hidden min-w-0 text-right sm:block">
          <span className="block max-w-36 truncate text-sm font-medium text-zinc-700">{name}</span>
        </span>
        <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-zinc-700 text-sm font-semibold text-white shadow-sm">
          {initials(email)}
        </span>
      </button>

      {isOpen && (
        <div role="menu" className="absolute right-0 top-12 z-30 w-40 overflow-hidden rounded-lg border border-stone-200 bg-white py-1 text-sm shadow-lg">
          <Link
            href="/diagrams"
            role="menuitem"
            onClick={() => setIsOpen(false)}
            className="block px-3 py-2 font-medium text-zinc-700 transition hover:bg-stone-50"
          >
            My diagrams
          </Link>
          <div className="my-1 border-t border-stone-100" role="separator" />
          <button
            type="button"
            onClick={logout}
            role="menuitem"
            className="block w-full px-3 py-2 text-left text-zinc-700 transition hover:bg-stone-50"
          >
            Sign out
          </button>
        </div>
      )}
    </div>
  );
}
