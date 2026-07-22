'use client';

import { useEffect, useRef, useState, type MouseEvent, type ReactNode } from 'react';
import Image from 'next/image';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { AdminAccountMenu } from './admin-account-menu';
import { primaryNavIdFor, primaryNavItems } from './admin-navigation.mjs';

type AdminSection = 'overview' | 'runs' | 'evalOverview' | 'evalRuns' | 'operations' | 'candidates' | 'cases' | 'datasets' | 'trace';
type PrimaryNavId = 'overview' | 'evaluation' | 'traceAnalysis' | 'operations';

interface AdminShellProps {
  active: AdminSection;
  children: ReactNode;
}

interface AdminPageHeadingProps {
  eyebrow: string;
  title: string;
  description: string;
  action?: ReactNode;
}

// Compact management console: a persistent vertical rail keeps every admin
// workspace one click apart while leaving the widest possible canvas for content.
export function AdminShell({ active, children }: AdminShellProps) {
  const router = useRouter();
  const activeNavId = primaryNavIdFor(active);
  const [visualActiveNavId, setVisualActiveNavId] = useState<PrimaryNavId | null>(activeNavId);
  const [mobileOpen, setMobileOpen] = useState(false);
  const navigationTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Keep browser back/forward navigation aligned with the animated menu state.
  useEffect(() => {
    setVisualActiveNavId(activeNavId);
  }, [activeNavId]);

  useEffect(() => () => {
    if (navigationTimer.current) clearTimeout(navigationTimer.current);
  }, []);

  const navigatePrimary = (event: MouseEvent<HTMLAnchorElement>, id: PrimaryNavId, href: string) => {
    // Preserve native modified-click behavior for opening links in another tab.
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;

    if (navigationTimer.current) clearTimeout(navigationTimer.current);
    setMobileOpen(false);

    // Clicking the current workspace toggles its submenu without changing routes.
    if (id === activeNavId) {
      event.preventDefault();
      setVisualActiveNavId((current) => current === id ? null : id);
      return;
    }

    setVisualActiveNavId(id);
    event.preventDefault();

    // Let the rail complete most of its transition before replacing the page.
    navigationTimer.current = setTimeout(() => router.push(href), 220);
  };

  return (
    <div className="app-page flex min-h-screen text-zinc-800">
      {/* Backdrop for the mobile drawer. */}
      {mobileOpen && (
        <button
          type="button"
          aria-label="Close navigation"
          onClick={() => setMobileOpen(false)}
          className="fixed inset-0 z-30 bg-zinc-900/30 backdrop-blur-sm sm:hidden"
        />
      )}

      <aside
        className={`fixed inset-y-0 left-0 z-40 flex w-60 shrink-0 flex-col border-r border-stone-200 bg-white/95 backdrop-blur transition-transform sm:sticky sm:top-0 sm:z-20 sm:h-screen sm:w-16 sm:translate-x-0 lg:w-60 ${
          mobileOpen ? 'translate-x-0' : '-translate-x-full'
        }`}
      >
        <div className="flex h-16 shrink-0 items-center gap-2.5 border-b border-stone-200 px-4 sm:justify-center sm:px-0 lg:justify-start lg:px-5">
          <Link href="/" className="flex min-w-0 items-center gap-2.5" aria-label="FreeDraw home">
            {/* Keep the full wordmark visible in the wide rail and the compact mark in the collapsed rail. */}
            <Image src="/brand/freedraw-app-icon-v2.png" alt="" width={32} height={32} className="shrink-0 rounded-lg lg:hidden" priority />
            <Image src="/brand/freedraw-wordmark-on-light-v2.png" alt="" width={116} height={20} className="hidden lg:block" priority />
          </Link>
        </div>

        <nav className="flex-1 space-y-1 overflow-y-auto px-2 py-3 lg:px-3" aria-label="Admin sections">
          <p className="px-2 pb-1 pt-1 text-[10px] font-semibold uppercase tracking-[0.12em] text-zinc-400 sm:hidden lg:block">
            Admin
          </p>
          {primaryNavItems.map((item) => {
            const isActive = item.id === activeNavId;
            const isVisuallyActive = item.id === visualActiveNavId;
            const isHighlighted = isVisuallyActive || (visualActiveNavId === null && isActive);
            return (
              <div key={item.id}>
                <Link
                  href={item.href}
                  aria-current={isActive && item.children.length === 0 ? 'page' : undefined}
                  aria-expanded={item.children.length > 0 ? isVisuallyActive : undefined}
                  title={item.label}
                  onClick={(event) => navigatePrimary(event, item.id, item.href)}
                  className={`group flex h-10 items-center gap-3 rounded-lg px-2.5 text-sm font-medium transition-colors duration-300 sm:justify-center lg:justify-start ${
                    isHighlighted
                      ? 'bg-zinc-800 text-white'
                      : 'text-zinc-500 hover:bg-stone-100 hover:text-zinc-800'
                  }`}
                >
                  <NavIcon id={item.id} className="h-5 w-5 shrink-0" />
                  <span className="truncate sm:hidden lg:inline">{item.label}</span>
                </Link>

                {/* Animate the submenu's real height so sibling sections glide into place. */}
                {item.children.length > 0 && (
                  <div
                    aria-hidden={!isVisuallyActive}
                    className={`grid transition-[grid-template-rows,opacity,margin] duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] sm:hidden lg:grid ${
                      isVisuallyActive
                        ? 'mt-1 grid-rows-[1fr] opacity-100'
                        : 'pointer-events-none mt-0 grid-rows-[0fr] opacity-0'
                    }`}
                  >
                    <div className="overflow-hidden">
                      <div className="ml-5 space-y-0.5 border-l border-stone-200 pl-3">
                        {item.children.map((child) => {
                          const isChildActive = child.section === active;
                          return (
                            <Link
                              key={child.section}
                              href={child.href}
                              tabIndex={isVisuallyActive ? undefined : -1}
                              aria-current={isChildActive ? 'page' : undefined}
                              onClick={() => setMobileOpen(false)}
                              className={`block rounded-md px-2.5 py-2 text-xs font-medium transition-colors duration-200 ${
                                isChildActive
                                  ? 'bg-stone-100 text-zinc-900'
                                  : 'text-zinc-500 hover:bg-stone-50 hover:text-zinc-800'
                              }`}
                            >
                              {child.label}
                            </Link>
                          );
                        })}
                      </div>
                    </div>
                  </div>
                )}
              </div>
            );
          })}
        </nav>

        <div className="border-t border-stone-200 p-2 lg:p-3">
          <AdminAccountMenu variant="sidebar" />
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        {/* Slim top bar surfaces the drawer toggle on small screens. */}
        <header className="sticky top-0 z-10 flex h-12 items-center gap-3 border-b border-stone-200 bg-white/95 px-4 backdrop-blur sm:hidden">
          <button
            type="button"
            aria-label="Open navigation"
            onClick={() => setMobileOpen(true)}
            className="flex h-9 w-9 items-center justify-center rounded-lg border border-stone-200 text-zinc-600"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="h-5 w-5">
              <path d="M4 7h16M4 12h16M4 17h16" strokeLinecap="round" />
            </svg>
          </button>
          <span className="text-sm font-medium text-zinc-600">Admin Dashboard</span>
        </header>

        <main className="w-full flex-1 px-4 py-6 sm:px-6 sm:py-7 lg:px-8">{children}</main>
      </div>
    </div>
  );
}

export function AdminPageHeading({ eyebrow, title, description, action }: AdminPageHeadingProps) {
  return (
    <div className="mb-6 flex flex-col gap-4 border-b border-stone-200 pb-5 sm:flex-row sm:items-end sm:justify-between">
      <div>
        <p className="font-mono text-[11px] font-medium uppercase tracking-[0.12em] text-zinc-400">{eyebrow}</p>
        <h1 className="mt-1.5 font-display text-2xl font-semibold text-zinc-900 sm:text-3xl">{title}</h1>
        <p className="mt-1.5 text-sm text-zinc-500">{description}</p>
      </div>
      {action && <div className="flex shrink-0 items-center">{action}</div>}
    </div>
  );
}

// Line icons tuned to the compact rail — one glyph per primary workspace.
function NavIcon({ id, className }: { id: PrimaryNavId | string; className?: string }) {
  const common = { viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.8, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const, className };
  if (id === 'overview') {
    return (
      <svg {...common}>
        <rect x="3" y="3" width="7" height="9" rx="1.5" />
        <rect x="14" y="3" width="7" height="5" rx="1.5" />
        <rect x="14" y="12" width="7" height="9" rx="1.5" />
        <rect x="3" y="16" width="7" height="5" rx="1.5" />
      </svg>
    );
  }
  if (id === 'evaluation') {
    return (
      <svg {...common}>
        <path d="M9 11l3 3L22 4" />
        <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
      </svg>
    );
  }
  if (id === 'traceAnalysis') {
    return (
      <svg {...common}>
        <path d="M4 5h16" />
        <path d="M4 10h10" />
        <path d="M4 15h13" />
        <path d="M4 20h7" />
      </svg>
    );
  }
  // operations
  return (
    <svg {...common}>
      <circle cx="12" cy="12" r="3" />
      <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
    </svg>
  );
}
