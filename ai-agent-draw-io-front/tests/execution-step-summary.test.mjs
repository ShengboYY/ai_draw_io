import test from 'node:test';
import assert from 'node:assert/strict';

import {
  buildStepSummary,
  sanitizeStepNote,
} from '../src/app/drawio/execution-step-summary.ts';

test('buildStepSummary returns structured analysis bullets', () => {
  const summary = buildStepSummary({
    phase: 'analyzing',
    userRequest: '帮我画一个用户登录到下单支付的业务流程图',
  });

  assert.match(summary, /Working summary:/);
  assert.match(summary, /用户登录到下单支付/);
  assert.match(summary, /diagram type/);
  assert.match(summary, /^- /m);
});

test('buildStepSummary keeps drawing progress counts visible', () => {
  const summary = buildStepSummary({ phase: 'drawing', nodeCount: 4, edgeCount: 3 });

  assert.match(summary, /Current canvas: 4 nodes, 3 edges/);
  assert.match(summary, /complete canvas draft/);
});

test('sanitizeStepNote rejects raw stream artifacts', () => {
  assert.equal(sanitizeStepNote('<mxCell id="1" vertex="1" />'), '');
  assert.equal(sanitizeStepNote('{"type":"drawio_node","xml":"<mxCell />"}'), '');
});

test('buildStepSummary adds a concise readable note', () => {
  const summary = buildStepSummary({
    phase: 'reviewing',
    note: 'Need to check edge crossings and improve grouping before the next pass.',
    userRequest: '帮我画一个用户登录到下单支付的业务流程图',
  });

  assert.match(summary, /Quality checklist:/);
  assert.match(summary, /branch labels/);
  assert.match(summary, /Current note: Need to check edge crossings/);
});

test('buildStepSummary makes revision concrete for the requested diagram type', () => {
  const summary = buildStepSummary({
    phase: 'revising',
    userRequest: '帮我画一个用户登录到下单支付的业务流程图',
  });

  assert.match(summary, /Revision brief:/);
  assert.match(summary, /flowchart\/process diagram/);
  assert.match(summary, /branch labels/);
  assert.doesNotMatch(summary, /relationship correctness/);
});

test('buildStepSummary makes thinking concrete for non-drawing phases', () => {
  const summary = buildStepSummary({
    phase: 'thinking',
    userRequest: '画一个订单系统微服务架构图，包括网关、订单、支付、库存和数据库',
  });

  assert.match(summary, /Working summary:/);
  assert.match(summary, /architecture diagram/);
  assert.match(summary, /gateway, services, stores, and external dependencies/);
});

test('buildStepSummary keeps generic analysis useful when no request is available', () => {
  const summary = buildStepSummary({ phase: 'analyzing' });

  assert.match(summary, /Current request: waiting for request details/);
});

test('sanitizeStepNote drops partial token-sized notes', () => {
  assert.equal(sanitizeStepNote('Need'), '');
  assert.equal(sanitizeStepNote('Need to check edge crossings.'), 'Need to check edge crossings.');
});
