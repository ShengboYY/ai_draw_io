import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { streamErrorMessage } from '../src/app/drawio/stream-error-presentation.ts';

test('stream errors hide raw server payloads and localize known failures', () => {
  const raw = new Error('HTTP_500: {"timestamp":"2026-07-27","path":"/api/v1/chat_stream"}');

  assert.equal(streamErrorMessage(raw, true), '服务器暂时无法处理请求，请重试。');
  assert.equal(
    streamErrorMessage('TURN_V2_BRIDGE_TIMEOUT', false),
    'The request timed out. Please try again.',
  );
  assert.equal(
    streamErrorMessage('CLARIFICATION_DEFERRED', true),
    '还需要确认你希望如何使用附件或修改画布，请补充说明。',
  );
  assert.equal(
    streamErrorMessage('TURN_EXECUTION_FAILED', true),
    '后端执行过程中发生错误，请检查服务日志后重试。',
  );
  assert.equal(
    streamErrorMessage('CANVAS_CONTEXT_CONFLICT', true),
    '生成期间画布内容发生了变化，请重新发送请求。',
  );
});

test('chat history clips horizontal overflow and wraps assistant content', () => {
  const pagePath = fileURLToPath(new URL('../src/app/drawio/page.tsx', import.meta.url));
  const pageSource = readFileSync(pagePath, 'utf8');

  assert.match(pageSource, /overflow-x-hidden[^"]*overflow-y-auto/);
  assert.match(pageSource, /\[overflow-wrap:anywhere\]/);
  assert.match(pageSource, /prose-pre:overflow-x-auto/);
});

test('a terminal stream error suppresses the empty-response fallback', () => {
  const pagePath = fileURLToPath(new URL('../src/app/drawio/page.tsx', import.meta.url));
  const pageSource = readFileSync(pagePath, 'utf8');

  assert.match(pageSource, /let receivedStreamError = false;/);
  assert.match(
    pageSource,
    /emptyResponseMessageAdded \|\| receivedStreamError \|\| agentTextContent \|\| hasIncrementalContent/,
  );
  assert.match(pageSource, /case 'error': \{\s+receivedStreamError = true;/);
});
