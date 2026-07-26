import test from 'node:test';
import assert from 'node:assert/strict';

import {
  buildCitationReceipt,
  buildContextReceipts,
} from '../src/features/context/context-receipts.ts';

test('standalone receipt explains current prompt and attachment without technical modes', () => {
  const receipts = buildContextReceipts({
    location: { kind: 'STANDALONE' },
    sourceUse: 'NONE',
    attachments: [{ label: 'wireframe.png', state: 'READY' }],
    useChinese: true,
  });

  assert.deepEqual(receipts.map(receipt => receipt.label), [
    '独立画布', 'wireframe.png', '当前请求',
  ]);
  assert.equal(receipts[1].state, 'attached');
  assert.equal(receipts.some(receipt => /DIRECT|RETRIEVAL|RAG/.test(receipt.label)), false);
});

test('Chartbook and memory receipts expose semantic use and safe management actions', () => {
  const receipts = buildContextReceipts({
    location: { kind: 'CHARTBOOK', label: 'Platform architecture' },
    sourceUse: 'RETRIEVAL',
    memory: { state: 'USED', count: 2 },
    citationCount: 3,
    useChinese: true,
  });

  assert.equal(receipts.find(receipt => receipt.kind === 'location')?.label, 'Chartbook · Platform architecture');
  assert.equal(receipts.find(receipt => receipt.kind === 'source')?.action, 'OPEN_FILES');
  assert.equal(receipts.find(receipt => receipt.kind === 'memory')?.action, 'OPEN_MEMORY');
  assert.equal(receipts.find(receipt => receipt.kind === 'citation')?.action, 'OPEN_CITATIONS');
  assert.equal(receipts.find(receipt => receipt.kind === 'citation')?.label, '3 条资料引用');
});

test('composite use is shown as two semantic receipts and optional skip stays visible', () => {
  const receipts = buildContextReceipts({
    location: { kind: 'CHARTBOOK', label: 'Design system' },
    sourceUse: 'DIRECT_AND_RETRIEVAL',
    optionalEnrichmentSkipped: 'NO_RELEVANT_MATCH',
  });

  assert.deepEqual(receipts.filter(receipt => receipt.kind === 'source').map(receipt => receipt.label), [
    'Attached material', 'Chartbook materials',
  ]);
  assert.equal(receipts.find(receipt => receipt.kind === 'notice')?.state, 'skipped');
});

test('citation receipt does not expose a misleading zero state', () => {
  assert.equal(buildCitationReceipt(0), null);
  assert.equal(buildCitationReceipt(-1), null);
  assert.equal(buildCitationReceipt(1)?.label, '1 source citation');
});
