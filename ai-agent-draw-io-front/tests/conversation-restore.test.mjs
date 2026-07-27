import test from 'node:test';
import assert from 'node:assert/strict';

import {
  buildRestoredConversationMessages,
  mergeRestoredConversationPresentation,
  removeLegacyV2DuplicateMessages,
} from '../src/app/drawio/conversation-restore.ts';

test('buildRestoredConversationMessages maps persisted messages to chat messages', () => {
  const messages = buildRestoredConversationMessages([
    {
      clientMessageId: 'user-1',
      role: 'user',
      content: 'Create a flowchart',
      createdAt: '2026-07-01T10:00:00.000Z',
    },
    {
      clientMessageId: 'agent-1',
      role: 'agent',
      content: 'Done',
      createdAt: '2026-07-01T10:00:10.000Z',
    },
  ], 'Checkout Flow');

  assert.deepEqual(messages.map(message => ({
    id: message.id,
    role: message.role,
    content: message.content,
    timestamp: message.timestamp,
  })), [
    {
      id: 'user-1',
      role: 'user',
      content: 'Create a flowchart',
      timestamp: Date.parse('2026-07-01T10:00:00.000Z'),
    },
    {
      id: 'agent-1',
      role: 'agent',
      content: 'Done',
      timestamp: Date.parse('2026-07-01T10:00:10.000Z'),
    },
  ]);
});

test('buildRestoredConversationMessages restores persisted attachment chips', () => {
  const messages = buildRestoredConversationMessages([{
    clientMessageId: 'user-with-file',
    role: 'user',
    content: 'Recreate this image',
    attachmentRefs: ['wireframe.png'],
  }]);

  assert.deepEqual(messages[0].attachments, [{
    uploadId: 'wireframe.png',
    fileName: 'wireframe.png',
    state: 'SUCCEEDED',
  }]);
});

test('buildRestoredConversationMessages restores attachment preview identity', () => {
  const messages = buildRestoredConversationMessages([{
    clientMessageId: 'user-with-preview',
    role: 'user',
    content: 'Recreate this image',
    attachmentRefs: ['upload-1'],
  }], 'Diagram', {
    'upload-1': {
      fileName: 'wireframe.png',
      state: 'SUCCEEDED',
      materialId: 'material-1',
      versionId: 'version-1',
    },
  });

  assert.deepEqual(messages[0].attachments, [{
    uploadId: 'upload-1',
    fileName: 'wireframe.png',
    state: 'SUCCEEDED',
    materialId: 'material-1',
    versionId: 'version-1',
  }]);
});

test('buildRestoredConversationMessages uses loaded fallback when no messages exist', () => {
  const messages = buildRestoredConversationMessages([], 'Checkout Flow');

  assert.equal(messages.length, 1);
  assert.equal(messages[0].role, 'agent');
  assert.equal(messages[0].content, 'Loaded "Checkout Flow".');
});

test('buildRestoredConversationMessages drops blank persisted messages', () => {
  const messages = buildRestoredConversationMessages([
    { clientMessageId: 'bad', role: 'user', content: ' ' },
    { clientMessageId: 'good', role: 'assistant', content: 'Ready' },
  ], 'Untitled Diagram');

  assert.equal(messages.length, 1);
  assert.equal(messages[0].id, 'good');
  assert.equal(messages[0].role, 'agent');
});

test('removeLegacyV2DuplicateMessages drops only the old post-commit user rewrite pattern', () => {
  const messages = removeLegacyV2DuplicateMessages([
    {
      clientMessageId: 'old-assistant-id-used-for-user',
      turnId: 'turn-1',
      role: 'user',
      content: 'Draw a login flow',
      createdAt: '2026-07-27T10:00:00.000Z',
    },
    {
      clientMessageId: 'assistant-turn-1',
      turnId: 'turn-1',
      role: 'agent',
      content: 'Done',
      createdAt: '2026-07-27T10:00:05.000Z',
    },
    {
      clientMessageId: 'local-user-id',
      role: 'user',
      content: '  Draw   a login flow ',
      createdAt: '2026-07-27T10:00:06.000Z',
    },
  ]);

  assert.deepEqual(messages.map(message => message.clientMessageId), [
    'old-assistant-id-used-for-user',
    'assistant-turn-1',
  ]);
});

test('removeLegacyV2DuplicateMessages preserves a deliberate repeated V2 user turn', () => {
  const messages = removeLegacyV2DuplicateMessages([
    {
      clientMessageId: 'user-1',
      turnId: 'turn-1',
      role: 'user',
      content: 'Try again',
      createdAt: '2026-07-27T10:00:00.000Z',
    },
    {
      clientMessageId: 'agent-1',
      turnId: 'turn-1',
      role: 'agent',
      content: 'Done',
      createdAt: '2026-07-27T10:00:05.000Z',
    },
    {
      clientMessageId: 'user-2',
      turnId: 'turn-2',
      role: 'user',
      content: 'Try again',
      createdAt: '2026-07-27T10:00:06.000Z',
    },
    {
      clientMessageId: 'agent-2',
      turnId: 'turn-2',
      role: 'agent',
      content: 'Done again',
      createdAt: '2026-07-27T10:00:10.000Z',
    },
  ]);

  assert.equal(messages.length, 4);
});

test('mergeRestoredConversationPresentation keeps durable data and restores local execution steps', () => {
  const restored = [{
    id: 'assistant-durable',
    role: 'agent',
    content: 'Committed answer',
    timestamp: Date.parse('2026-07-27T10:00:10.000Z'),
    attachments: [{
      uploadId: 'upload-1',
      fileName: 'server-name.png',
      state: 'SUCCEEDED',
    }],
  }];
  const cached = [{
    id: 'assistant-local',
    role: 'agent',
    content: 'Locally rendered answer',
    timestamp: Date.parse('2026-07-27T10:00:09.000Z'),
    routeType: 'create_new',
    language: 'zh',
    steps: [{
      phase: 'analysis',
      label: '分析请求',
      content: '已识别为流程图。',
      status: 'done',
    }],
  }];

  const merged = mergeRestoredConversationPresentation(restored, cached);

  assert.equal(merged[0].id, 'assistant-durable');
  assert.equal(merged[0].content, 'Committed answer');
  assert.equal(merged[0].attachments[0].fileName, 'server-name.png');
  assert.equal(merged[0].routeType, 'create_new');
  assert.equal(merged[0].language, 'zh');
  assert.deepEqual(merged[0].steps, cached[0].steps);
});

test('mergeRestoredConversationPresentation never matches an id across roles', () => {
  const merged = mergeRestoredConversationPresentation([{
    id: 'shared-id',
    role: 'agent',
    content: 'Done',
    timestamp: Date.parse('2026-07-27T10:00:10.000Z'),
  }], [
    {
      id: 'shared-id',
      role: 'user',
      content: 'Done',
      timestamp: Date.parse('2026-07-27T10:00:10.000Z'),
      steps: [{
        phase: 'analysis',
        label: 'Must not leak',
        content: '',
        status: 'done',
      }],
    },
  ]);

  assert.equal(merged[0].steps, undefined);
});
