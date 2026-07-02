import test from 'node:test';
import assert from 'node:assert/strict';

import {
  hasPasswordResetConfirmErrors,
  hasPasswordResetRequestErrors,
  passwordResetStatusDisplay,
  validatePasswordResetConfirm,
  validatePasswordResetRequest,
} from '../src/utils/reset-password-form.ts';

test('validatePasswordResetRequest accepts email-like input', () => {
  const errors = validatePasswordResetRequest('  Alice@Example.com ');
  assert.deepEqual(errors, {});
  assert.equal(hasPasswordResetRequestErrors(errors), false);
});

test('validatePasswordResetRequest rejects malformed email', () => {
  const errors = validatePasswordResetRequest('not-an-email');
  assert.equal(errors.email, 'Please enter a valid email address.');
  assert.equal(hasPasswordResetRequestErrors(errors), true);
});

test('validatePasswordResetConfirm flags missing token, short password, and mismatch', () => {
  const errors = validatePasswordResetConfirm('', 'short', 'different');
  assert.ok(errors.token);
  assert.ok(errors.password);
  assert.ok(errors.confirm);
  assert.equal(hasPasswordResetConfirmErrors(errors), true);
});

test('validatePasswordResetConfirm passes valid token and matching password', () => {
  const errors = validatePasswordResetConfirm('reset-token', 'new-password', 'new-password');
  assert.deepEqual(errors, {});
  assert.equal(hasPasswordResetConfirmErrors(errors), false);
});

test('passwordResetStatusDisplay treats success as sign-in ready', () => {
  const display = passwordResetStatusDisplay('SUCCESS');
  assert.equal(display.variant, 'success');
  assert.equal(display.canSignIn, true);
  assert.equal(display.canRequestNew, false);
});

test('passwordResetStatusDisplay makes expired and invalid links requestable', () => {
  assert.equal(passwordResetStatusDisplay('EXPIRED').canRequestNew, true);
  assert.equal(passwordResetStatusDisplay('INVALID').canRequestNew, true);
});

test('passwordResetStatusDisplay treats reused links as requestable and sign-in ready', () => {
  const display = passwordResetStatusDisplay('ALREADY_USED');
  assert.equal(display.canRequestNew, true);
  assert.equal(display.canSignIn, true);
});
