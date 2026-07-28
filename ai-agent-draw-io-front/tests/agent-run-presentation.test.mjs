import test from 'node:test';
import assert from 'node:assert/strict';

import {
  buildAgentRunView,
  buildAgentCompletionReply,
  buildRouteStepDetail,
  buildVisualRepairStepDetail,
  buildVisualReviewStepDetail,
  finishEventsAfterCanvasLoaded,
  finishPreviousPhaseEvents,
  getVisibleExecutionSteps,
  projectUserExecutionStep,
  shouldShowAgentTyping,
  thinkingPhaseLabel,
  thinkingRouteLabel,
  usesChinesePresentation,
  visualReviewUnavailableReason,
  visualReviewStageLabel,
  visualReviewStaleMessage,
} from '../src/app/drawio/agent-run-presentation.ts';

test('thinking labels follow the routed work path', () => {
  assert.equal(thinkingRouteLabel('edit_existing'), 'Edit diagram');
  assert.equal(thinkingPhaseLabel('edit_existing', 'drawing'), 'Drawing');
  assert.equal(thinkingPhaseLabel('optimize_layout', 'reviewing'), 'Deterministic validation');
  assert.equal(thinkingPhaseLabel('answer_only', 'thinking'), 'Prepare answer');
  assert.equal(thinkingPhaseLabel(undefined, 'drawing'), 'Draw diagram');
});

test('internal phases project onto stable user-visible execution stages', () => {
  assert.deepEqual(
    projectUserExecutionStep({ phase: 'analyzing', routeType: 'create_new', useChinese: true }),
    { key: 'analysis', phase: 'analysis', label: '分析请求' },
  );
  assert.deepEqual(
    projectUserExecutionStep({
      phase: 'retrieval',
      routeType: 'create_new',
      sourceUse: 'DIRECT_AND_RETRIEVAL',
      useChinese: true,
    }),
    { key: 'preparation', phase: 'preparation', label: '准备内容' },
  );
  for (const phase of ['drawing', 'generating', 'thinking']) {
    assert.deepEqual(
      projectUserExecutionStep({
        phase,
        routeType: 'create_new',
        sourceUse: 'DIRECT',
        useChinese: true,
      }),
      { key: 'generation', phase: 'generation', label: '重建图表' },
    );
  }
  assert.deepEqual(
    projectUserExecutionStep({ phase: 'answer', useChinese: true }),
    { key: 'generation', phase: 'generation', label: '组织回答' },
  );
  for (const phase of ['reviewing', 'visual_review', 'visual_evidence', 'visual_repair', 'revising']) {
    assert.deepEqual(
      projectUserExecutionStep({ phase, routeType: 'create_new', useChinese: true }),
      { key: 'verification', phase: 'verification', label: '检查结果' },
    );
  }
});

test('route step describes the actual diagram type and selected skill', () => {
  assert.equal(
    buildRouteStepDetail({
      routeType: 'create_new',
      diagramType: 'flowchart',
      skillName: 'drawio-flowchart',
      sourceUse: 'DIRECT',
      useChinese: true,
    }),
    '识别为新建流程图任务，将使用 drawio-flowchart 技能生成画布。来源方式：按原图还原。',
  );
  assert.equal(
    buildRouteStepDetail({
      routeType: 'edit_existing',
      diagramType: 'flowchart',
      skillName: 'drawio-flowchart',
      sourceUse: 'DIRECT_AND_RETRIEVAL',
      useChinese: true,
    }),
    '识别为修改现有流程图，将使用 drawio-flowchart 技能并保留未涉及的画布内容。来源方式：原图还原并允许资料补充。',
  );
  assert.match(
    buildRouteStepDetail({
      routeType: 'create_new',
      diagramType: 'flowchart',
      sourceUse: 'RETRIEVAL',
      useChinese: true,
    }),
    /来源方式：仅检索授权资料/,
  );
  assert.match(
    buildRouteStepDetail({
      routeType: 'create_new',
      diagramType: 'flowchart',
      sourceUse: 'NONE',
      useChinese: true,
    }),
    /来源方式：不使用外部来源/,
  );
});

test('visual review lifecycle uses explicit user-facing stage labels', () => {
  assert.equal(visualReviewStageLabel('POST_MUTATION'), 'Visual review');
  assert.equal(visualReviewStageLabel('POST_REPAIR'), 'Post-repair review');
  assert.equal(visualReviewStageLabel('REPAIR'), 'Visual repair');
  assert.equal(visualReviewStageLabel('VERIFY_ONLY'), 'Final verification');
});

test('completion reply reports both policy-authorized repair rounds', () => {
  const view = buildAgentRunView({
    isRunning: false,
    content: '',
    events: [
      { id: '1', phase: 'drawing', title: 'drawio_done', status: 'done', tone: 'drawing', nodes: 6, edges: 7 },
    ],
  });
  const reply = buildAgentCompletionReply(view, '请画一个登录流程图', [
    { stage: 'POST_MUTATION', visualRepairRound: 0, decision: 'REPAIR', repairCompleted: true },
    { stage: 'POST_REPAIR', visualRepairRound: 1, decision: 'REPAIR', repairCompleted: true },
    { stage: 'VERIFY_ONLY', visualRepairRound: 2, decision: 'APPROVE' },
  ]);

  assert.match(reply, /完成了 2 次局部自动修复/);
  assert.match(reply, /修复后复核已通过/);
});

test('visual review step explains the concrete finding and next action', () => {
  const content = buildVisualReviewStepDetail({
    stage: 'POST_MUTATION',
    decision: 'REPAIR',
    summary: '右侧异常分支的连线追踪存在明显歧义，且主流程节点间距不够统一。',
    issues: [
      { type: 'EDGE_TRACEABILITY', severity: 'major', region: 'center' },
      { type: 'EDGE_TRACEABILITY', severity: 'minor', region: 'center' },
      { type: 'LAYOUT_HIERARCHY', severity: 'minor', region: 'left' },
    ],
    useChinese: true,
  });

  assert.match(content, /1 个主要问题和 2 个轻微问题/);
  assert.match(content, /右侧异常分支的连线追踪存在明显歧义/);
  assert.match(content, /启动一次局部自动修复/);
  assert.equal(
    buildVisualRepairStepDetail({
      issues: [
        { type: 'EDGE_TRACEABILITY', severity: 'major' },
        { type: 'LAYOUT_HIERARCHY', severity: 'minor' },
        { type: 'WRONG_REQUESTED_RELATIONSHIP', severity: 'major' },
      ],
      useChinese: true,
    }),
    '正在根据审阅结果修复连线可追踪性、布局层级和关系与要求不符。',
  );
});

test('visual review warnings explain unavailable, human-review, and stale outcomes', () => {
  assert.match(
    buildVisualReviewStepDetail({ stage: 'POST_MUTATION', decision: 'UNAVAILABLE', useChinese: true }),
    /视觉审阅暂时不可用/,
  );
  assert.match(
    buildVisualReviewStepDetail({
      stage: 'POST_MUTATION',
      decision: 'NEEDS_HUMAN_REVIEW',
      summary: '连接方向存在歧义。',
      useChinese: true,
    }),
    /建议人工确认/,
  );
  assert.equal(visualReviewStaleMessage(true), '审阅期间画布已发生变化，因此跳过了过期的视觉审阅结果。');
  assert.match(
    buildVisualReviewStepDetail({ stage: 'POST_MUTATION', decision: 'UNAVAILABLE', stale: true, useChinese: true }),
    /跳过了过期的视觉审阅结果/,
  );
  assert.match(
    buildVisualReviewStepDetail({ stage: 'VERIFY_ONLY', useChinese: true }),
    /未返回可确认的结论/,
  );
  assert.doesNotMatch(
    buildVisualReviewStepDetail({ stage: 'VERIFY_ONLY', useChinese: true }),
    /通过/,
  );
  const invalidResultReason = visualReviewUnavailableReason(
    false,
    'output_schema_error',
    'NEEDS_HUMAN_REVIEW',
  );
  assert.equal(invalidResultReason, 'REVIEW_RESULT_INVALID');
  const invalidResultMessage = buildVisualReviewStepDetail({
    stage: 'POST_MUTATION',
    decision: 'NEEDS_HUMAN_REVIEW',
    issues: [],
    unavailableReason: invalidResultReason,
    useChinese: true,
  });
  assert.match(invalidResultMessage, /结果无法解析/);
  assert.match(invalidResultMessage, /未执行自动修复/);
  assert.doesNotMatch(invalidResultMessage, /发现 0 个问题/);
});

test('render export failure is distinct from VLM provider unavailability', () => {
  assert.match(
    buildVisualReviewStepDetail({
      stage: 'POST_MUTATION', decision: 'UNAVAILABLE', unavailableReason: 'EXPORT_FAILED', useChinese: true,
    }),
    /无法导出审阅截图/,
  );
  assert.match(
    buildVisualReviewStepDetail({
      stage: 'POST_MUTATION', decision: 'UNAVAILABLE', unavailableReason: 'VLM_UNAVAILABLE', useChinese: true,
    }),
    /视觉审阅服务暂时不可用/,
  );
});

test('buildAgentRunView summarizes local edit tool usage and preserves final text', () => {
  const view = buildAgentRunView({
    isRunning: false,
    content: '已完成局部修改，没有重画整张图。',
    events: [
      { id: '1', phase: 'analyzing', title: 'Intent Router', detail: 'patch_existing', status: 'done', tone: 'analysis' },
      { id: '2', phase: 'drawing', title: 'find_cells', detail: 'Plan Agent', status: 'done', tone: 'tool', tool: 'find_cells' },
      { id: '3', phase: 'drawing', title: 'update_cells', detail: '1 cell updated', status: 'done', tone: 'tool', tool: 'update_cells', scope: 'local' },
      { id: '4', phase: 'reviewing', title: 'validate_diagram', detail: 'XML OK', status: 'done', tone: 'validation', tool: 'validate_diagram', nodes: 8, edges: 6 },
    ],
  });

  assert.equal(view.statusTone, 'passed');
  assert.equal(view.metricLabel, '8 nodes · 6 edges');
  assert.equal(view.finalContent, '已完成局部修改，没有重画整张图。');
});

test('buildAgentRunView reports running drawing progress from the latest event', () => {
  const view = buildAgentRunView({
    isRunning: true,
    content: '',
    events: [
      { id: '1', phase: 'drawing', title: 'drawio_node', detail: 'Added node #3: Gateway', status: 'running', tone: 'drawing', nodes: 3, edges: 1 },
    ],
  });

  assert.equal(view.statusTone, 'running');
  assert.equal(view.metricLabel, '3 nodes · 1 edge');
});

test('buildAgentRunView highlights validation warnings before completion', () => {
  const view = buildAgentRunView({
    isRunning: false,
    content: '',
    events: [
      { id: '1', phase: 'reviewing', title: 'validation_result', detail: '2 overlaps found', status: 'warning', tone: 'validation' },
    ],
  });

  assert.equal(view.statusTone, 'warning');
});

test('buildAgentCompletionReply gives a natural language completion summary', () => {
  const view = buildAgentRunView({
    isRunning: false,
    content: '',
    events: [
      { id: '1', phase: 'drawing', title: 'drawio_done', detail: 'Final canvas loaded', status: 'done', tone: 'drawing', tool: 'display_diagram', nodes: 7, edges: 6 },
    ],
  });

  assert.equal(
    buildAgentCompletionReply(view, '请画一个 AI 绘图系统架构图'),
    '已完成图表，并加载到 Draw.io 画布中。当前图表包含 7 个节点、6 条连线。'
  );
});

test('completion reply merges repair and verification into one specific assistant answer', () => {
  const view = buildAgentRunView({
    isRunning: false,
    content: '',
    events: [
      { id: '1', phase: 'drawing', title: 'drawio_done', status: 'done', tone: 'drawing', nodes: 11, edges: 13 },
    ],
  });
  const reply = buildAgentCompletionReply(view, '请画一个用户登录流程图', [
    {
      stage: 'POST_MUTATION',
      decision: 'REPAIR',
      repairCompleted: true,
      summary: '右侧异常分支的连线追踪存在明显歧义，且主流程节点间距不够统一。',
      issues: [
        { type: 'EDGE_TRACEABILITY', severity: 'major', region: 'center' },
        { type: 'EDGE_TRACEABILITY', severity: 'minor', region: 'center' },
        { type: 'LAYOUT_HIERARCHY', severity: 'minor', region: 'left' },
      ],
    },
    {
      stage: 'VERIFY_ONLY',
      decision: 'APPROVE_WITH_NOTES',
      summary: '主要问题已经解决，右侧失败分支仍有轻微绕行。',
      issues: [{ type: 'EDGE_TRACEABILITY', severity: 'minor', region: 'right' }],
    },
  ]);

  assert.match(reply, /已完成并加载到 Draw\.io 画布，共 11 个节点、13 条连线/);
  assert.match(reply, /完成了一次局部自动修复/);
  assert.match(reply, /修复后复核/);
  assert.match(reply, /右侧失败分支仍有轻微绕行/);
  assert.doesNotMatch(reply, /Issues:|EDGE_TRACEABILITY|A single automatic/);
});

test('completion reply keeps visual verification when Drawer also returned final text', () => {
  const view = buildAgentRunView({
    isRunning: false,
    content: 'Done with the diagram.',
    events: [
      { id: '1', phase: 'drawing', title: 'drawio_done', status: 'done', tone: 'drawing', nodes: 4, edges: 3 },
    ],
  });

  const reply = buildAgentCompletionReply(view, '请画一个登录流程图', [
    { stage: 'VERIFY_ONLY', decision: 'APPROVE', summary: '修复后的连线已经清晰。' },
  ]);

  assert.match(reply, /修复后复核已通过/);
  assert.match(reply, /修复后的连线已经清晰/);
  assert.doesNotMatch(reply, /^Done with the diagram\.$/);
});

test('presentation language follows the dominant request language', () => {
  assert.equal(usesChinesePresentation('Please move the 登录 node to the right.'), false);
  assert.equal(usesChinesePresentation('请把 API Gateway 节点移到右侧。'), true);
});

test('completion reply distinguishes requested, completed, and verified repair states', () => {
  const view = buildAgentRunView({
    isRunning: false,
    content: '',
    events: [
      { id: '1', phase: 'drawing', title: 'drawio_done', status: 'done', tone: 'drawing', nodes: 5, edges: 4 },
    ],
  });
  const requestedOnly = buildAgentCompletionReply(view, '请画一个登录流程图', [
    { stage: 'POST_MUTATION', decision: 'REPAIR', summary: '连线路径需要调整。' },
  ]);
  const repairedOnly = buildAgentCompletionReply(view, '请画一个登录流程图', [
    { stage: 'POST_MUTATION', decision: 'REPAIR', repairCompleted: true, summary: '连线路径需要调整。' },
  ]);
  const inconclusiveVerification = buildAgentCompletionReply(view, '请画一个登录流程图', [
    { stage: 'POST_MUTATION', decision: 'REPAIR', repairCompleted: true, summary: '连线路径需要调整。' },
    { stage: 'VERIFY_ONLY' },
  ]);

  assert.match(requestedOnly, /未返回修复后的画布/);
  assert.doesNotMatch(requestedOnly, /完成了一次局部自动修复/);
  assert.match(repairedOnly, /完成了一次局部自动修复/);
  assert.match(repairedOnly, /未能完成修复后复核/);
  assert.match(inconclusiveVerification, /复核未返回可确认的结论/);
});

test('stale visual review stays inside the single completion reply', () => {
  const view = buildAgentRunView({
    isRunning: false,
    content: '',
    events: [
      { id: '1', phase: 'drawing', title: 'drawio_done', status: 'done', tone: 'drawing', nodes: 5, edges: 4 },
    ],
  });

  const reply = buildAgentCompletionReply(view, '请画一个登录流程图', [
    { stage: 'POST_MUTATION', decision: 'UNAVAILABLE', stale: true },
  ]);

  assert.match(reply, /已完成并加载到 Draw\.io 画布/);
  assert.match(reply, /审阅期间画布已发生变化/);
  assert.doesNotMatch(reply, /temporarily unavailable/);
});

test('buildAgentRunView ignores zero review metrics when no canvas was loaded', () => {
  const view = buildAgentRunView({
    isRunning: false,
    content: 'No changes needed',
    events: [
      { id: '1', phase: 'drawing', title: 'Drawing Agent', detail: 'Generating canvas changes', status: 'done', tone: 'drawing' },
      { id: '2', phase: 'reviewing', title: 'review_result', detail: 'No changes needed', status: 'done', tone: 'review', nodes: 0, edges: 0 },
    ],
  });

  assert.equal(view.metricLabel, 'Waiting for canvas');
});

test('shouldShowAgentTyping hides the idle dots once run events exist', () => {
  assert.equal(shouldShowAgentTyping({
    role: 'agent',
    content: '',
    reasoning: '',
    eventCount: 1,
    stepCount: 0,
    isLatestRunningAgent: true,
  }), false);

  assert.equal(shouldShowAgentTyping({
    role: 'agent',
    content: '',
    reasoning: '',
    eventCount: 0,
    stepCount: 0,
    isLatestRunningAgent: true,
  }), true);
});

test('finishPreviousPhaseEvents closes older phase rows when a new phase starts', () => {
  const events = finishPreviousPhaseEvents([
    { id: '1', phase: 'drawing', title: 'Drawing Agent', detail: 'Generating canvas changes', status: 'running', tone: 'drawing' },
    { id: '2', phase: 'drawing', title: 'drawio_done', detail: 'Final canvas loaded', status: 'done', tone: 'drawing', tool: 'display_diagram' },
  ], 'reviewing');

  assert.equal(events[0].status, 'done');
  assert.equal(events[1].status, 'done');
});

test('getVisibleExecutionSteps only shows the active step while running', () => {
  const steps = [
    { phase: 'analyzing', label: 'Analyze request', content: '', status: 'done' },
    { phase: 'drawing', label: 'Draw diagram', content: '', status: 'running' },
  ];

  assert.deepEqual(getVisibleExecutionSteps(steps, true), [steps[1]]);
  assert.deepEqual(getVisibleExecutionSteps(steps, false), []);
});

test('finishEventsAfterCanvasLoaded closes stale drawing progress rows', () => {
  const events = finishEventsAfterCanvasLoaded([
    { id: '1', phase: 'drawing', title: 'Drawing Agent', detail: 'Generating canvas changes', status: 'running', tone: 'drawing' },
    { id: '2', phase: 'drawing', title: 'Update canvas preview', detail: 'Added edge #6: 展示给用户', status: 'running', tone: 'drawing' },
    { id: '2', phase: 'reviewing', title: 'validate_diagram', detail: 'Diagram XML passed lightweight validation.', status: 'done', tone: 'validation', tool: 'validate_diagram' },
    { id: '3', phase: 'drawing', title: 'drawio_done', detail: 'Final canvas loaded', status: 'done', tone: 'drawing', tool: 'display_diagram' },
  ]);

  assert.equal(events[0].status, 'done');
  assert.equal(events[1].status, 'done');
  assert.equal(events[2].status, 'done');
  assert.equal(events[3].status, 'done');
});
