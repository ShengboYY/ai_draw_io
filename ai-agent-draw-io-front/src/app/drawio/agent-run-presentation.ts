export type AgentRunEventStatus = 'running' | 'done' | 'warning' | 'error';
export type AgentRunEventTone = 'analysis' | 'drawing' | 'tool' | 'validation' | 'review';
export type AgentRunScope = 'full' | 'local' | 'append' | 'layout' | 'review';

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

export type AgentRunDisplayEvent = AgentRunEvent & {
  statusLabel: string;
};

export type AgentRunView = {
  title: string;
  statusLabel: string;
  statusTone: 'running' | 'passed' | 'warning' | 'failed';
  scopeLabel: string;
  toolLabel: string;
  metricLabel: string;
  finalContent: string;
  visibleEvents: AgentRunDisplayEvent[];
};

const unique = (values: string[]) => Array.from(new Set(values.filter(Boolean)));

const pluralize = (count: number, singular: string, plural: string) => `${count} ${count === 1 ? singular : plural}`;

const containsCjk = (text?: string) => /[\u3400-\u9fff]/.test(text || '');

const localizeMetricLabel = (metricLabel: string, useChinese: boolean) => {
  const match = metricLabel.match(/^(\d+) nodes? · (\d+) edges?$/);
  if (!match || !useChinese) return metricLabel;
  return `${match[1]} 个节点、${match[2]} 条连线`;
};

const actionLabels: Record<string, string> = {
  'intent router': 'Understand request',
  agent_intent: 'Understand request',
  'drawing agent': 'Draw diagram',
  agent_drawer: 'Draw diagram',
  drawio_node: 'Add node',
  drawio_edge: 'Add connector',
  drawio_preview: 'Update canvas preview',
  'streaming nodes': 'Update canvas preview',
  'streaming edges': 'Update canvas preview',
  'update canvas preview': 'Update canvas preview',
  drawio_done: 'Load canvas',
  drawio: 'Load canvas',
  display_diagram: 'Load canvas',
  validate_diagram: 'Validate diagram',
  validation_result: 'Validate diagram',
  reviewer: 'Review diagram',
  agent_reviewer: 'Review diagram',
  'revision agent': 'Revise diagram',
  update_cells: 'Update selected items',
  find_cells: 'Find target items',
  append_diagram: 'Append to diagram',
  optimize_diagram: 'Optimize layout',
  route_edges: 'Route connectors',
};

const statusLabels: Record<AgentRunEventStatus, string> = {
  running: 'In progress',
  done: 'Done',
  warning: 'Needs attention',
  error: 'Failed',
};

const actionLabelFor = (event: Pick<AgentRunEvent, 'title' | 'tool'>) => {
  const titleKey = (event.title || '').trim().toLowerCase();
  if (actionLabels[titleKey]) return actionLabels[titleKey];

  const rawValue = (event.tool || event.title || '').trim();
  const key = rawValue.toLowerCase();
  if (actionLabels[key]) return actionLabels[key];

  if (key.includes('validate')) return 'Validate diagram';
  if (key.includes('display') || key.includes('done')) return 'Load canvas';
  if (key.includes('draw')) return 'Draw diagram';
  if (key.includes('review')) return 'Review diagram';
  if (key.includes('update')) return 'Update selected items';
  if (key.includes('route')) return 'Route connectors';
  return 'Process step';
};

const detailLabelFor = (event: AgentRunEvent) => {
  const detail = event.detail?.replace(/\s+/g, ' ').trim() || '';
  const title = actionLabelFor(event);
  const lowerDetail = detail.toLowerCase();

  if (!detail) return '';
  if (title === 'Validate diagram' && (lowerDetail.includes('xml ok') || lowerDetail.includes('passed lightweight validation'))) {
    return 'Diagram structure looks valid.';
  }
  if (title === 'Draw diagram' && lowerDetail.includes('generating canvas changes')) {
    return 'Creating canvas changes.';
  }
  if (title === 'Load canvas' && lowerDetail.includes('final canvas loaded')) {
    return 'Final canvas loaded.';
  }
  if (title === 'Update canvas preview' && lowerDetail.includes('loaded preview skeleton')) {
    return 'Started canvas preview.';
  }
  return detail;
};

const toDisplayEvent = (event: AgentRunEvent): AgentRunDisplayEvent => ({
  ...event,
  title: actionLabelFor(event),
  detail: detailLabelFor(event),
  statusLabel: statusLabels[event.status],
});

const getLatestMetric = (events: AgentRunEvent[]) => {
  for (let i = events.length - 1; i >= 0; i--) {
    const event = events[i];
    if (typeof event.nodes === 'number' || typeof event.edges === 'number') {
      return {
        nodes: event.nodes || 0,
        edges: event.edges || 0,
      };
    }
  }

  return null;
};

const inferScope = (events: AgentRunEvent[]) => {
  const explicitScope = events.find(event => event.scope)?.scope;
  if (explicitScope) return explicitScope;

  const tools = events.map(event => event.tool || event.title);
  if (tools.some(tool => tool === 'update_cells' || tool === 'find_cells')) return 'local';
  if (tools.some(tool => tool === 'append_diagram')) return 'append';
  if (tools.some(tool => tool === 'route_edges' || tool === 'optimize_diagram')) return 'layout';
  if (events.some(event => event.phase === 'reviewing')) return 'review';
  return 'full';
};

const scopeTitles: Record<AgentRunScope, string> = {
  full: 'Drawing diagram',
  local: 'Local edit',
  append: 'Appending content',
  layout: 'Optimizing layout',
  review: 'Reviewing quality',
};

const scopeLabels: Record<AgentRunScope, string> = {
  full: 'Full canvas',
  local: 'Local patch',
  append: 'Append only',
  layout: 'Layout repair',
  review: 'Quality review',
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
  const scope = inferScope(safeEvents);
  const hasError = safeEvents.some(event => event.status === 'error');
  const hasWarning = safeEvents.some(event => event.status === 'warning');
  const tools = unique(safeEvents.map(event => actionLabelFor(event)));
  const metric = getLatestMetric(safeEvents);

  // Keep the chat card compact by showing the latest high-signal execution events.
  const visibleEvents = safeEvents.slice(-6).map(toDisplayEvent);

  const statusTone = hasError ? 'failed' : hasWarning ? 'warning' : isRunning ? 'running' : 'passed';
  const statusLabel = hasError ? 'Failed' : hasWarning ? 'Needs attention' : isRunning ? 'Running' : 'Completed';

  return {
    title: scopeTitles[scope],
    statusLabel,
    statusTone,
    scopeLabel: scopeLabels[scope],
    toolLabel: tools.length ? tools.join(' → ') : 'No tool yet',
    metricLabel: metric ? `${pluralize(metric.nodes, 'node', 'nodes')} · ${pluralize(metric.edges, 'edge', 'edges')}` : 'Waiting for canvas',
    finalContent: (content || '').trim(),
    visibleEvents,
  };
};

export const buildAgentCompletionReply = (view: AgentRunView, userRequest?: string) => {
  if (view.finalContent) return view.finalContent;
  if (view.metricLabel === 'Waiting for canvas') return '';

  const useChinese = containsCjk(userRequest);
  const metricLabel = localizeMetricLabel(view.metricLabel, useChinese);

  // Keep the completion copy user-facing while detailed tool output stays in the run details panel.
  if (view.statusTone === 'warning') {
    return useChinese
      ? `图表已加载到 Draw.io 画布中，当前包含 ${metricLabel}，但还有一些布局或校验提醒可以继续优化。`
      : `The diagram is loaded into the Draw.io canvas with ${metricLabel}, with a few validation notes still available for review.`;
  }

  if (view.statusTone === 'failed') {
    return useChinese
      ? '图表生成过程中遇到问题，详情里保留了工具输出，方便继续排查。'
      : 'The diagram generation hit a problem. The details panel keeps the tool output for follow-up.';
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

export const shouldShowAgentProgressCard = ({
  role,
  eventCount,
  isLatestRunningAgent,
}: {
  role: string;
  eventCount: number;
  isLatestRunningAgent: boolean;
}) => role === 'agent' && (eventCount > 0 || isLatestRunningAgent);

export const finishPreviousPhaseEvents = (events: AgentRunEvent[], activePhase: string) => (
  events.map(event => (
    event.status === 'running' && event.phase !== activePhase
      ? { ...event, status: 'done' as const }
      : event
  ))
);

export const finishEventsAfterCanvasLoaded = (events: AgentRunEvent[]) => (
  events.map(event => {
    const label = actionLabelFor(event);
    if (event.status === 'running' && (label === 'Draw diagram' || label === 'Update canvas preview')) {
      return { ...event, status: 'done' as const };
    }
    return event;
  })
);

export const getVisibleExecutionSteps = <T extends { status: string }>(steps: T[] | undefined, isRunning: boolean) => {
  if (!isRunning) return [];
  return (steps || []).filter(step => step.status === 'running');
};
