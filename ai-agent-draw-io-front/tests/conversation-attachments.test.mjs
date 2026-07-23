import test from 'node:test';
import assert from 'node:assert/strict';

import {
  addAttachmentToCurrentSelection,
  reconcileAttachmentSelection,
  readConversationAttachments,
  readConversationAttachmentSelection,
  selectedAttachmentIdsForRequest,
  writeConversationAttachments,
  writeConversationAttachmentSelection,
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

test('conversation attachment selection is restored independently from the session pool', () => {
  const storage = new Map();
  const sessionStorage = {
    getItem: key => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, value),
  };

  writeConversationAttachmentSelection(sessionStorage, 'session-1', ['upl-2']);

  assert.deepEqual(readConversationAttachmentSelection(sessionStorage, 'session-1'), ['upl-2']);
  assert.equal(readConversationAttachmentSelection(sessionStorage, 'session-2'), null);
});

test('current selection keeps only attachments in the session pool and selects new uploads', () => {
  const pool = [
    { uploadId: 'upl-1', fileName: 'guide.pdf', state: 'READY' },
    { uploadId: 'upl-2', fileName: 'diagram.png', state: 'PROCESSING' },
  ];

  assert.deepEqual(reconcileAttachmentSelection(pool, ['upl-2', 'upl-missing']), ['upl-2']);
  assert.deepEqual(addAttachmentToCurrentSelection(['upl-1'], 'upl-2'), ['upl-1', 'upl-2']);
  assert.deepEqual(addAttachmentToCurrentSelection(['upl-1'], 'upl-1'), ['upl-1']);
});

test('request declaration preserves selected terminal attachments for a typed server stop', () => {
  const pool = [
    { uploadId: 'upl-ready', fileName: 'guide.pdf', state: 'READY' },
    { uploadId: 'upl-rejected', fileName: 'bad.pdf', state: 'REJECTED' },
  ];

  assert.deepEqual(
    selectedAttachmentIdsForRequest(pool, ['upl-ready', 'upl-rejected']),
    ['upl-ready', 'upl-rejected'],
  );
});
