import assert from 'node:assert/strict';
import test from 'node:test';

import {
  readConversationLibrarySelections,
  writeConversationLibrarySelections,
} from '../src/features/sources/conversation-library-selections.ts';

const memoryStorage = () => {
  const values = new Map();
  return {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
  };
};

test('Library selections persist per conversation and discard malformed entries', () => {
  const storage = memoryStorage();
  writeConversationLibrarySelections(storage, 'session-1', [
    {
      materialId: 'material-1',
      versionId: 'version-1',
      displayName: 'Architecture.pdf',
      kind: 'PDF',
    },
  ]);

  assert.deepEqual(readConversationLibrarySelections(storage, 'session-1'), [
    {
      materialId: 'material-1',
      versionId: 'version-1',
      displayName: 'Architecture.pdf',
      kind: 'PDF',
    },
  ]);

  storage.setItem('drawio_conversation_library_selections:session-2', JSON.stringify([
    { materialId: 'material-2', versionId: '', displayName: 'Broken.pdf', kind: 'PDF' },
    { materialId: 'material-3', versionId: 'version-3', displayName: 'Valid.png', kind: 'IMAGE' },
  ]));
  assert.deepEqual(readConversationLibrarySelections(storage, 'session-2'), [
    { materialId: 'material-3', versionId: 'version-3', displayName: 'Valid.png', kind: 'IMAGE' },
  ]);
});
