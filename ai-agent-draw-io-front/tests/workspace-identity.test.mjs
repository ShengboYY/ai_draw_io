import test from 'node:test';
import assert from 'node:assert/strict';

import {
  ANONYMOUS_WORKSPACE_KEY,
  rememberAnonymousWorkspaceHint,
  resolveWorkspaceIdentity,
} from '../src/utils/workspace-identity.ts';

const createStorage = (initial = {}) => {
  const values = new Map(Object.entries(initial));
  return {
    getItem: key => values.has(key) ? values.get(key) : null,
    setItem: (key, value) => values.set(key, value),
    value: key => values.get(key),
  };
};

test('resolveWorkspaceIdentity reuses a server-issued owner id only as a UI hint', () => {
  const storage = createStorage({
    [ANONYMOUS_WORKSPACE_KEY]: 'anon_123e4567-e89b-42d3-a456-426614174000',
  });

  assert.deepEqual(resolveWorkspaceIdentity({ storage }), {
    kind: 'anonymous',
    ownerId: 'anon_123e4567-e89b-42d3-a456-426614174000',
  });
});

test('resolveWorkspaceIdentity never manufactures an owner id in the browser', () => {
  const storage = createStorage();

  assert.equal(resolveWorkspaceIdentity({ storage }), null);
  assert.equal(storage.value(ANONYMOUS_WORKSPACE_KEY), undefined);
});

test('rememberAnonymousWorkspaceHint stores only a validated server response', () => {
  const storage = createStorage();

  assert.equal(rememberAnonymousWorkspaceHint(storage, 'admin'), false);
  assert.equal(rememberAnonymousWorkspaceHint(
    storage,
    'anon_123e4567-e89b-42d3-a456-426614174000',
  ), true);
  assert.equal(
    storage.value(ANONYMOUS_WORKSPACE_KEY),
    'anon_123e4567-e89b-42d3-a456-426614174000',
  );
});
