import test from 'node:test';
import assert from 'node:assert/strict';

import {
  clearImportedAnonymousWorkspace,
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

test('clearImportedAnonymousWorkspace removes only the imported local workspace id', () => {
  const storage = new MemoryStorage({
    [ANONYMOUS_WORKSPACE_KEY]: 'anon_123e4567-e89b-42d3-a456-426614174000',
  });

  clearImportedAnonymousWorkspace(storage, 'anon_123e4567-e89b-42d3-a456-426614174000');

  assert.equal(storage.getItem(ANONYMOUS_WORKSPACE_KEY), null);
});
