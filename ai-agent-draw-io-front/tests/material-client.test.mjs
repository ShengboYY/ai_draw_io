import test from 'node:test';
import assert from 'node:assert/strict';

import { MaterialApiError, createMaterialClient } from '../src/api/material.ts';

test('material client completes browser upload and polls until ready without logging S3 policy', async () => {
  const calls = [];
  const responses = [
    { code: '0000', info: 'success', data: { uploadId: 'upl-1', state: 'INITIATED', postPolicy: { url: 'https://s3.example/upload', fields: { key: 'private' } } } },
    { code: '0000', info: 'success', data: { uploadId: 'upl-1', state: 'PROCESSING', materialId: 'mat-1', versionId: 'ver-1' } },
    { code: '0000', info: 'success', data: { uploadId: 'upl-1', state: 'READY', materialId: 'mat-1', versionId: 'ver-1' } },
  ];
  const client = createMaterialClient({
    baseUrl: 'https://app.example/api/v1',
    csrfHeaders: async () => ({ 'X-XSRF-TOKEN': 'csrf' }),
    sleep: async () => {},
    fetch: async (url, options = {}) => {
      calls.push({ url, options });
      if (url === 'https://s3.example/upload') return new Response(null, { status: 204 });
      return new Response(JSON.stringify(responses.shift()), { status: 200 });
    },
  });

  const initiated = await client.initiate({
    displayName: 'diagram.png', mediaType: 'image/png', byteSize: 10, sha256: 'a'.repeat(64),
    target: { scopeType: 'CONVERSATION', scopeId: 'session-1', retentionClass: 'TEMPORARY', diagramId: 'diagram-1' },
  }, 'idem-1');
  await client.uploadBytes(initiated.postPolicy, new Blob(['image']));
  await client.complete('upl-1');
  const terminal = await client.pollStatus('upl-1');

  assert.equal(terminal.state, 'READY');
  assert.deepEqual(calls.map(call => call.url), [
    'https://app.example/api/v1/material-uploads',
    'https://s3.example/upload',
    'https://app.example/api/v1/material-uploads/upl-1/complete',
    'https://app.example/api/v1/material-uploads/upl-1',
  ]);
  assert.equal(calls[0].options.headers['Idempotency-Key'], 'idem-1');
  assert.equal(calls[0].options.headers['X-XSRF-TOKEN'], 'csrf');
});

test('material client exposes S3 upload failures without the signed policy', async () => {
  const client = createMaterialClient({
    baseUrl: 'https://app.example/api/v1',
    csrfHeaders: async () => ({ 'X-XSRF-TOKEN': 'csrf' }),
    fetch: async () => new Response(null, { status: 503 }),
  });

  await assert.rejects(
    () => client.uploadBytes({ url: 'https://s3.example/upload', fields: { policy: 'secret-value' } }, new Blob(['image'])),
    error => error instanceof MaterialApiError && error.code === 'UPLOAD_BYTES_FAILED' && !error.message.includes('secret-value'),
  );
});

test('material client polls processing uploads through partial-ready', async () => {
  const observed = [];
  const statuses = [
    { code: '0000', data: { uploadId: 'upl-4', state: 'PROCESSING' } },
    { code: '0000', data: { uploadId: 'upl-4', state: 'PARTIAL_READY', errorCode: 'OCR_GAP' } },
  ];
  const client = createMaterialClient({
    baseUrl: 'https://app.example/api/v1',
    csrfHeaders: async () => ({}),
    sleep: async () => {},
    fetch: async () => new Response(JSON.stringify(statuses.shift()), { status: 200 }),
  });

  const terminal = await client.pollStatus('upl-4', status => observed.push(status.state));

  assert.equal(terminal.state, 'PARTIAL_READY');
  assert.deepEqual(observed, ['PROCESSING', 'PARTIAL_READY']);
});
