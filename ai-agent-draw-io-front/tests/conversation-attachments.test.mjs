import test from 'node:test';
import assert from 'node:assert/strict';

import {
  readConversationAttachments,
  writeConversationAttachments,
} from '../src/features/sources/conversation-attachments.ts';

test('conversation attachments restore opaque upload state for the current session', () => {
  const storage = new Map();
  const sessionStorage = {
    getItem: key => storage.get(key) || null,
    setItem: (key, value) => storage.set(key, value),
  };
  const attachments = [{ uploadId: 'upl-1', fileName: 'guide.pdf', state: 'PROCESSING' }];

  writeConversationAttachments(sessionStorage, 'session-1', attachments);

  assert.deepEqual(readConversationAttachments(sessionStorage, 'session-1'), attachments);
  assert.deepEqual(readConversationAttachments(sessionStorage, 'session-2'), []);
});
