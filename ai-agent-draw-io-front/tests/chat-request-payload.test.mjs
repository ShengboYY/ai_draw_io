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
    modelCredentialId: 'mcr_gpt55',
    maxReviewIterations: 2,
    skills: ['drawio-architecture'],
  });

  assert.equal(request.message, '把 API 改成 Gateway');
  assert.equal(request.canvasXml, xml);
  assert.equal(request.canvasSummary, 'The canvas contains 1 node and 0 edges. Main labels: API.');
  assert.equal(request.modelCredentialId, 'mcr_gpt55');
  assert.equal(request.maxReviewIterations, 2);
  assert.deepEqual(request.skills, ['drawio-architecture']);
  assert.deepEqual(request.clientHints, {
    maxReviewIterations: 2,
    skills: ['drawio-architecture'],
  });
  assert.doesNotMatch(request.message, /Current Draw\.io XML|mxGraphModel|Canvas Summary/);
});

test('buildDrawioChatRequestPayload includes canvas state version fields', () => {
  const request = buildDrawioChatRequestPayload({
    agentId: '300000',
    userId: 'alice',
    sessionId: 'session-1',
    userMessage: '整理一下连线',
    diagramId: 'diagram-1',
    expectedVersion: 4,
  });

  assert.equal(request.diagramId, 'diagram-1');
  assert.equal(request.expectedVersion, 4);
});

test('buildDrawioChatRequestPayload sends saved credential id without raw custom key fields', () => {
  const request = buildDrawioChatRequestPayload({
    agentId: '300000',
    userId: 'usr_alice',
    sessionId: 'session-1',
    userMessage: 'draw it',
    modelCredentialId: 'mcr_alice',
    customBaseUrl: 'https://api.openai.com/v1',
    customApiKey: 'sk-raw-secret',
    customCompletionsPath: '/chat/completions',
    customModel: 'gpt-4o',
  });

  assert.equal(request.modelCredentialId, 'mcr_alice');
  assert.equal('customBaseUrl' in request, false);
  assert.equal('customApiKey' in request, false);
  assert.equal('customCompletionsPath' in request, false);
  assert.equal('customModel' in request, false);
});
