import test from 'node:test';
import assert from 'node:assert/strict';

import {
  createHighlightAction,
  parseSelectionEvent,
} from '../src/app/drawio/secure-drawio-bridge.ts';

const frameWindow = {};

test('parseSelectionEvent accepts only the exact iframe origin and source window', () => {
  const selection = parseSelectionEvent({
    eventOrigin: 'https://drawio.internal.example',
    expectedOrigin: 'https://drawio.internal.example',
    eventSource: frameWindow,
    expectedSource: frameWindow,
    data: JSON.stringify({
      protocol: 'zipp-drawio-v1',
      event: 'zippSelection',
      cellIds: ['node-1', 'edge-2', 'node-1'],
      canvasVersion: 7,
      contentHash: 'sha256:abc123',
    }),
  });

  assert.deepEqual(selection, {
    cellIds: ['node-1', 'edge-2'],
    canvasVersion: 7,
    contentHash: 'sha256:abc123',
  });
  assert.equal(parseSelectionEvent({
    eventOrigin: 'https://drawio.internal.example.attacker.test',
    expectedOrigin: 'https://drawio.internal.example',
    eventSource: frameWindow,
    expectedSource: frameWindow,
    data: '{}',
  }), null);
  assert.equal(parseSelectionEvent({
    eventOrigin: 'https://drawio.internal.example',
    expectedOrigin: 'https://drawio.internal.example',
    eventSource: {},
    expectedSource: frameWindow,
    data: '{}',
  }), null);
});

test('createHighlightAction carries the server canvas freshness tuple', () => {
  assert.deepEqual(createHighlightAction(['node-1', 'node-2'], 4, 'sha256:canvas'), {
    protocol: 'zipp-drawio-v1',
    action: 'zippHighlight',
    cellIds: ['node-1', 'node-2'],
    canvasVersion: 4,
    contentHash: 'sha256:canvas',
  });
});
