import test from 'node:test';
import assert from 'node:assert/strict';

import {
  clearAnonymousWorkspaceImportNotice,
  clearImportedAnonymousWorkspace,
  readAnonymousWorkspaceImportNotice,
  rememberAnonymousWorkspaceImportDeclined,
  rememberAnonymousWorkspaceImportResult,
  shouldPromptAnonymousWorkspaceImport,
} from '../src/utils/anonymous-workspace-import.ts';
import { ANONYMOUS_WORKSPACE_KEY } from '../src/utils/workspace-identity.ts';

class MemoryStorage {
  constructor(values = {}) {
    this.values = new Map(Object.entries(values));
  }

  getItem(key) {
    return this.values.get(key) ?? null;
  }

  setItem(key, value) {
    this.values.set(key, value);
  }

  removeItem(key) {
    this.values.delete(key);
  }
}

test('shouldPromptAnonymousWorkspaceImport detects a valid anonymous workspace after login', () => {
  const storage = new MemoryStorage({
    [ANONYMOUS_WORKSPACE_KEY]: 'anon_123e4567-e89b-42d3-a456-426614174000',
  });

  const decision = shouldPromptAnonymousWorkspaceImport({
    loginStatus: 'SUCCESS',
    storage,
  });

  assert.equal(decision.shouldPrompt, true);
  assert.equal(decision.anonymousWorkspaceId, 'anon_123e4567-e89b-42d3-a456-426614174000');
});

test('shouldPromptAnonymousWorkspaceImport rejects invalid anonymous workspace ids', () => {
  const storage = new MemoryStorage({
    [ANONYMOUS_WORKSPACE_KEY]: 'admin',
  });

  const decision = shouldPromptAnonymousWorkspaceImport({
    loginStatus: 'SUCCESS',
    storage,
  });

  assert.equal(decision.shouldPrompt, false);
  assert.equal(decision.anonymousWorkspaceId, '');
});

test('shouldPromptAnonymousWorkspaceImport only prompts after successful login', () => {
  const storage = new MemoryStorage({
    [ANONYMOUS_WORKSPACE_KEY]: 'anon_123e4567-e89b-42d3-a456-426614174000',
  });

  const decision = shouldPromptAnonymousWorkspaceImport({
    loginStatus: 'INVALID_CREDENTIALS',
    storage,
  });

  assert.equal(decision.shouldPrompt, false);
});

test('shouldPromptAnonymousWorkspaceImport does not prompt again after this user declines', () => {
  const anonymousWorkspaceId = 'anon_123e4567-e89b-42d3-a456-426614174000';
  const storage = new MemoryStorage({
    [ANONYMOUS_WORKSPACE_KEY]: anonymousWorkspaceId,
  });

  rememberAnonymousWorkspaceImportDeclined(storage, anonymousWorkspaceId, 'usr_alice');

  const decision = shouldPromptAnonymousWorkspaceImport({
    loginStatus: 'SUCCESS',
    storage,
    targetUserId: 'usr_alice',
  });

  assert.equal(decision.shouldPrompt, false);
  assert.equal(decision.anonymousWorkspaceId, anonymousWorkspaceId);
});

test('shouldPromptAnonymousWorkspaceImport still prompts a different user after another user declines', () => {
  const anonymousWorkspaceId = 'anon_123e4567-e89b-42d3-a456-426614174000';
  const storage = new MemoryStorage({
    [ANONYMOUS_WORKSPACE_KEY]: anonymousWorkspaceId,
  });

  rememberAnonymousWorkspaceImportDeclined(storage, anonymousWorkspaceId, 'usr_alice');

  const decision = shouldPromptAnonymousWorkspaceImport({
    loginStatus: 'SUCCESS',
    storage,
    targetUserId: 'usr_bob',
  });

  assert.equal(decision.shouldPrompt, true);
  assert.equal(decision.anonymousWorkspaceId, anonymousWorkspaceId);
});

test('clearImportedAnonymousWorkspace removes only the imported local workspace id', () => {
  const storage = new MemoryStorage({
    [ANONYMOUS_WORKSPACE_KEY]: 'anon_123e4567-e89b-42d3-a456-426614174000',
  });

  clearImportedAnonymousWorkspace(storage, 'anon_123e4567-e89b-42d3-a456-426614174000');

  assert.equal(storage.getItem(ANONYMOUS_WORKSPACE_KEY), null);
});

test('readAnonymousWorkspaceImportNotice formats a plural import result', () => {
  const storage = new MemoryStorage();

  rememberAnonymousWorkspaceImportResult(storage, 3);

  assert.deepEqual(readAnonymousWorkspaceImportNotice(storage), {
    importedCount: 3,
    message: 'Imported 3 works from this browser.',
  });
});

test('readAnonymousWorkspaceImportNotice formats a singular import result', () => {
  const storage = new MemoryStorage();

  rememberAnonymousWorkspaceImportResult(storage, 1);

  assert.deepEqual(readAnonymousWorkspaceImportNotice(storage), {
    importedCount: 1,
    message: 'Imported 1 work from this browser.',
  });
});

test('readAnonymousWorkspaceImportNotice ignores empty or invalid import results', () => {
  const storage = new MemoryStorage();

  rememberAnonymousWorkspaceImportResult(storage, 0);
  assert.equal(readAnonymousWorkspaceImportNotice(storage), null);

  storage.setItem('ai_draw_io_anonymous_import_result', '{"importedCount":"many"}');
  assert.equal(readAnonymousWorkspaceImportNotice(storage), null);
});

test('clearAnonymousWorkspaceImportNotice removes the stored import notice', () => {
  const storage = new MemoryStorage();

  rememberAnonymousWorkspaceImportResult(storage, 2);
  clearAnonymousWorkspaceImportNotice(storage);

  assert.equal(readAnonymousWorkspaceImportNotice(storage), null);
});
