import type { ReactNode } from 'react';
import Image from 'next/image';
import Link from 'next/link';
import { AdminAccountMenu } from './admin-account-menu';

type AdminSection = 'overview' | 'runs';

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

const navItems: { id: AdminSection; href: string; label: string }[] = [
  { id: 'overview', href: '/admin', label: 'Overview' },
  { id: 'runs', href: '/admin/runs', label: 'Runs' },
];

// Keep observability pages in the same visual frame as the diagram workspace.
export function AdminShell({ active, children }: AdminShellProps) {
  return (
    <main className="app-page min-h-screen text-zinc-800">
      <header className="sticky top-0 z-20 border-b border-stone-200 bg-white/95 backdrop-blur">
        <div className="flex h-16 items-center gap-3 px-4 sm:gap-4 sm:px-6 lg:px-8">
          <Link href="/diagrams" className="flex shrink-0 items-center gap-2.5" aria-label="FreeDraw workspace">
            <span className="relative block h-9 w-9 overflow-hidden rounded-xl shadow-sm" aria-hidden="true">
              <Image src="/brand/freedraw-logo-dark.png" alt="" fill sizes="36px" className="object-cover" priority />
            </span>
            <span className="font-display text-lg font-semibold text-zinc-800">FreeDraw</span>
          </Link>

          <span className="hidden h-5 w-px bg-stone-200 sm:block" aria-hidden="true" />
          <span className="hidden text-sm font-medium text-zinc-500 sm:block">Admin Dashboard</span>

          <nav className="ml-auto flex h-full items-center gap-0.5 sm:gap-1" aria-label="Admin sections">
            {navItems.map((item) => {
              const isActive = item.id === active;
              return (
                <Link
                  key={item.id}
                  href={item.href}
                  aria-current={isActive ? 'page' : undefined}
                  className={`flex h-9 items-center rounded-lg px-2 text-sm font-medium transition sm:px-3 ${
                    isActive
                      ? 'bg-zinc-800 text-white shadow-sm'
                      : 'text-zinc-500 hover:bg-stone-100 hover:text-zinc-800'
                  }`}
                >
                  {item.label}
                </Link>
              );
            })}
          </nav>

          <AdminAccountMenu />
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
