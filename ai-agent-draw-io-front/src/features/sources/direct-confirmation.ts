export type DirectClarificationResolution =
  | 'ACCEPT_OBSERVED'
  | 'FORWARD'
  | 'REVERSE'
  | 'BIDIRECTIONAL'
  | 'UNDIRECTED';

export type DirectClarification = {
  reasonCode: string;
  resolution: DirectClarificationResolution;
  observedFingerprint: string;
};

export type DirectConfirmationIssue = {
  reasonCode: string;
  observedValue?: string;
  observedFingerprint?: string;
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
    prompt: observedValue
      ? `是否按当前识别结果“${observedValue}”继续？`
      : '是否按当前识别结果继续？',
    options: [{ value: 'ACCEPT_OBSERVED', label: '接受当前识别' }],
  };
};

/** Returns null until the user has explicitly resolved every reported issue. */
export const buildDirectClarifications = (
  issues: Array<{ reasonCode: string; observedFingerprint?: string }>,
  selections: Record<string, DirectClarificationResolution>,
): DirectClarification[] | null => {
  const uniqueIssues = Array.from(new Map(
    issues.filter(issue => Boolean(issue.reasonCode))
      .map(issue => [issue.reasonCode, issue]),
  ).values());
  const values = uniqueIssues.map(issue => ({
    reasonCode: issue.reasonCode,
    resolution: selections[issue.reasonCode],
    observedFingerprint: issue.observedFingerprint || '',
  }));
  return values.every(value => Boolean(value.resolution) && Boolean(value.observedFingerprint))
    ? values as DirectClarification[]
    : null;
};
