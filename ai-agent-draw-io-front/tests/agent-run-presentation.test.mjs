import test from 'node:test';
import assert from 'node:assert/strict';

import {
  buildAgentRunView,
  buildAgentProgressSummary,
  buildAgentCompletionReply,
  finishEventsAfterCanvasLoaded,
  finishPreviousPhaseEvents,
  getVisibleExecutionSteps,
  shouldShowAgentProgressCard,
  shouldShowAgentTyping,
} from '../src/app/drawio/agent-run-presentation.ts';

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

  assert.equal(view.title, 'Local edit');
  assert.equal(view.statusLabel, 'Completed');
  assert.equal(view.statusTone, 'passed');
  assert.equal(view.toolLabel, 'Understand request → Find target items → Update selected items → Validate diagram');
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

  assert.equal(view.title, 'Drawing diagram');
  assert.equal(view.statusLabel, 'Running');
  assert.equal(view.statusTone, 'running');
  assert.equal(view.metricLabel, '3 nodes · 1 edge');
  assert.equal(view.visibleEvents[0].detail, 'Added node #3: Gateway');
});

test('buildAgentRunView highlights validation warnings before completion', () => {
  const view = buildAgentRunView({
    isRunning: false,
    content: '',
    events: [
      { id: '1', phase: 'reviewing', title: 'validation_result', detail: '2 overlaps found', status: 'warning', tone: 'validation' },
    ],
  });

  assert.equal(view.statusLabel, 'Needs attention');
  assert.equal(view.statusTone, 'warning');
  assert.equal(view.title, 'Reviewing quality');
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

test('buildAgentProgressSummary does not claim completion when no canvas loaded', () => {
  const view = buildAgentRunView({
    isRunning: false,
    content: '用户请求创建图表，但模型只返回了说明文字。',
    events: [
      { id: '1', phase: 'drawing', title: 'Drawing Agent', detail: 'Generating canvas changes', status: 'done', tone: 'drawing' },
    ],
  });

  assert.equal(
    buildAgentProgressSummary(view, false),
    '没有加载到可绘制的图表。Agent 返回了文字说明，但没有返回 Draw.io XML。'
  );
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
  assert.equal(
    buildAgentProgressSummary(view, false),
    'No drawable diagram was loaded. The agent returned text instead of Draw.io XML.'
  );
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

test('buildAgentRunView uses user-facing action names instead of backend names', () => {
  const view = buildAgentRunView({
    isRunning: false,
    content: '',
    events: [
      { id: '1', phase: 'drawing', title: 'Drawing Agent', detail: 'Generating canvas changes', status: 'done', tone: 'drawing' },
      { id: '2', phase: 'reviewing', title: 'validate_diagram', detail: 'Diagram XML passed lightweight validation.', status: 'done', tone: 'validation', tool: 'validate_diagram' },
      { id: '3', phase: 'drawing', title: 'drawio_done', detail: 'Final canvas loaded', status: 'done', tone: 'drawing', tool: 'display_diagram', nodes: 9, edges: 4 },
    ],
  });

  assert.equal(view.toolLabel, 'Draw diagram → Validate diagram → Load canvas');
  assert.equal(view.visibleEvents[0].title, 'Draw diagram');
  assert.equal(view.visibleEvents[0].statusLabel, 'Done');
  assert.equal(view.visibleEvents[1].title, 'Validate diagram');
  assert.equal(view.visibleEvents[1].detail, 'Diagram structure looks valid.');
  assert.equal(view.visibleEvents[2].title, 'Load canvas');
});

test('buildAgentRunView labels streamed canvas updates without repeating load canvas', () => {
  const view = buildAgentRunView({
    isRunning: true,
    content: '',
    events: [
      { id: '1', phase: 'drawing', title: 'Drawing Agent', detail: 'Generating canvas changes', status: 'done', tone: 'drawing' },
      { id: '2', phase: 'drawing', title: 'drawio_preview', detail: 'Loaded preview skeleton', status: 'done', tone: 'drawing', tool: 'display_diagram', nodes: 1, edges: 0 },
      { id: '3', phase: 'drawing', title: 'Streaming nodes', detail: 'Added node #7: draw.io 图表', status: 'running', tone: 'drawing', tool: 'display_diagram', nodes: 7, edges: 5 },
      { id: '4', phase: 'drawing', title: 'Streaming edges', detail: 'Added edge #6: 展示给用户', status: 'running', tone: 'drawing', tool: 'display_diagram', nodes: 7, edges: 6 },
      { id: '5', phase: 'reviewing', title: 'validation_result', detail: 'XML OK', status: 'done', tone: 'validation', tool: 'validate_diagram', nodes: 7, edges: 6 },
    ],
  });

  assert.equal(view.toolLabel, 'Draw diagram → Update canvas preview → Validate diagram');
  assert.equal(view.visibleEvents[1].title, 'Update canvas preview');
  assert.equal(view.visibleEvents[2].title, 'Update canvas preview');
  assert.equal(view.visibleEvents.at(-1).title, 'Validate diagram');
});

test('getVisibleExecutionSteps only shows the active step while running', () => {
  const steps = [
    { phase: 'analyzing', label: 'Analyze request', content: '', status: 'done' },
    { phase: 'drawing', label: 'Draw diagram', content: '', status: 'running' },
  ];

  assert.deepEqual(getVisibleExecutionSteps(steps, true), [steps[1]]);
  assert.deepEqual(getVisibleExecutionSteps(steps, false), []);
});

test('shouldShowAgentProgressCard appears immediately for the active agent message', () => {
  assert.equal(shouldShowAgentProgressCard({
    role: 'agent',
    eventCount: 0,
    isLatestRunningAgent: true,
  }), true);

  assert.equal(shouldShowAgentProgressCard({
    role: 'agent',
    eventCount: 0,
    isLatestRunningAgent: false,
  }), false);
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
