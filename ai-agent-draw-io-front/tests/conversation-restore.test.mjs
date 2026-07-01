import test from 'node:test';
import assert from 'node:assert/strict';

import { buildRestoredConversationMessages } from '../src/app/drawio/conversation-restore.ts';

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
