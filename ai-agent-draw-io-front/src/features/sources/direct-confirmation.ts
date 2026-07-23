export type DirectClarificationResolution =
  | 'ACCEPT_OBSERVED'
  | 'FORWARD'
  | 'REVERSE'
  | 'BIDIRECTIONAL'
  | 'UNDIRECTED';

export type DirectClarification = {
  reasonCode: string;
  resolution: DirectClarificationResolution;
};

type DirectConfirmationIssue = {
  reasonCode: string;
  targetLabel: string;
  prompt: string;
  options: Array<{ value: DirectClarificationResolution; label: string }>;
};

const directionOptions: DirectConfirmationIssue['options'] = [
  { value: 'FORWARD', label: '正向' },
  { value: 'REVERSE', label: '反向' },
  { value: 'BIDIRECTIONAL', label: '双向' },
  { value: 'UNDIRECTED', label: '无方向' },
];

const reasonTarget = (reasonCode: string) => reasonCode.split(':', 2)[1] || '未命名区域';

/** Converts bounded backend reason codes into user-facing choices without exposing model prose. */
export const directConfirmationIssue = (
  reasonCode: string,
  observedValue = '',
): DirectConfirmationIssue => {
  const target = reasonTarget(reasonCode);
  if (reasonCode.startsWith('UNRESOLVED_EDGE_DIRECTION:')) {
    return {
      reasonCode,
      targetLabel: `连线 ${target}`,
      prompt: '这条连线的箭头方向是什么？',
      options: directionOptions,
    };
  }
  if (reasonCode.startsWith('LOW_CONFIDENCE_NODE_TEXT:')) {
    return {
      reasonCode,
      targetLabel: `节点 ${target}`,
      prompt: observedValue
        ? `是否接受当前识别出的节点文字“${observedValue}”？`
        : '是否接受当前识别出的节点文字？',
      options: [{ value: 'ACCEPT_OBSERVED', label: '接受当前识别' }],
    };
  }
  if (reasonCode.startsWith('LOW_CONFIDENCE_GROUP_TEXT:')) {
    return {
      reasonCode,
      targetLabel: `分组 ${target}`,
      prompt: observedValue
        ? `是否接受当前识别出的分组文字“${observedValue}”？`
        : '是否接受当前识别出的分组文字？',
      options: [{ value: 'ACCEPT_OBSERVED', label: '接受当前识别' }],
    };
  }
  if (reasonCode.startsWith('LOW_CONFIDENCE_EDGE:')) {
    return {
      reasonCode,
      targetLabel: `连线 ${target}`,
      prompt: '是否接受当前识别出的连线？',
      options: [{ value: 'ACCEPT_OBSERVED', label: '接受当前识别' }],
    };
  }
  return {
    reasonCode,
    targetLabel: `图片区域 ${target}`,
    prompt: '是否按当前识别结果继续？',
    options: [{ value: 'ACCEPT_OBSERVED', label: '接受当前识别' }],
  };
};

/** Returns null until the user has explicitly resolved every reported issue. */
export const buildDirectClarifications = (
  reasons: string[],
  selections: Record<string, DirectClarificationResolution>,
): DirectClarification[] | null => {
  const uniqueReasons = Array.from(new Set(reasons.filter(Boolean)));
  const values = uniqueReasons.map(reasonCode => ({
    reasonCode,
    resolution: selections[reasonCode],
  }));
  return values.every(value => Boolean(value.resolution))
    ? values as DirectClarification[]
    : null;
};
