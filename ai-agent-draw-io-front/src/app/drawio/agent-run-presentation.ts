export type AgentRunEventStatus = 'running' | 'done' | 'warning' | 'error';
export type AgentRunEventTone = 'analysis' | 'drawing' | 'tool' | 'validation' | 'review';
export type AgentRunScope = 'full' | 'local' | 'append' | 'layout' | 'review';
export type AgentRouteType = 'create_new' | 'edit_existing' | 'optimize_layout' | 'answer_only' | 'answer_with_evidence' | 'clarify' | 'review_only';
export type VisualReviewDecision = 'APPROVE' | 'APPROVE_WITH_NOTES' | 'REPAIR' | 'NEEDS_HUMAN_REVIEW' | 'UNAVAILABLE';
export type VisualReviewStage = 'CURRENT_CANVAS' | 'POST_MUTATION' | 'POST_REPAIR' | 'VERIFY_ONLY';

export type VisualReviewDisplayIssue = {
  type?: string;
  severity?: string;
  region?: string;
  // These fields are accepted so callers can pass the transport object directly, but are never rendered.
  evidence?: string;
  repairInstruction?: string;
  repairScope?: 'local' | 'whole_canvas';
};

export type VisualReviewPresentation = {
  stage: VisualReviewStage;
  visualRepairRound?: number;
  decision?: VisualReviewDecision;
  summary?: string;
  issues?: VisualReviewDisplayIssue[];
  stale?: boolean;
  repairCompleted?: boolean;
  unavailableReason?: 'EXPORT_FAILED' | 'VLM_UNAVAILABLE' | 'REVIEW_REQUEST_FAILED';
};

export type AgentRunEvent = {
  id: string;
  phase: string;
  title: string;
  detail?: string;
  status: AgentRunEventStatus;
  tone: AgentRunEventTone;
  tool?: string;
  scope?: AgentRunScope;
  nodes?: number;
  edges?: number;
};

export type AgentRunView = {
  statusTone: 'running' | 'passed' | 'warning' | 'failed';
  metricLabel: string;
  finalContent: string;
};

const unique = (values: string[]) => Array.from(new Set(values.filter(Boolean)));

const pluralize = (count: number, singular: string, plural: string) => `${count} ${count === 1 ? singular : plural}`;

export const usesChinesePresentation = (text?: string) => {
  const value = text || '';
  const hanCount = [...value].filter(char => /[\u3400-\u9fff]/.test(char)).length;
  const latinCount = [...value].filter(char => /[A-Za-z]/.test(char)).length;
  // A quoted Chinese label should not switch an otherwise English response to Chinese.
  return hanCount > 0 && (latinCount === 0 || (hanCount >= 2 && hanCount * 2 >= latinCount));
};

const localizeMetricLabel = (metricLabel: string, useChinese: boolean) => {
  const match = metricLabel.match(/^(\d+) nodes? · (\d+) edges?$/);
  if (!match || !useChinese) return metricLabel;
  return `${match[1]} 个节点、${match[2]} 条连线`;
};

const getLatestMetric = (events: AgentRunEvent[]) => {
  for (let i = events.length - 1; i >= 0; i--) {
    const event = events[i];
    if (typeof event.nodes === 'number' || typeof event.edges === 'number') {
      // A zero-zero review metric means no drawable XML reached the canvas.
      if ((event.nodes || 0) === 0 && (event.edges || 0) === 0) continue;
      return {
        nodes: event.nodes || 0,
        edges: event.edges || 0,
      };
    }
  }

  return null;
};

const routeLabels: Record<AgentRouteType, string> = {
  create_new: 'New diagram',
  edit_existing: 'Edit diagram',
  optimize_layout: 'Layout repair',
  answer_only: 'Answer',
  answer_with_evidence: 'Evidence answer',
  clarify: 'Clarify request',
  review_only: 'Diagram review',
};

const routeLabelsChinese: Record<AgentRouteType, string> = {
  create_new: '新建图表',
  edit_existing: '修改图表',
  optimize_layout: '布局修复',
  answer_only: '回答问题',
  answer_with_evidence: '资料回答',
  clarify: '澄清需求',
  review_only: '审阅图表',
};

const routePhaseLabels: Record<AgentRouteType, Partial<Record<string, string>>> = {
  create_new: {
    analyzing: 'Understand diagram request',
    drawing: 'Drawing',
    reviewing: 'Deterministic validation',
    revising: 'Refine diagram',
  },
  edit_existing: {
    analyzing: 'Understand requested change',
    drawing: 'Drawing',
    reviewing: 'Deterministic validation',
    revising: 'Refine changes',
  },
  optimize_layout: {
    analyzing: 'Inspect layout',
    drawing: 'Drawing',
    reviewing: 'Deterministic validation',
    revising: 'Fix remaining issues',
  },
  answer_only: {
    analyzing: 'Understand question',
    thinking: 'Prepare answer',
  },
  answer_with_evidence: {
    analyzing: 'Understand evidence question',
    retrieval: 'Retrieve authorized evidence',
  },
  clarify: {
    analyzing: 'Identify missing details',
    thinking: 'Prepare question',
  },
  review_only: {
    analyzing: 'Inspect diagram',
    reviewing: 'Explain findings',
  },
};

const fallbackPhaseLabels: Record<string, string> = {
  analyzing: 'Analyze request',
  drawing: 'Draw diagram',
  reviewing: 'Review quality',
  revising: 'Plan revision',
  thinking: 'Thinking',
  retrieval: 'Retrieve evidence',
};

const routePhaseLabelsChinese: Record<AgentRouteType, Partial<Record<string, string>>> = {
  create_new: { analyzing: '理解图表需求', drawing: '绘制图表', reviewing: '结构校验', revising: '修正图表' },
  edit_existing: { analyzing: '理解修改要求', drawing: '修改画布', reviewing: '结构校验', revising: '修正修改' },
  optimize_layout: { analyzing: '检查布局', drawing: '优化布局', reviewing: '结构校验', revising: '修复剩余问题' },
  answer_only: { analyzing: '理解问题', thinking: '组织回答' },
  answer_with_evidence: { analyzing: '理解资料问题', retrieval: '检索授权资料' },
  clarify: { analyzing: '确认缺失信息', thinking: '组织澄清问题' },
  review_only: { analyzing: '检查图表', reviewing: '整理审阅结果' },
};

const fallbackPhaseLabelsChinese: Record<string, string> = {
  analyzing: '分析请求',
  drawing: '绘制图表',
  reviewing: '检查质量',
  revising: '修正图表',
  thinking: '处理请求',
  retrieval: '检索资料',
};

const diagramTypeLabelsChinese: Record<string, string> = {
  architecture: '架构图',
  concept: '概念图',
  er: 'ER 图',
  flowchart: '流程图',
  sequence: '时序图',
  state: '状态图',
  uml: 'UML 图',
  usecase: '用例图',
};

export const thinkingRouteLabel = (routeType?: string, useChinese = false) => (
  (useChinese ? routeLabelsChinese : routeLabels)[routeType as AgentRouteType]
);

export const thinkingPhaseLabel = (routeType: string | undefined, phase: string, useChinese = false) => (
  useChinese
    ? routePhaseLabelsChinese[routeType as AgentRouteType]?.[phase]
      || fallbackPhaseLabelsChinese[phase]
      || fallbackPhaseLabelsChinese.thinking
    : routePhaseLabels[routeType as AgentRouteType]?.[phase]
      || fallbackPhaseLabels[phase]
      || fallbackPhaseLabels.thinking
);

export const visualReviewStageLabel = (stage: VisualReviewStage | 'REPAIR', useChinese = false) => {
  if (stage === 'VERIFY_ONLY') return useChinese ? '修复后复核' : 'Final verification';
  if (stage === 'POST_REPAIR') return useChinese ? '修复后审阅' : 'Post-repair review';
  if (stage === 'REPAIR') return useChinese ? '视觉修复' : 'Visual repair';
  return useChinese ? '视觉审阅' : 'Visual review';
};

export const buildRouteStepDetail = ({
  routeType,
  diagramType,
  skillName,
  useChinese = false,
}: {
  routeType?: string;
  diagramType?: string;
  skillName?: string;
  useChinese?: boolean;
}) => {
  const route = routeType as AgentRouteType;
  const usableSkill = skillName && skillName !== 'none' ? skillName : '';
  const diagramLabel = useChinese
    ? diagramTypeLabelsChinese[(diagramType || '').toLowerCase()] || '图表'
    : (diagramType && diagramType !== 'none' ? diagramType.replace(/[_-]+/g, ' ') : 'diagram');

  if (useChinese) {
    if (route === 'create_new') {
      return `识别为新建${diagramLabel}任务${usableSkill ? `，将使用 ${usableSkill} 技能` : ''}生成画布。`;
    }
    if (route === 'edit_existing') return `识别为修改现有${diagramLabel}${usableSkill ? `，将使用 ${usableSkill} 技能并` : '，将'}保留未涉及的画布内容。`;
    if (route === 'optimize_layout') return `识别为${diagramLabel}布局优化${usableSkill ? `，将使用 ${usableSkill} 技能` : ''}，只调整排版和连线路径。`;
    if (route === 'review_only') return `识别为${diagramLabel}审阅任务${usableSkill ? `，将使用 ${usableSkill} 技能` : ''}，只检查画布，不直接修改。`;
    if (route === 'clarify') return '当前信息不足以安全修改画布，将先确认具体需求。';
    return '这是一个无需修改画布的问题，将直接组织回答。';
  }

  if (route === 'create_new') {
    return `Classified as a new ${diagramLabel}${usableSkill ? ` using the ${usableSkill} skill` : ''}.`;
  }
  if (route === 'edit_existing') return `Classified as an edit to the existing ${diagramLabel}${usableSkill ? ` using the ${usableSkill} skill` : ''}; unrelated canvas content will be preserved.`;
  if (route === 'optimize_layout') return `Classified as a ${diagramLabel} layout optimization${usableSkill ? ` using the ${usableSkill} skill` : ''}.`;
  if (route === 'review_only') return `Classified as a review-only pass over the current ${diagramLabel}${usableSkill ? ` using the ${usableSkill} skill` : ''}.`;
  if (route === 'clarify') return 'More information is needed before the canvas can be changed safely.';
  return 'No canvas mutation is needed; preparing a direct answer.';
};

export const visualReviewStaleMessage = (useChinese = false) => useChinese
  ? '审阅期间画布已发生变化，因此跳过了过期的视觉审阅结果。'
  : 'The canvas changed during review, so the outdated visual review result was skipped.';

const safeReviewText = (value?: string, maxLength = 500) => {
  const text = (value || '').replace(/```/g, '').replace(/\s+/g, ' ').trim();
  // A JSON-looking summary is a provider/schema leak, not user-facing review content.
  if (!text || text.startsWith('{') || text.startsWith('[')) return '';
  return text.length > maxLength ? `${text.slice(0, maxLength - 3).trim()}...` : text;
};

const humanizeReviewValue = (value?: string) => {
  const text = safeReviewText(value, 80).replace(/[_-]+/g, ' ').toLowerCase();
  return text ? `${text.charAt(0).toUpperCase()}${text.slice(1)}` : '';
};

const issueTypeLabelsChinese: Record<string, string> = {
  TASK_NOT_VISIBLE: '任务内容可见性',
  MISSING_REQUESTED_ELEMENT: '缺失内容',
  WRONG_REQUESTED_RELATIONSHIP: '关系与要求不符',
  TEXT_READABILITY: '文字可读性',
  LAYOUT_HIERARCHY: '布局层级',
  EDGE_TRACEABILITY: '连线可追踪性',
  STYLE_COHERENCE: '样式一致性',
  DOMAIN_UNCERTAINTY: '业务语义',
};

const naturalJoin = (values: string[], useChinese: boolean) => {
  if (values.length <= 1) return values[0] || '';
  if (useChinese) return `${values.slice(0, -1).join('、')}和${values.at(-1)}`;
  return `${values.slice(0, -1).join(', ')} and ${values.at(-1)}`;
};

const reviewIssueTypeLabels = (issues: VisualReviewDisplayIssue[], useChinese: boolean) => unique(
  issues.map(issue => (
    useChinese
      ? issueTypeLabelsChinese[(issue.type || '').toUpperCase()] || safeReviewText(issue.type, 80)
      : humanizeReviewValue(issue.type)
  )),
);

const issueCountLabel = (issues: VisualReviewDisplayIssue[], useChinese: boolean) => {
  const major = issues.filter(issue => issue.severity === 'major' || issue.severity === 'critical').length;
  const minor = issues.filter(issue => issue.severity === 'minor').length;
  const total = issues.length;

  if (useChinese) {
    const parts = [
      major ? `${major} 个主要问题` : '',
      minor ? `${minor} 个轻微问题` : '',
    ].filter(Boolean);
    return parts.length === 2 ? `${parts[0]}和 ${parts[1]}` : parts[0] || `${total} 个问题`;
  }

  const parts = [
    major ? pluralize(major, 'blocking issue', 'blocking issues') : '',
    minor ? pluralize(minor, 'minor note', 'minor notes') : '',
  ].filter(Boolean);
  return parts.length ? naturalJoin(parts, false) : pluralize(total, 'issue', 'issues');
};

export const buildVisualReviewStepDetail = ({
  stage,
  decision,
  summary,
  issues,
  stale = false,
  unavailableReason,
  useChinese = false,
}: {
  stage: VisualReviewStage;
  decision?: VisualReviewDecision;
  summary?: string;
  issues?: VisualReviewDisplayIssue[];
  stale?: boolean;
  unavailableReason?: VisualReviewPresentation['unavailableReason'];
  useChinese?: boolean;
}) => {
  const safeSummary = safeReviewText(summary, 220);
  const safeIssues = (issues || []).slice(0, 5);
  const count = issueCountLabel(safeIssues, useChinese);
  const isPostRepair = stage === 'POST_REPAIR' || stage === 'VERIFY_ONLY';

  if (stale) return visualReviewStaleMessage(useChinese);
  if (decision === 'UNAVAILABLE') {
    if (unavailableReason === 'EXPORT_FAILED') {
      return useChinese
        ? '无法导出审阅截图；当前画布已保留，尚未调用视觉审阅模型。'
        : 'The review screenshot could not be exported. The canvas was kept and the visual reviewer was not called.';
    }
    if (unavailableReason === 'VLM_UNAVAILABLE') {
      return useChinese
        ? '视觉审阅服务暂时不可用；截图已生成，当前画布已保留。'
        : 'The visual review service is temporarily unavailable. Evidence was rendered and the current canvas was kept.';
    }
    return useChinese
      ? '视觉审阅暂时不可用；请求未能完成，当前画布已保留。'
      : 'Visual review is temporarily unavailable; the request could not be completed and the current canvas was kept.';
  }
  if (decision === 'NEEDS_HUMAN_REVIEW') {
    return useChinese
      ? `视觉审阅${safeSummary ? `发现：${safeSummary}` : `发现 ${count}`} 建议人工确认，画布未被自动修改。`
      : `Visual review ${safeSummary ? `found: ${safeSummary}` : `found ${count}`}. Human review is recommended; the canvas was not changed automatically.`;
  }
  if (decision === 'REPAIR') {
    const chineseSummary = safeSummary
      ? `：${safeSummary}${/[。！？.!?]$/.test(safeSummary) ? '' : '。'}`
      : '。';
    return useChinese
      ? `视觉审阅发现 ${count}${chineseSummary}将启动一次局部自动修复。`
      : `Visual review found ${count}${safeSummary ? `: ${safeSummary}` : ''} One bounded local repair will start.`;
  }
  if (decision === 'APPROVE_WITH_NOTES') {
    return useChinese
      ? `${isPostRepair ? '修复后审阅通过' : '视觉审阅通过'}；${safeSummary || `仍保留 ${count} 作为备注。`}`
      : `${isPostRepair ? 'Post-repair review passed' : 'Visual review passed'} with notes${safeSummary ? `: ${safeSummary}` : '.'}`;
  }
  if (decision === 'APPROVE') {
    return useChinese
      ? `${isPostRepair ? '修复后审阅通过' : '视觉审阅通过'}，未发现需要继续处理的问题。`
      : `${isPostRepair ? 'Post-repair review passed' : 'Visual review passed'} with no remaining action required.`;
  }
  return useChinese
    ? '视觉审阅未返回可确认的结论；当前画布已保留。'
    : 'Visual review did not return a conclusive decision; the current canvas was kept.';
};

export const buildVisualRepairStepDetail = ({
  issues,
  useChinese = false,
}: {
  issues?: VisualReviewDisplayIssue[];
  useChinese?: boolean;
}) => {
  const types = reviewIssueTypeLabels((issues || []).slice(0, 5), useChinese);
  if (useChinese) return `正在根据审阅结果修复${types.length ? naturalJoin(types, true) : '已确认的视觉问题'}。`;
  return `Applying the review fixes${types.length ? ` for ${naturalJoin(types, false).toLowerCase()}` : ''}.`;
};

export const buildAgentRunView = ({
  events,
  content,
  isRunning,
}: {
  events?: AgentRunEvent[];
  content?: string;
  isRunning: boolean;
}): AgentRunView => {
  const safeEvents = events || [];
  const hasError = safeEvents.some(event => event.status === 'error');
  const hasWarning = safeEvents.some(event => event.status === 'warning');
  const metric = getLatestMetric(safeEvents);

  const statusTone = hasError ? 'failed' : hasWarning ? 'warning' : isRunning ? 'running' : 'passed';

  return {
    statusTone,
    metricLabel: metric ? `${pluralize(metric.nodes, 'node', 'nodes')} · ${pluralize(metric.edges, 'edge', 'edges')}` : 'Waiting for canvas',
    finalContent: (content || '').trim(),
  };
};

export const buildAgentCompletionReply = (
  view: AgentRunView,
  userRequest?: string,
  visualReviews: VisualReviewPresentation[] = [],
) => {
  if (view.finalContent && visualReviews.length === 0) return view.finalContent;

  const useChinese = usesChinesePresentation(userRequest);
  const finalReview = visualReviews.at(-1);
  const repairReviews = visualReviews.filter(review => review.decision === 'REPAIR');
  const repairReview = repairReviews.at(0);
  const completedRepairCount = repairReviews.filter(review => review.repairCompleted).length;
  const finalSummary = safeReviewText(finalReview?.summary);
  const repairSummary = safeReviewText(repairReview?.summary);

  if (view.metricLabel === 'Waiting for canvas') {
    if (!finalReview) return '';
    return buildVisualReviewStepDetail({ ...finalReview, useChinese });
  }

  const metricLabel = localizeMetricLabel(view.metricLabel, useChinese);

  // Keep the completion copy user-facing while detailed tool output stays in the execution trace.
  if (view.statusTone === 'failed') {
    return useChinese
      ? '图表生成过程中遇到问题，详情里保留了工具输出，方便继续排查。'
      : 'The diagram generation hit a problem. The details panel keeps the tool output for follow-up.';
  }

  if (finalReview) {
    const paragraphs = [
      useChinese
        ? `已完成并加载到 Draw.io 画布，共 ${metricLabel}。`
        : `Done. I loaded the diagram into Draw.io with ${metricLabel}.`,
    ];

    if (completedRepairCount > 0) {
      paragraphs.push(useChinese
        ? `${repairSummary ? `视觉审阅发现：${repairSummary} ` : ''}我根据每轮审阅后的策略判断完成了${completedRepairCount === 1 ? '一次' : ` ${completedRepairCount} 次`}局部自动修复。`
        : `${repairSummary ? `Visual review found: ${repairSummary} ` : ''}I applied ${completedRepairCount} bounded local ${completedRepairCount === 1 ? 'repair' : 'repairs'}, each authorized after review.`);
    } else if (repairReview) {
      paragraphs.push(useChinese
        ? `${repairSummary ? `视觉审阅发现：${repairSummary} ` : ''}审阅建议进行局部修复，但未返回修复后的画布，因此保留了当前版本。`
        : `${repairSummary ? `Visual review found: ${repairSummary} ` : ''}A local repair was requested, but no repaired canvas was returned, so I kept the current version.`);
    }

    if (finalReview.stale) {
      paragraphs.push(visualReviewStaleMessage(useChinese));
    } else if (finalReview.decision === 'UNAVAILABLE') {
      paragraphs.push(buildVisualReviewStepDetail({ ...finalReview, useChinese }));
    } else if (finalReview.decision === 'NEEDS_HUMAN_REVIEW') {
      paragraphs.push(useChinese
        ? `${finalSummary || '仍有无法安全自动处理的问题'} 建议人工确认，我没有继续改动画布。`
        : `${finalSummary || 'Some issues could not be handled safely'}. Human review is recommended, and I did not change the canvas further.`);
    } else if ((finalReview.stage === 'POST_REPAIR' || finalReview.stage === 'VERIFY_ONLY')
      && (finalReview.decision === 'APPROVE' || finalReview.decision === 'APPROVE_WITH_NOTES')) {
      const reviewLabel = finalReview.stage === 'VERIFY_ONLY'
        ? (useChinese ? '修复后复核' : 'Post-repair verification')
        : (useChinese ? '修复后审阅' : 'Post-repair review');
      paragraphs.push(useChinese
        ? `${reviewLabel}${finalReview.decision === 'APPROVE' ? '已通过' : '通过并保留备注'}。${finalSummary || '主要问题已经解决。'}${finalReview.decision === 'APPROVE_WITH_NOTES' ? ' 剩余问题已作为备注保留，没有继续改动画布。' : ''}`
        : `${reviewLabel} ${finalReview.decision === 'APPROVE' ? 'passed' : 'passed with notes'}. ${finalSummary || 'The blocking issues were resolved.'}${finalReview.decision === 'APPROVE_WITH_NOTES' ? ' The remaining notes were kept without another canvas mutation.' : ''}`);
    } else if (finalReview.decision === 'REPAIR' && finalReview.repairCompleted) {
      paragraphs.push(useChinese
        ? '局部修复已经完成，但未能完成修复后复核。'
        : 'The local repair completed, but post-repair visual verification could not be completed.');
    } else if (finalReview.decision === 'APPROVE_WITH_NOTES') {
      paragraphs.push(useChinese
        ? `视觉审阅通过并保留备注。${finalSummary}`
        : `Visual review passed with notes. ${finalSummary}`);
    } else if (finalReview.decision === 'APPROVE' && !repairReview) {
      paragraphs.push(useChinese
        ? `视觉审阅已通过。${finalSummary}`
        : `Visual review passed. ${finalSummary}`);
    } else if (finalReview.stage === 'VERIFY_ONLY') {
      paragraphs.push(useChinese
        ? '修复后复核未返回可确认的结论，当前画布已保留。'
        : 'Post-repair verification did not return a conclusive decision, so the current canvas was kept.');
    } else if (!repairReview) {
      paragraphs.push(useChinese
        ? '视觉审阅未返回可确认的结论，当前画布已保留。'
        : 'Visual review did not return a conclusive decision, so the current canvas was kept.');
    }

    return paragraphs.filter(Boolean).join('\n\n').trim();
  }

  if (view.statusTone === 'warning') {
    return useChinese
      ? `图表已加载到 Draw.io 画布中，当前包含 ${metricLabel}，但还有一些布局或校验提醒可以继续优化。`
      : `The diagram is loaded into the Draw.io canvas with ${metricLabel}, with a few validation notes still available for review.`;
  }

  return useChinese
    ? `已完成图表，并加载到 Draw.io 画布中。当前图表包含 ${metricLabel}。`
    : `Done. I loaded the diagram into the Draw.io canvas with ${metricLabel}.`;
};

export const shouldShowAgentTyping = ({
  role,
  content,
  reasoning,
  eventCount,
  stepCount,
  isLatestRunningAgent,
}: {
  role: string;
  content?: string;
  reasoning?: string;
  eventCount: number;
  stepCount: number;
  isLatestRunningAgent: boolean;
}) => (
  role === 'agent'
  && isLatestRunningAgent
  && !(content || '').trim()
  && !(reasoning || '').trim()
  && eventCount === 0
  && stepCount === 0
);

export const finishPreviousPhaseEvents = (events: AgentRunEvent[], activePhase: string) => (
  events.map(event => (
    event.status === 'running' && event.phase !== activePhase
      ? { ...event, status: 'done' as const }
      : event
  ))
);

export const finishEventsAfterCanvasLoaded = (events: AgentRunEvent[]) => (
  events.map(event => {
    if (event.status === 'running' && event.phase === 'drawing') {
      return { ...event, status: 'done' as const };
    }
    return event;
  })
);

export const getVisibleExecutionSteps = <T extends { status: string }>(steps: T[] | undefined, isRunning: boolean) => {
  if (!isRunning) return [];
  return (steps || []).filter(step => step.status === 'running');
};
