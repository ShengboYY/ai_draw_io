import test from 'node:test';
import assert from 'node:assert/strict';

import { buildWaterfall, waterfallRowView } from '../src/app/admin/admin-shared.ts';

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

