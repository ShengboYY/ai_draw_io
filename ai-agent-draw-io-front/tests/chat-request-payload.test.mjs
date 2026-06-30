import test from 'node:test';
import assert from 'node:assert/strict';

import { buildDrawioChatRequestPayload } from '../src/app/drawio/chat-request-payload.ts';

test('buildDrawioChatRequestPayload keeps user text separate from canvas context', () => {
  const xml = '<mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/><mxCell id="2" value="API" vertex="1" parent="1"/></root></mxGraphModel>';

  const request = buildDrawioChatRequestPayload({
    agentId: '300000',
    userId: 'alice',
    sessionId: 'session-1',
    userMessage: '把 API 改成 Gateway',
    canvasXml: xml,
    canvasSummary: 'The canvas contains 1 node and 0 edges. Main labels: API.',
    customModel: 'gpt-5.5',
    maxReviewIterations: 2,
    skills: ['drawio-architecture'],
  });

  assert.equal(request.message, '把 API 改成 Gateway');
  assert.equal(request.canvasXml, xml);
  assert.equal(request.canvasSummary, 'The canvas contains 1 node and 0 edges. Main labels: API.');
  assert.equal(request.customModel, 'gpt-5.5');
  assert.equal(request.maxReviewIterations, 2);
  assert.deepEqual(request.skills, ['drawio-architecture']);
  assert.deepEqual(request.clientHints, {
    maxReviewIterations: 2,
    skills: ['drawio-architecture'],
  });
  assert.doesNotMatch(request.message, /Current Draw\.io XML|mxGraphModel|Canvas Summary/);
});
