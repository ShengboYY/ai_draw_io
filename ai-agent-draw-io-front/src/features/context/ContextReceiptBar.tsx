'use client';

import type { ContextReceipt, ContextReceiptAction } from './context-receipts';

const stateClass: Record<ContextReceipt['state'], string> = {
  used: 'border-stone-200 bg-white text-zinc-700',
  attached: 'border-emerald-200 bg-emerald-50 text-emerald-800',
  pending: 'border-amber-200 bg-amber-50 text-amber-800',
  skipped: 'border-stone-200 bg-stone-50 text-zinc-600',
  unavailable: 'border-rose-200 bg-rose-50 text-rose-800',
};

const actionLabel: Record<ContextReceiptAction, string> = {
  OPEN_FILES: '查看资料',
  OPEN_MEMORY: '管理决策',
  OPEN_CITATIONS: '查看引用',
};

/** Compact semantic context receipts; it intentionally contains no Direct/RAG mode selector. */
export const ContextReceiptBar = ({
  receipts,
  useChinese = false,
  onAction,
}: {
  receipts: ContextReceipt[];
  useChinese?: boolean;
  onAction?: (action: ContextReceiptAction, receipt: ContextReceipt) => void;
}) => {
  if (receipts.length === 0) return null;
  return (
    <div className="flex max-w-full flex-wrap items-center gap-1.5" aria-label={useChinese ? '本轮上下文使用情况' : 'Context used for this turn'}>
      {receipts.map(receipt => {
        const content = (
          <>
            <span className="max-w-52 truncate">{receipt.label}</span>
            {receipt.action && onAction && <span className="text-[10px] opacity-70">{actionLabel[receipt.action]}</span>}
          </>
        );
        const className = `inline-flex max-w-full items-center gap-1.5 rounded-full border px-2.5 py-1 text-[11px] ${stateClass[receipt.state]}`;
        if (receipt.action && onAction) {
          return (
            <button
              key={receipt.id}
              type="button"
              className={`${className} hover:border-zinc-400`}
              title={receipt.detail}
              onClick={() => onAction(receipt.action!, receipt)}
            >
              {content}
            </button>
          );
        }
        return <span key={receipt.id} className={className} title={receipt.detail}>{content}</span>;
      })}
    </div>
  );
};
