import test from 'node:test';
import assert from 'node:assert/strict';

import { buildWaterfall, sourceLabel, traceDisplayName, waterfallRowView } from '../src/app/admin/admin-shared.ts';

test('waterfallRowView tucks flat llm and tool rows under the preceding step', () => {
  const rows = buildWaterfall([
    { id: 'step-routing', source: 'step', phase: 'routing', detail: 'routing', occurredAt: '2026-07-09T10:00:00.000Z', latencyMs: 100 },
    { id: 'llm-routing', source: 'llm_call', phase: 'routing', detail: 'openai/openai', occurredAt: '2026-07-09T10:00:10.000Z', latencyMs: 50 },
    { id: 'step-drawing', source: 'step', phase: 'drawing', detail: 'drawing', occurredAt: '2026-07-09T10:01:00.000Z', latencyMs: 200 },
    { id: 'tool-drawing', source: 'tool_call', phase: 'drawing', detail: 'create_diagram', occurredAt: '2026-07-09T10:01:20.000Z', latencyMs: 25 },
  ]);

  assert.deepEqual(rows.map((row, index) => waterfallRowView(rows, index).visualDepth), [0, 1, 0, 1]);
  assert.deepEqual(rows.map((row, index) => waterfallRowView(rows, index).compact), [false, true, false, true]);
  assert.deepEqual(rows.map((row, index) => waterfallRowView(rows, index).startsStepGroup), [false, false, true, false]);
});

test('buildWaterfall renders unified diagram trace spans with run-root nesting', () => {
  const rows = buildWaterfall([
    { id: 'run-1', kind: 'RUN', name: 'chat_stream', startedAt: '2026-07-09T10:00:00.000Z', latencyMs: 200 },
    { id: 'step-1', parentId: 'run-1', kind: 'STEP', name: 'drawing', startedAt: '2026-07-09T10:00:10.000Z', latencyMs: 120 },
    { id: 'llm-1', parentId: 'step-1', kind: 'LLM', name: 'openai/gpt-test', startedAt: '2026-07-09T10:00:15.000Z', latencyMs: 80 },
    { id: 'tool-1', parentId: 'step-1', kind: 'TOOL', name: 'create_diagram', startedAt: '2026-07-09T10:00:30.000Z', latencyMs: 10 },
  ]);

  assert.deepEqual(rows.map((row) => row.event.id), ['run-1', 'step-1', 'llm-1', 'tool-1']);
  assert.deepEqual(rows.map((row) => row.depth), [0, 1, 2, 2]);
  assert.equal(sourceLabel(rows[2].event.kind), 'llm');
  assert.equal(traceDisplayName(rows[3].event), 'create_diagram');
});
