'use client';

import { useEffect, useState, type ReactNode } from 'react';
import Image from 'next/image';
import Link from 'next/link';
import { AdminAccountMenu } from './admin-account-menu';

type AdminSection = 'overview' | 'runs' | 'evalRuns' | 'operations' | 'candidates' | 'cases' | 'datasets' | 'trace';

interface AdminShellProps {
  active: AdminSection;
  traceHref?: string;
  children: ReactNode;
}

interface AdminPageHeadingProps {
  eyebrow: string;
  title: string;
  description: string;
  action?: ReactNode;
}

const primaryNavItems: { id: Exclude<AdminSection, 'trace'>; href: string; label: string }[] = [
  { id: 'overview', href: '/admin', label: 'Overview' },
  { id: 'runs', href: '/admin/runs', label: 'Runs' },
  { id: 'evalRuns', href: '/admin/eval-runs', label: 'Evaluations' },
  { id: 'operations', href: '/admin/eval-operations', label: 'Operations' },
  { id: 'candidates', href: '/admin/eval-candidates', label: 'Candidates' },
  { id: 'cases', href: '/admin/eval-cases', label: 'Cases' },
  { id: 'datasets', href: '/admin/eval-datasets', label: 'Datasets' },
];

// Keep observability pages in the same visual frame as the diagram workspace.
export function AdminShell({ active, traceHref, children }: AdminShellProps) {
  const navItems = traceHref
    ? [...primaryNavItems, { id: 'trace' as const, href: traceHref, label: 'Trace' }]
    : primaryNavItems;
  const [visualActive, setVisualActive] = useState(active);
  const visualIndex = Math.max(0, navItems.findIndex((item) => item.id === visualActive));

  useEffect(() => {
    setVisualActive(active);
  }, [active]);

  return (
    <main className="app-page min-h-screen text-zinc-800">
      <header className="sticky top-0 z-20 border-b border-stone-200 bg-white/95 backdrop-blur">
        <div className="relative flex h-16 items-center gap-3 px-4 sm:gap-4 sm:px-6 lg:px-8">
          <Link href="/diagrams" className="flex shrink-0 items-center gap-2.5" aria-label="FreeDraw workspace">
            <span className="relative block h-9 w-9 overflow-hidden rounded-xl shadow-sm" aria-hidden="true">
              <Image src="/brand/freedraw-logo-dark.png" alt="" fill sizes="36px" className="object-cover" priority />
            </span>
            <span className="font-display text-lg font-semibold text-zinc-800">FreeDraw</span>
          </Link>

          <span className="hidden h-5 w-px bg-stone-200 sm:block" aria-hidden="true" />
          <span className="hidden text-sm font-medium text-zinc-500 sm:block">Admin Dashboard</span>

          <nav
            className="relative ml-auto grid h-10 items-center rounded-xl border border-stone-200 bg-stone-100/90 p-1 shadow-sm sm:absolute sm:left-1/2 sm:ml-0 sm:-translate-x-1/2"
            aria-label="Admin sections"
            style={{ gridTemplateColumns: `repeat(${navItems.length}, minmax(0, 1fr))` }}
          >
            {/* The shared background slides between routes while the links remain independently accessible. */}
            <span
              aria-hidden="true"
              className="absolute inset-y-1 left-1 rounded-lg bg-zinc-800 shadow-sm transition-transform duration-500 ease-[cubic-bezier(0.22,1,0.36,1)] will-change-transform"
              style={{
                width: `calc((100% - 0.5rem) / ${navItems.length})`,
                transform: `translateX(${visualIndex * 100}%)`,
              }}
            />
            {navItems.map((item) => {
              const isActive = item.id === active;
              const isVisuallyActive = item.id === visualActive;
              return (
                <Link
                  key={item.id}
                  href={item.href}
                  aria-current={isActive ? 'page' : undefined}
                  onClick={() => setVisualActive(item.id)}
                  className={`relative z-10 flex h-8 items-center justify-center rounded-lg px-2 text-sm font-medium transition-colors sm:px-3 ${
                    isVisuallyActive
                      ? 'text-white'
                      : 'text-zinc-500 hover:text-zinc-800'
                  }`}
                >
                  {item.label}
                </Link>
              );
            })}
          </nav>

          <div className="sm:ml-auto">
            <AdminAccountMenu />
          </div>
        </div>
      </header>

      <div className="mx-auto w-full max-w-7xl px-4 py-7 sm:px-6 sm:py-9 lg:px-8">{children}</div>
    </main>
  );
}

export function AdminPageHeading({ eyebrow, title, description, action }: AdminPageHeadingProps) {
  return (
    <div className="mb-7 flex flex-col gap-4 border-b border-stone-200 pb-5 sm:flex-row sm:items-end sm:justify-between">
      <div>
        <p className="font-mono text-[11px] font-medium uppercase tracking-[0.12em] text-zinc-400">{eyebrow}</p>
        <h1 className="mt-2 font-display text-3xl font-semibold text-zinc-900 sm:text-4xl">{title}</h1>
        <p className="mt-2 text-sm text-zinc-500">{description}</p>
      </div>
      {action && <div className="flex shrink-0 items-center">{action}</div>}
    </div>
  );
}
