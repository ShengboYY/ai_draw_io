import type { ReactNode } from 'react';

type DiagramAction = {
  label: string;
  icon: ReactNode;
  onSelect: () => void;
  tone?: 'default' | 'danger';
  trailingIcon?: ReactNode;
};

type DiagramActionMenuProps = {
  diagramTitle: string;
  isOpen: boolean;
  onToggle: () => void;
  actions: DiagramAction[];
};

const iconProps = {
  viewBox: '0 0 24 24',
  className: 'h-[18px] w-[18px]',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.7,
  strokeLinecap: 'round',
  strokeLinejoin: 'round',
  'aria-hidden': true,
} as const;

export const PencilIcon = () => (
  <svg {...iconProps}><path d="M12 20h8" /><path d="M16.4 3.6a2 2 0 0 1 2.83 2.83L7.5 18.15 3.5 19.2l1.05-4Z" /></svg>
);

export const MoveIcon = () => (
  <svg {...iconProps}><path d="M13 5h6v6" /><path d="m19 5-8 8" /><path d="M11 7H5a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-6" /></svg>
);

export const TrashIcon = () => (
  <svg {...iconProps}><path d="M4 7h16" /><path d="M9 7V4h6v3" /><path d="m6.5 7 .8 13h9.4l.8-13" /><path d="M10 11v5M14 11v5" /></svg>
);

export const ChevronRightIcon = () => (
  <svg viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="m9 18 6-6-6-6" /></svg>
);

const MoreIcon = () => (
  <svg {...iconProps}><circle cx="5" cy="12" r="1" fill="currentColor" stroke="none" /><circle cx="12" cy="12" r="1" fill="currentColor" stroke="none" /><circle cx="19" cy="12" r="1" fill="currentColor" stroke="none" /></svg>
);

export const DiagramActionMenu = ({
  diagramTitle,
  isOpen,
  onToggle,
  actions,
}: DiagramActionMenuProps) => (
  <div className="relative shrink-0" data-diagram-action-menu>
    <button
      type="button"
      onClick={onToggle}
      className="flex h-8 w-8 cursor-pointer items-center justify-center rounded-lg text-zinc-500 transition hover:bg-stone-100 hover:text-zinc-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-zinc-700/20"
      aria-label={`More actions for ${diagramTitle}`}
      aria-haspopup="menu"
      aria-expanded={isOpen}
    >
      <MoreIcon />
    </button>
    {isOpen && (
      <div
        role="menu"
        aria-label={`Actions for ${diagramTitle}`}
        className="absolute right-0 top-9 z-20 w-44 rounded-xl border border-stone-200/90 bg-white p-1.5 shadow-[0_14px_35px_rgba(24,24,27,0.14)]"
      >
        {/* One shared row treatment keeps diagram menus visually aligned across workspace surfaces. */}
        {actions.map((action, index) => {
          const isDanger = action.tone === 'danger';
          return (
            <button
              key={action.label}
              type="button"
              role="menuitem"
              onClick={action.onSelect}
              className={`${index > 0 ? 'mt-0.5 ' : ''}flex w-full items-center gap-3 rounded-lg px-2.5 py-2 text-left transition ${
                isDanger
                  ? 'hover:bg-rose-50 focus-visible:bg-rose-50'
                  : 'hover:bg-stone-50 focus-visible:bg-stone-50'
              } focus-visible:outline-none`}
            >
              <span
                className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-lg ${
                  isDanger ? 'bg-rose-50 text-rose-600' : 'bg-stone-100 text-zinc-600'
                }`}
                aria-hidden="true"
              >
                {action.icon}
              </span>
              <span className={`flex-1 text-sm font-medium ${isDanger ? 'text-rose-700' : 'text-zinc-800'}`}>
                {action.label}
              </span>
              {action.trailingIcon && (
                <span className="text-zinc-400" aria-hidden="true">{action.trailingIcon}</span>
              )}
            </button>
          );
        })}
      </div>
    )}
  </div>
);
