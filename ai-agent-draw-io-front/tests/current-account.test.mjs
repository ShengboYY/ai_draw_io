import test from 'node:test';
import assert from 'node:assert/strict';

import { workspaceLabelFromAccount } from '../src/utils/current-account.ts';

test('workspaceLabelFromAccount uses anonymous account status from backend', () => {
  assert.equal(workspaceLabelFromAccount({
    ownerId: 'anon_123e4567-e89b-42d3-a456-426614174000',
    ownerType: 'ANONYMOUS',
    authenticated: false,
    emailVerified: false,
    accountStatus: 'ANONYMOUS',
  }, 'user@example.com'), 'Local workspace');
});

test('workspaceLabelFromAccount falls back to local anonymous identity before status loads', () => {
  assert.equal(workspaceLabelFromAccount(null, 'anon_123e4567-e89b-42d3-a456-426614174000'), 'Local workspace');
});

test('workspaceLabelFromAccount displays signed-in state for authenticated owners', () => {
  assert.equal(workspaceLabelFromAccount({
    ownerId: 'user_123',
    ownerType: 'USER',
    authenticated: true,
    emailVerified: true,
    accountStatus: 'ACTIVE',
  }, 'anon_123e4567-e89b-42d3-a456-426614174000'), 'Signed-in workspace');
});
