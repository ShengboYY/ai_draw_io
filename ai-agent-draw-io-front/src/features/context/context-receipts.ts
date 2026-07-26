export type ContextLocation = {
  kind: 'STANDALONE' | 'CHARTBOOK';
  label?: string;
};

export type ContextSourceUse = 'NONE' | 'DIRECT' | 'RETRIEVAL' | 'DIRECT_AND_RETRIEVAL';

export type ContextReceiptState = 'used' | 'attached' | 'pending' | 'skipped' | 'unavailable';

export type ContextReceiptAction = 'OPEN_FILES' | 'OPEN_MEMORY' | 'OPEN_CITATIONS';

export type ContextReceipt = {
  id: string;
  kind: 'location' | 'attachment' | 'source' | 'memory' | 'citation' | 'notice';
  label: string;
  detail: string;
  state: ContextReceiptState;
  action?: ContextReceiptAction;
};

export type ContextAttachmentReceiptInput = {
  label: string;
  state?: string;
};

export type ContextMemoryReceiptInput = {
  state: 'USED' | 'UNAVAILABLE' | 'STALE';
  count?: number;
};

export type OptionalEnrichmentSkipReason = 'NO_RELEVANT_MATCH' | 'UNAVAILABLE' | 'NOT_REQUIRED';

export type ContextReceiptInput = {
  location: ContextLocation;
  attachments?: ContextAttachmentReceiptInput[];
  sourceUse?: ContextSourceUse;
  memory?: ContextMemoryReceiptInput;
  citationCount?: number;
  optionalEnrichmentSkipped?: OptionalEnrichmentSkipReason;
  useChinese?: boolean;
};

const readyAttachmentStates = new Set(['READY', 'SUCCEEDED', 'PARTIAL_READY']);
const unavailableAttachmentStates = new Set(['FAILED', 'REJECTED', 'CANCELLED', 'EXPIRED']);

const attachmentState = (state?: string): ContextReceiptState => {
  const normalized = state?.trim().toUpperCase() || '';
  if (readyAttachmentStates.has(normalized)) return 'attached';
  if (unavailableAttachmentStates.has(normalized)) return 'unavailable';
  return 'pending';
};

const sourceReceipts = (sourceUse: ContextSourceUse, useChinese: boolean): ContextReceipt[] => {
  const labels = useChinese
    ? {
        prompt: ['当前请求', '仅使用当前消息和画布'],
        attachment: ['当前附件', '按附件内容完成'],
        materials: ['Chartbook 资料', '使用已授权的 Chartbook 资料'],
      }
    : {
        prompt: ['Current request', 'Used the current message and canvas only'],
        attachment: ['Attached material', 'Used the attached material'],
        materials: ['Chartbook materials', 'Used authorized Chartbook materials'],
      };
  if (sourceUse === 'NONE') {
    return [{ id: 'source-prompt', kind: 'source', label: labels.prompt[0], detail: labels.prompt[1], state: 'used' }];
  }
  if (sourceUse === 'DIRECT') {
    return [{ id: 'source-attachment', kind: 'source', label: labels.attachment[0], detail: labels.attachment[1], state: 'used' }];
  }
  if (sourceUse === 'RETRIEVAL') {
    return [{ id: 'source-chartbook', kind: 'source', label: labels.materials[0], detail: labels.materials[1], state: 'used', action: 'OPEN_FILES' }];
  }
  return [
    { id: 'source-attachment', kind: 'source', label: labels.attachment[0], detail: labels.attachment[1], state: 'used' },
    { id: 'source-chartbook', kind: 'source', label: labels.materials[0], detail: labels.materials[1], state: 'used', action: 'OPEN_FILES' },
  ];
};

export const buildCitationReceipt = (count: number, useChinese = false): ContextReceipt | null => {
  if (!Number.isFinite(count) || count <= 0) return null;
  const safeCount = Math.floor(count);
  return {
    id: 'citation-receipt',
    kind: 'citation',
    label: useChinese ? `${safeCount} 条资料引用` : `${safeCount} source citation${safeCount === 1 ? '' : 's'}`,
    detail: useChinese ? '可查看本轮回答使用的资料依据' : 'Open the sources used by this answer',
    state: 'used',
    action: 'OPEN_CITATIONS',
  };
};

/** Builds semantic receipts; technical source mode names never enter user-facing labels. */
export const buildContextReceipts = (input: ContextReceiptInput): ContextReceipt[] => {
  const useChinese = input.useChinese ?? false;
  const location = input.location.kind === 'CHARTBOOK'
    ? {
        id: 'context-chartbook',
        kind: 'location' as const,
        label: useChinese ? `Chartbook${input.location.label ? ` · ${input.location.label}` : ''}` : `Chartbook${input.location.label ? ` · ${input.location.label}` : ''}`,
        detail: useChinese ? '当前图表所属的工作资料范围' : 'The working context for this diagram',
        state: 'used' as const,
      }
    : {
        id: 'context-standalone',
        kind: 'location' as const,
        label: useChinese ? '独立画布' : 'Standalone canvas',
        detail: useChinese ? '未使用 Chartbook 资料范围' : 'No Chartbook context was attached',
        state: 'used' as const,
      };
  const receipts: ContextReceipt[] = [location];

  (input.attachments || []).filter(item => item.label.trim()).forEach((attachment, index) => {
    const state = attachmentState(attachment.state);
    receipts.push({
      id: `attachment-${index}-${attachment.label}`,
      kind: 'attachment',
      label: attachment.label,
      detail: state === 'attached'
        ? (useChinese ? '已附加到本轮消息' : 'Attached to this turn')
        : state === 'pending'
          ? (useChinese ? '仍在准备' : 'Still preparing')
          : (useChinese ? '未能使用' : 'Could not be used'),
      state,
    });
  });

  if (input.sourceUse) receipts.push(...sourceReceipts(input.sourceUse, useChinese));

  if (input.memory) {
    const memory = input.memory;
    receipts.push({
      id: 'memory-receipt',
      kind: 'memory',
      label: useChinese ? 'Chartbook 决策' : 'Chartbook decisions',
      detail: memory.state === 'USED'
        ? (useChinese ? `使用了 ${memory.count || 0} 条已确认决策` : `Used ${memory.count || 0} confirmed decision${memory.count === 1 ? '' : 's'}`)
        : memory.state === 'STALE'
          ? (useChinese ? '决策已过期，未注入本轮' : 'Stale decisions were not used')
          : (useChinese ? '暂时不可用，本轮未受阻' : 'Unavailable; this turn continued without it'),
      state: memory.state === 'USED' ? 'used' : 'unavailable',
      action: 'OPEN_MEMORY',
    });
  }

  const citation = buildCitationReceipt(input.citationCount || 0, useChinese);
  if (citation) receipts.push(citation);

  if (input.optionalEnrichmentSkipped) {
    const reason = input.optionalEnrichmentSkipped;
    receipts.push({
      id: 'optional-enrichment-skipped',
      kind: 'notice',
      label: useChinese ? '资料增强未使用' : 'Optional context not used',
      detail: reason === 'NO_RELEVANT_MATCH'
        ? (useChinese ? '未找到与本轮请求相关的资料' : 'No relevant material was found')
        : reason === 'UNAVAILABLE'
          ? (useChinese ? '资料暂时不可用，已继续处理当前请求' : 'Material was unavailable; the request continued')
          : (useChinese ? '本轮请求不需要额外资料' : 'This request did not need extra material'),
      state: 'skipped',
    });
  }

  return receipts;
};
