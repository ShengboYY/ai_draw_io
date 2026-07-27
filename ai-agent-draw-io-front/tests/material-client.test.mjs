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

test('material client uploads local bytes through the API with session and CSRF protection', async () => {
  const calls = [];
  const file = new Blob(['local-image']);
  const client = createMaterialClient({
    baseUrl: 'https://app.example/api/v1',
    csrfHeaders: async () => ({ 'X-XSRF-TOKEN': 'csrf' }),
    fetch: async (url, options = {}) => {
      calls.push({ url, options });
      return new Response(null, { status: 204 });
    },
  });

  await client.uploadBytes({
    url: '/material-uploads/upl-local/content',
    fields: { 'x-ai-upload-mode': 'local' },
  }, file);

  assert.equal(calls[0].url, 'https://app.example/api/v1/material-uploads/upl-local/content');
  assert.equal(calls[0].options.method, 'POST');
  assert.equal(calls[0].options.credentials, 'include');
  assert.equal(calls[0].options.headers['X-XSRF-TOKEN'], 'csrf');
  assert.equal(calls[0].options.headers['Content-Type'], 'application/octet-stream');
  assert.equal(calls[0].options.body, file);
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

test('material client stops polling when the upload session succeeds', async () => {
  let requestCount = 0;
  const client = createMaterialClient({
    baseUrl: 'https://app.example/api/v1',
    csrfHeaders: async () => ({}),
    sleep: async () => {},
    fetch: async () => {
      requestCount += 1;
      // UploadSession uses SUCCEEDED after the processing revision is published.
      return new Response(JSON.stringify({
        code: '0000',
        data: { uploadId: 'upl-success', state: 'SUCCEEDED' },
      }), { status: 200 });
    },
  });

  const terminal = await client.pollStatus('upl-success');

  assert.equal(terminal.state, 'SUCCEEDED');
  assert.equal(requestCount, 1);
});

test('material client requests details and page metadata without fetching source bytes', async () => {
  const calls = [];
  const responses = [
    { code: '0000', data: { material: { materialId: 'mat-1', displayName: 'guide.pdf' }, versions: [{ versionId: 'ver-1', versionNo: 1 }], scopes: [] } },
    { code: '0000', data: { materialId: 'mat-1', versionId: 'ver-1', revisionId: 'rev-1', revisionNo: 1, processingStatus: 'READY', progress: 100, excludedPages: [], pages: [{ pageNo: 1, previewAvailable: true }] } },
  ];
  const client = createMaterialClient({
    baseUrl: 'https://app.example/api/v1',
    fetch: async (url, options = {}) => {
      calls.push({ url, options });
      return new Response(JSON.stringify(responses.shift()), { status: 200 });
    },
  });

  const details = await client.details('mat-1');
  const pages = await client.pageSet('mat-1', 'ver-1');

  assert.equal(details.versions[0].versionId, 'ver-1');
  assert.equal(pages.pages[0].pageNo, 1);
  assert.deepEqual(calls.map(call => call.url), [
    'https://app.example/api/v1/materials/mat-1',
    'https://app.example/api/v1/materials/mat-1/versions/ver-1/pages',
  ]);
  assert.equal(calls.every(call => call.options.method === undefined), true);
});

test('material client lists only metadata in an owned diagram scope', async () => {
  const calls = [];
  const client = createMaterialClient({
    baseUrl: 'https://app.example/api/v1',
    fetch: async (url, options = {}) => {
      calls.push({ url, options });
      return new Response(JSON.stringify({ code: '0000', data: { items: [], total: 0, limit: 100, offset: 0 } }));
    },
  });

  await client.listScope('DIAGRAM', 'diagram-1', { lifecycleState: 'ACTIVE', limit: 100 });

  assert.equal(calls[0].url, 'https://app.example/api/v1/materials/scopes/DIAGRAM/diagram-1?lifecycleState=ACTIVE&limit=100');
  assert.equal(calls[0].options.method, undefined);
});

test('material client lists conversation files and builds exact-version download URLs', async () => {
  const calls = [];
  const client = createMaterialClient({
    baseUrl: 'https://app.example/api/v1',
    fetch: async (url, options = {}) => {
      calls.push({ url, options });
      return new Response(JSON.stringify({ code: '0000', data: { items: [], total: 0, limit: 100, offset: 0 } }));
    },
  });

  await client.listScope('CONVERSATION', 'conversation/1', { lifecycleState: 'ACTIVE', limit: 100 });

  assert.equal(calls[0].url,
    'https://app.example/api/v1/materials/scopes/CONVERSATION/conversation%2F1?lifecycleState=ACTIVE&limit=100');
  assert.equal(client.downloadUrl('material/1', 'version/1'),
    'https://app.example/api/v1/materials/material%2F1/versions/version%2F1/download');
});

test('material client protects scope and recycle-bin mutations with CSRF and idempotency', async () => {
  const calls = [];
  const client = createMaterialClient({
    baseUrl: 'https://app.example/api/v1',
    csrfHeaders: async () => ({ 'X-XSRF-TOKEN': 'csrf' }),
    fetch: async (url, options = {}) => {
      calls.push({ url, options });
      return new Response(JSON.stringify({ code: '0000', data: { materialId: 'mat-1', lifecycleState: 'TRASHED' } }));
    },
  });

  await client.addScope('mat-1', 'CHARTBOOK', 'cb-1');
  await client.remove('mat-1', 'idem-remove');
  await client.restore('mat-1', 'idem-restore');

  assert.equal(calls[0].options.headers['X-XSRF-TOKEN'], 'csrf');
  assert.equal(calls[1].options.headers['Idempotency-Key'], 'idem-remove');
  assert.equal(calls[2].options.headers['Idempotency-Key'], 'idem-restore');
});
