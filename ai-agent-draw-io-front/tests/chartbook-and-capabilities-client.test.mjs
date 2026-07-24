import test from 'node:test';
import assert from 'node:assert/strict';

import { createChartbookClient } from '../src/api/chartbook.ts';
import { createMaterialCapabilitiesClient } from '../src/api/material-capabilities.ts';

test('chartbook client sends CSRF and idempotency metadata for creation', async () => {
  const calls = [];
  const client = createChartbookClient({
    baseUrl: 'https://app.example/api/v1',
    csrfHeaders: async () => ({ 'X-XSRF-TOKEN': 'csrf' }),
    fetch: async (url, options = {}) => {
      calls.push({ url, options });
      return new Response(JSON.stringify({ code: '0000', data: { chartbookId: 'cb-1', name: 'Agile', status: 'ACTIVE', diagramIds: [], materialIds: [], createdAt: 'now', updatedAt: 'now' } }));
    },
  });

  const chartbook = await client.create('Agile', 'idem-1');

  assert.equal(chartbook.chartbookId, 'cb-1');
  assert.equal(calls[0].url, 'https://app.example/api/v1/chartbooks');
  assert.equal(calls[0].options.headers['Idempotency-Key'], 'idem-1');
  assert.equal(calls[0].options.headers['X-XSRF-TOKEN'], 'csrf');
});

test('chartbook client leaves CSRF off read-only requests', async () => {
  let csrfCalls = 0;
  const client = createChartbookClient({
    baseUrl: 'https://app.example/api/v1',
    csrfHeaders: async () => {
      csrfCalls += 1;
      return { 'X-XSRF-TOKEN': 'csrf' };
    },
    fetch: async (_url, options = {}) => {
      assert.equal(options.headers?.['X-XSRF-TOKEN'], undefined);
      return new Response(JSON.stringify({ code: '0000', data: [] }));
    },
  });

  assert.deepEqual(await client.list(), []);
  assert.equal(csrfCalls, 0);
});

test('chartbook client accepts empty successful archive responses', async () => {
  const client = createChartbookClient({
    baseUrl: 'https://app.example/api/v1',
    csrfHeaders: async () => ({ 'X-XSRF-TOKEN': 'csrf' }),
    fetch: async () => new Response(JSON.stringify({ code: '0000' })),
  });

  await assert.doesNotReject(() => client.archive('cb-1'));
});

test('chartbook client adds a file through the idempotent unified endpoint', async () => {
  const calls = [];
  const client = createChartbookClient({
    baseUrl: 'https://app.example/api/v1',
    csrfHeaders: async () => ({ 'X-XSRF-TOKEN': 'csrf' }),
    fetch: async (url, options = {}) => {
      calls.push({ url, options });
      return new Response(JSON.stringify({
        code: '0000',
        data: {
          material: { materialId: 'material/1', retentionClass: 'RETAINED' },
          versions: [],
          scopes: [{ linkId: 'scope-1', scopeType: 'CHARTBOOK', scopeKey: 'chartbook/1' }],
        },
      }));
    },
  });

  const file = await client.addFile('chartbook/1', 'material/1', 'add-file-1');

  assert.equal(file.material.retentionClass, 'RETAINED');
  assert.equal(calls[0].url,
    'https://app.example/api/v1/chartbooks/chartbook%2F1/files/material%2F1');
  assert.equal(calls[0].options.method, 'POST');
  assert.equal(calls[0].options.headers['Idempotency-Key'], 'add-file-1');
  assert.equal(calls[0].options.headers['X-XSRF-TOKEN'], 'csrf');
});

test('capability client reads stable disabled states without CSRF', async () => {
  const client = createMaterialCapabilitiesClient({
    baseUrl: 'https://app.example/api/v1',
    fetch: async () => new Response(JSON.stringify({ code: '0000', data: { upload: 'DISABLED', catalog: 'DISABLED', preview: 'DISABLED', retrieval: 'DISABLED', denseRetrieval: 'DISABLED', visualObservation: 'DISABLED', directImageConversion: 'DISABLED', anonymousUpload: 'DISABLED', acceptedMimeTypes: ['application/pdf'], maxBatchFiles: 10 } })),
  });

  const capabilities = await client.get();

  assert.equal(capabilities.upload, 'DISABLED');
  assert.equal(capabilities.maxBatchFiles, 10);
});
