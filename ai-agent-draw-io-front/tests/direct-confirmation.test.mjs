import test from 'node:test';
import assert from 'node:assert/strict';

import {
  buildDirectClarifications,
  directConfirmationIssue,
} from '../src/features/sources/direct-confirmation.ts';

test('direction ambiguity requires an explicit bounded direction choice', () => {
  assert.deepEqual(
    directConfirmationIssue('UNRESOLVED_EDGE_DIRECTION:e1'),
    {
      reasonCode: 'UNRESOLVED_EDGE_DIRECTION:e1',
      targetLabel: '连线 e1',
      prompt: '这条连线的箭头方向是什么？',
      options: [
        { value: 'FORWARD', label: '正向' },
        { value: 'REVERSE', label: '反向' },
        { value: 'BIDIRECTIONAL', label: '双向' },
        { value: 'UNDIRECTED', label: '无方向' },
      ],
    },
  );
});

test('confirmation request is withheld until every issue has a selected resolution', () => {
  const issues = [
    {
      reasonCode: 'UNRESOLVED_EDGE_DIRECTION:e1',
      observedValue: 'a → b',
      observedFingerprint: 'edge-fingerprint',
    },
    {
      reasonCode: 'LOW_CONFIDENCE_NODE_TEXT:n2',
      observedValue: 'Approve order',
      observedFingerprint: 'text-fingerprint',
    },
  ];

  assert.equal(buildDirectClarifications(issues, {
    'UNRESOLVED_EDGE_DIRECTION:e1': 'FORWARD',
  }), null);
  assert.equal(buildDirectClarifications([
    { reasonCode: 'UNRESOLVED_EDGE_DIRECTION:e1', observedValue: 'a → b' },
  ], {
    'UNRESOLVED_EDGE_DIRECTION:e1': 'FORWARD',
  }), null);
  assert.deepEqual(buildDirectClarifications(issues, {
    'UNRESOLVED_EDGE_DIRECTION:e1': 'FORWARD',
    'LOW_CONFIDENCE_NODE_TEXT:n2': 'ACCEPT_OBSERVED',
  }), [
    {
      reasonCode: 'UNRESOLVED_EDGE_DIRECTION:e1',
      resolution: 'FORWARD',
      observedFingerprint: 'edge-fingerprint',
    },
    {
      reasonCode: 'LOW_CONFIDENCE_NODE_TEXT:n2',
      resolution: 'ACCEPT_OBSERVED',
      observedFingerprint: 'text-fingerprint',
    },
  ]);
});

test('low-confidence text shows the exact observed value before acceptance', () => {
  assert.equal(
    directConfirmationIssue('LOW_CONFIDENCE_NODE_TEXT:n2', 'Approve order').prompt,
    '是否接受当前识别出的节点文字“Approve order”？',
  );
  assert.equal(
    directConfirmationIssue('UNRESOLVED:abc123', 'Treat the arrow as forward').prompt,
    '是否按当前识别结果“Treat the arrow as forward”继续？',
  );
});
