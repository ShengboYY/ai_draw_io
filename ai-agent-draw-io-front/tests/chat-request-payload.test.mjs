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
    maxDeterministicRepairRounds: 2,
    skills: ['drawio-architecture'],
  });

  assert.equal(request.message, '把 API 改成 Gateway');
  assert.equal(request.canvasXml, xml);
  assert.equal(request.canvasSummary, 'The canvas contains 1 node and 0 edges. Main labels: API.');
  assert.equal(request.modelCredentialId, 'mcr_gpt55');
  assert.equal(request.maxDeterministicRepairRounds, 2);
  assert.equal('maxReviewIterations' in request, false);
  assert.deepEqual(request.skills, ['drawio-architecture']);
  assert.deepEqual(request.clientHints, {
    maxDeterministicRepairRounds: 2,
    skills: ['drawio-architecture'],
  });
  assert.doesNotMatch(request.message, /Current Draw\.io XML|mxGraphModel|Canvas Summary/);
});

test('buildDrawioChatRequestPayload generates a request id for request tracing', () => {
  const request = buildDrawioChatRequestPayload({
    agentId: '300000',
    userId: 'alice',
    sessionId: 'session-1',
    userMessage: 'draw checkout flow',
  });

  assert.equal(typeof request.requestId, 'string');
  assert.match(request.requestId, /^[A-Za-z0-9._:-]{8,128}$/);
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

test('buildDrawioChatRequestPayload never serializes legacy per-message source controls', () => {
  const request = buildDrawioChatRequestPayload({
    agentId: '300000',
    userId: 'usr_alice',
    sessionId: 'session-1',
    userMessage: 'Use the current conversation files',
    attachmentUploadIds: ['upl-image-1'],
    sourceMode: 'EXPLICIT_ONLY',
    selectedVersionIds: ['ver-1'],
    sourceUseOverride: 'DIRECT',
  });

  assert.equal('attachmentUploadIds' in request, false);
  assert.equal('sourceMode' in request, false);
  assert.equal('selectedVersionIds' in request, false);
  assert.equal('sourceUseOverride' in request, false);
});

test('buildDrawioChatRequestPayload carries bounded direct image clarifications', () => {
  const payload = buildDrawioChatRequestPayload({
    agentId: '300000',
    userId: 'usr_alice',
    sessionId: 'session-1',
    userMessage: 'Restore this image',
    directClarifications: [
      {
        reasonCode: 'UNRESOLVED_EDGE_DIRECTION:e1',
        resolution: 'FORWARD',
        observedFingerprint: 'edge-fingerprint',
      },
    ],
    directConfirmationSourceVersionId: 'version-1',
  });

  assert.deepEqual(payload.directClarifications, [
    {
      reasonCode: 'UNRESOLVED_EDGE_DIRECTION:e1',
      resolution: 'FORWARD',
      observedFingerprint: 'edge-fingerprint',
    },
  ]);
  assert.equal(payload.directConfirmationSourceVersionId, 'version-1');
});

test('buildDrawioChatRequestPayload carries the current rendered PNG for review-only routing', () => {
  const request = buildDrawioChatRequestPayload({
    agentId: '300000',
    userId: 'alice',
    sessionId: 'session-1',
    userMessage: 'review this diagram',
    canvasImageDataUrl: 'data:image/png;base64,AAAA',
    canvasImageRendererVersion: 'drawio-embed-png-v1',
  });

  assert.equal(request.canvasImageDataUrl, 'data:image/png;base64,AAAA');
  assert.equal(request.canvasImageRendererVersion, 'drawio-embed-png-v1');
});

test('buildDrawioChatRequestPayload includes compact conversation context', () => {
  const request = buildDrawioChatRequestPayload({
    agentId: '300000',
    userId: 'alice',
    sessionId: 'session-1',
    userMessage: 'restaurant order system',
    conversationMessages: [
      {
        id: 'msg-1',
        role: 'user',
        content: 'Create a UML class diagram',
        steps: [{ label: 'ignored' }],
      },
      {
        id: 'msg-2',
        role: 'agent',
        content: 'Please provide the topic/domain. <mxGraphModel><root><mxCell value="hidden" vertex="1"/></root></mxGraphModel>',
        events: [{ title: 'ignored' }],
      },
    ],
  });

  assert.deepEqual(request.conversationMessages, [
    {
      clientMessageId: 'msg-1',
      role: 'user',
      content: 'Create a UML class diagram',
    },
    {
      clientMessageId: 'msg-2',
      role: 'agent',
      content: 'Please provide the topic/domain.',
    },
  ]);
  assert.doesNotMatch(JSON.stringify(request.conversationMessages), /mxGraphModel|hidden|ignored/);
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
