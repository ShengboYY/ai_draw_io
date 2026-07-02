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

test('resolveWorkspaceIdentity ignores login cookie users until real auth exists', () => {
  const storage = createStorage({ [ANONYMOUS_WORKSPACE_KEY]: 'anon_saved' });

  const identity = resolveWorkspaceIdentity({
    loginUser: ' admin ',
    storage,
    generateId: () => '123e4567-e89b-42d3-a456-426614174000',
  });

  assert.deepEqual(identity, {
    kind: 'anonymous',
    ownerId: 'anon_123e4567-e89b-42d3-a456-426614174000',
  });
});

test('resolveWorkspaceIdentity reuses an existing strong anonymous owner id', () => {
  const storage = createStorage({ [ANONYMOUS_WORKSPACE_KEY]: 'anon_123e4567-e89b-42d3-a456-426614174000' });

  const identity = resolveWorkspaceIdentity({
    storage,
    generateId: () => 'new-id',
  });

  assert.deepEqual(identity, {
    kind: 'anonymous',
    ownerId: 'anon_123e4567-e89b-42d3-a456-426614174000',
  });
});

test('resolveWorkspaceIdentity creates and stores an anonymous owner id', () => {
  const storage = createStorage();

  const identity = resolveWorkspaceIdentity({
    storage,
    generateId: () => '123e4567-e89b-42d3-a456-426614174000',
  });

  assert.deepEqual(identity, {
    kind: 'anonymous',
    ownerId: 'anon_123e4567-e89b-42d3-a456-426614174000',
  });
  assert.equal(storage.value(ANONYMOUS_WORKSPACE_KEY), 'anon_123e4567-e89b-42d3-a456-426614174000');
});

test('resolveWorkspaceIdentity replaces weak stored anonymous owner ids', () => {
  const storage = createStorage({ [ANONYMOUS_WORKSPACE_KEY]: 'anon_saved' });

  const identity = resolveWorkspaceIdentity({
    storage,
    generateId: () => '123e4567-e89b-42d3-a456-426614174000',
  });

  assert.equal(identity.kind, 'anonymous');
  assert.equal(identity.ownerId, 'anon_123e4567-e89b-42d3-a456-426614174000');
  assert.equal(storage.value(ANONYMOUS_WORKSPACE_KEY), 'anon_123e4567-e89b-42d3-a456-426614174000');
});
