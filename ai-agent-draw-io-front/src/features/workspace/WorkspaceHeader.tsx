'use client';

import Image from 'next/image';
import Link from 'next/link';
import { useEffect, useState, type ReactNode } from 'react';
import { isAccountMenuTarget } from '@/app/home-menu-click-away';
import type { WorkspaceIdentity } from './use-workspace-identity';

/**
 * Shared workspace top bar. Every signed-in surface uses it so the brand, account menu
 * and resource links stay identical; `search` is the only slot that varies.
 */
export const WorkspaceHeader = ({
  identity,
  search,
  onChartbooks,
  onMenuOpen,
}: {
  identity: WorkspaceIdentity;
  /** Omit on surfaces without their own search, e.g. a single chartbook. */
  search?: ReactNode;
  /** Provide to switch tabs in place instead of navigating to the chartbooks route. */
  onChartbooks?: () => void;
  onMenuOpen?: () => void;
}) => {
  const [isMenuOpen, setIsMenuOpen] = useState(false);

  useEffect(() => {
    if (!isMenuOpen) return;
    const closeMenuOnOutsidePointerDown = (event: PointerEvent) => {
      if (!isAccountMenuTarget(event.target)) setIsMenuOpen(false);
    };
    document.addEventListener('pointerdown', closeMenuOnOutsidePointerDown);
    return () => document.removeEventListener('pointerdown', closeMenuOnOutsidePointerDown);
  }, [isMenuOpen]);

  const openMenu = () => {
    // Keep closing on outside pointerdown so users can move from the trigger into the detached menu.
    onMenuOpen?.();
    setIsMenuOpen(true);
  };

  const itemClassName = 'block w-full px-3 py-2 text-left font-medium text-zinc-700 transition hover:bg-stone-50';
  return (
    <header className="sticky top-0 z-20 border-b border-stone-200 bg-white/95 backdrop-blur">
      <div className="flex h-auto flex-wrap items-center gap-3 px-4 py-3 sm:h-16 sm:flex-nowrap sm:gap-4 sm:px-6 sm:py-0">
        <Link href="/" className="order-1 flex shrink-0 items-center gap-2.5 sm:order-none" aria-label="FreeDraw home">
          {/* Use the supplied horizontal artwork so the wordmark stays consistent across product surfaces. */}
          <Image src="/brand/freedraw-wordmark-brush-on-light.png" alt="" width={122} height={21} priority />
        </Link>

        {search && (
          <div className="order-3 relative mx-0 flex w-full max-w-none basis-full items-center sm:order-none sm:mx-auto sm:max-w-xl sm:basis-auto">
            {search}
          </div>
        )}

        {/* Without a search box in the middle, the account block keeps itself pinned right. */}
        <div className={`order-2 ml-auto flex min-w-0 shrink-0 items-center gap-3 sm:order-none ${search ? 'sm:ml-0' : ''}`}>
          {!identity.isSignedInWorkspace && (
            <Link
              href="/login"
              className="theme-btn-secondary hidden h-10 items-center rounded-lg px-3 text-sm font-medium transition sm:inline-flex"
              title="Admin accounts use the same sign-in page."
            >
              Sign in
            </Link>
          )}
          <div
            className="relative flex min-w-0 items-center gap-2"
            data-account-menu
            onMouseEnter={openMenu}
          >
            {identity.isSignedInWorkspace ? (
              <>
                <button
                  type="button"
                  onClick={openMenu}
                  aria-label={`${identity.userDisplayName} account menu`}
                  aria-haspopup="menu"
                  aria-expanded={isMenuOpen}
                  className="flex min-w-0 items-center gap-2 rounded-lg transition hover:bg-stone-100 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-zinc-700/20"
                  title={identity.userDisplayName}
                >
                  <span className="hidden min-w-0 text-right sm:block">
                    <span className="block truncate text-sm font-medium text-zinc-700">{identity.userDisplayName}</span>
                  </span>
                  <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-zinc-700 text-sm font-semibold text-white shadow-sm transition">
                    {identity.userInitials}
                  </span>
                </button>
                {isMenuOpen && (
                  <div
                    role="menu"
                    className="absolute right-0 top-12 z-30 w-48 overflow-hidden rounded-lg border border-stone-200 bg-white py-1 text-sm shadow-lg"
                  >
                    {/* Keep secondary workspace destinations grouped with the signed-in account. */}
                    <Link href="/library" role="menuitem" onClick={() => setIsMenuOpen(false)} className={itemClassName}>
                      Library
                    </Link>
                    {onChartbooks ? (
                      <button
                        type="button"
                        role="menuitem"
                        onClick={() => {
                          setIsMenuOpen(false);
                          onChartbooks();
                        }}
                        className={itemClassName}
                      >
                        Chartbooks
                      </button>
                    ) : (
                      <Link href="/diagrams#chartbooks" role="menuitem" onClick={() => setIsMenuOpen(false)} className={itemClassName}>
                        Chartbooks
                      </Link>
                    )}
                    <div className="my-1 border-t border-stone-100" role="separator" />
                    {/* Admin authorization remains server-enforced; expired sessions retain /admin as the login return target. */}
                    <Link href="/admin" role="menuitem" onClick={() => setIsMenuOpen(false)} className={itemClassName}>
                      Admin dashboard
                    </Link>
                    <div className="my-1 border-t border-stone-100" role="separator" />
                    <button
                      type="button"
                      onClick={() => {
                        setIsMenuOpen(false);
                        void identity.logout();
                      }}
                      role="menuitem"
                      className="block w-full px-3 py-2 text-left text-zinc-700 transition hover:bg-stone-50"
                    >
                      Sign out
                    </button>
                  </div>
                )}
              </>
            ) : (
              <Link
                href="/login"
                aria-label="Sign in"
                className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-zinc-700 text-sm font-semibold text-white shadow-sm transition hover:bg-zinc-600"
                title="Sign in"
              >
                {identity.userInitials}
              </Link>
            )}
          </div>
        </div>
      </div>
    </header>
  );
};
