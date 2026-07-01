import test from 'node:test';
import assert from 'node:assert/strict';

import { ANONYMOUS_WORKSPACE_KEY, resolveWorkspaceIdentity } from '../src/utils/workspace-identity.ts';

const createStorage = (initial = {}) => {
  const values = new Map(Object.entries(initial));
  return {
    getItem: key => values.has(key) ? values.get(key) : null,
    setItem: (key, value) => values.set(key, value),
    value: key => values.get(key),
  };
};

test('resolveWorkspaceIdentity prefers the login cookie user', () => {
  const storage = createStorage({ [ANONYMOUS_WORKSPACE_KEY]: 'anon_saved' });

  const identity = resolveWorkspaceIdentity({
    loginUser: ' alice ',
    storage,
    generateId: () => 'ignored',
  });

  assert.deepEqual(identity, {
    kind: 'authenticated',
    ownerId: 'alice',
  });
});

test('resolveWorkspaceIdentity reuses an existing anonymous owner id', () => {
  const storage = createStorage({ [ANONYMOUS_WORKSPACE_KEY]: 'anon_saved' });

  const identity = resolveWorkspaceIdentity({
    storage,
    generateId: () => 'new-id',
  });

  assert.deepEqual(identity, {
    kind: 'anonymous',
    ownerId: 'anon_saved',
  });
});

test('resolveWorkspaceIdentity creates and stores an anonymous owner id', () => {
  const storage = createStorage();

  const identity = resolveWorkspaceIdentity({
    storage,
    generateId: () => 'abc-123',
  });

  assert.deepEqual(identity, {
    kind: 'anonymous',
    ownerId: 'anon_abc-123',
  });
  assert.equal(storage.value(ANONYMOUS_WORKSPACE_KEY), 'anon_abc-123');
});

test('resolveWorkspaceIdentity never returns a blank anonymous owner id', () => {
  const identity = resolveWorkspaceIdentity({
    storage: null,
    generateId: () => '',
  });

  assert.equal(identity.kind, 'anonymous');
  assert.match(identity.ownerId, /^anon_/);
  assert.ok(identity.ownerId.length > 'anon_'.length);
});
