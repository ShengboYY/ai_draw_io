import test from 'node:test';
import assert from 'node:assert/strict';

import {
  applyDemoQuotaConsumption,
  buildDemoQuotaState,
  demoQuotaExhaustedMessage,
  isDemoQuotaErrorCode,
} from '../src/app/drawio/demo-quota.ts';

test('buildDemoQuotaState shows anonymous default-model quota', () => {
  const state = buildDemoQuotaState({
    account: {
      ownerId: 'anon_123',
      ownerType: 'ANONYMOUS',
      authenticated: false,
      emailVerified: false,
      accountStatus: 'ANONYMOUS',
      demoQuotaLimit: 5,
      demoQuotaUsed: 2,
      demoQuotaRemaining: 3,
      demoQuotaExhausted: false,
    },
    selectedCustomModelId: 'default',
    customModels: [],
  });

  assert.equal(state.visible, true);
  assert.equal(state.remaining, 3);
  assert.equal(state.exhausted, false);
  assert.equal(state.label, '3 demo AI requests left');
});

test('buildDemoQuotaState hides quota when an anonymous user selects their own key', () => {
  const state = buildDemoQuotaState({
    account: {
      ownerId: 'anon_123',
      ownerType: 'ANONYMOUS',
      authenticated: false,
      emailVerified: false,
      accountStatus: 'ANONYMOUS',
      demoQuotaLimit: 5,
      demoQuotaUsed: 5,
      demoQuotaRemaining: 0,
      demoQuotaExhausted: true,
    },
    selectedCustomModelId: 'own-key',
    customModels: [{ id: 'own-key', enabled: true, apiKey: 'sk-user' }],
  });

  assert.equal(state.visible, false);
  assert.equal(state.exhausted, false);
});

test('applyDemoQuotaConsumption marks the last anonymous demo request exhausted', () => {
  const nextAccount = applyDemoQuotaConsumption({
    ownerId: 'anon_123',
    ownerType: 'ANONYMOUS',
    authenticated: false,
    emailVerified: false,
    accountStatus: 'ANONYMOUS',
    demoQuotaLimit: 5,
    demoQuotaUsed: 4,
    demoQuotaRemaining: 1,
    demoQuotaExhausted: false,
  });

  assert.equal(nextAccount.demoQuotaUsed, 5);
  assert.equal(nextAccount.demoQuotaRemaining, 0);
  assert.equal(nextAccount.demoQuotaExhausted, true);
});

test('demo quota errors are recognized by typed code', () => {
  assert.equal(isDemoQuotaErrorCode('DEMO_QUOTA_EXHAUSTED'), true);
  assert.equal(isDemoQuotaErrorCode('AUTH_RATE_LIMITED'), false);
  assert.match(demoQuotaExhaustedMessage, /Sign up|add your own API key/);
});
