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
// `variant` adapts it to the compact sidebar footer, where the menu opens upward.
export function AdminAccountMenu({
  variant = 'bar',
  logoutHref = '/diagrams',
}: {
  variant?: 'bar' | 'sidebar';
  logoutHref?: string;
}) {
  const sidebar = variant === 'sidebar';
  const pathname = usePathname();
  const router = useRouter();
  const [email, setEmail] = useState<string | null>(null);
  const [isAdmin, setIsAdmin] = useState(false);
  const [isOpen, setIsOpen] = useState(false);
  const [sessionResolved, setSessionResolved] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const resolveAccount = async () => {
      try {
        const { data } = await agentApi.me();
        if (cancelled) return;
        if (data.status !== 'SUCCESS' || !data.email) {
          clearUserInfo();
          setEmail(null);
          setIsAdmin(false);
          return;
        }

        setUserInfo(data.email);
        setEmail(data.email);
        if (typeof data.admin === 'boolean') {
          setIsAdmin(data.admin);
          return;
        }

        // Compatibility fallback while older backends do not expose the admin flag.
        try {
          await agentApi.adminListRuns({ limit: 1 });
          if (!cancelled) setIsAdmin(true);
        } catch {
          if (!cancelled) setIsAdmin(false);
        }
      } catch {
        // The host page will present its own request error if the backend is unavailable.
      } finally {
        if (!cancelled) setSessionResolved(true);
      }
    };

    void resolveAccount();
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
    setIsAdmin(false);
    // Product surfaces choose where the user lands after the session is cleared.
    router.replace(logoutHref);
  };

  if (!sessionResolved) {
    return <span className={`block h-10 shrink-0 rounded-lg bg-stone-100 ${sidebar ? 'w-full' : 'w-10'}`} aria-label="Loading account" />;
  }

  if (!email) {
    if (sidebar) {
      return (
        <Link
          href={buildLoginHref(pathname)}
          className="flex h-10 w-full items-center gap-2.5 rounded-lg bg-zinc-800 px-2.5 text-sm font-semibold text-white shadow-sm transition hover:bg-zinc-700"
          title="Sign in"
        >
          <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-md bg-white/15 text-xs">A</span>
          <span className="hidden lg:block">Sign in</span>
        </Link>
      );
    }
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
    <div
      className={`relative flex min-w-0 items-center ${sidebar ? 'w-full' : ''}`}
      data-account-menu
      onMouseEnter={() => setIsOpen(true)}
    >
      <button
        type="button"
        onClick={() => setIsOpen(true)}
        aria-label={`${name} account menu`}
        aria-haspopup="menu"
        aria-expanded={isOpen}
        className={`flex min-w-0 items-center gap-2 rounded-lg transition hover:bg-stone-100 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-zinc-700/20 ${
          sidebar ? 'w-full p-1.5' : ''
        }`}
        title={name}
      >
        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-zinc-700 text-sm font-semibold text-white shadow-sm">
          {initials(email)}
        </span>
        {sidebar ? (
          <span className="hidden min-w-0 text-left lg:block">
            <span className="block max-w-36 truncate text-sm font-medium text-zinc-700">{name}</span>
            <span className="block text-[11px] text-zinc-400">Admin</span>
          </span>
        ) : (
          <span className="hidden min-w-0 text-right sm:block">
            <span className="block max-w-36 truncate text-sm font-medium text-zinc-700">{name}</span>
          </span>
        )}
      </button>

      {isOpen && (
        <div
          role="menu"
          className={`absolute z-30 w-40 overflow-hidden rounded-lg border border-stone-200 bg-white py-1 text-sm shadow-lg ${
            sidebar ? 'bottom-12 left-0' : 'right-0 top-12'
          }`}
        >
          <Link
            href="/diagrams"
            role="menuitem"
            onClick={() => setIsOpen(false)}
            className="block px-3 py-2 font-medium text-zinc-700 transition hover:bg-stone-50"
          >
            My diagrams
          </Link>
          {isAdmin && (
            <Link
              href="/admin"
              role="menuitem"
              onClick={() => setIsOpen(false)}
              className="block px-3 py-2 font-medium text-zinc-700 transition hover:bg-stone-50"
            >
              Admin dashboard
            </Link>
          )}
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
