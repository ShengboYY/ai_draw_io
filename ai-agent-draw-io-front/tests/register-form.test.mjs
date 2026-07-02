import test from 'node:test';
import assert from 'node:assert/strict';

import {
  MIN_PASSWORD_LENGTH,
  hasErrors,
  isValidEmail,
  validateRegisterForm,
  verificationDisplay,
} from '../src/utils/register-form.ts';

test('isValidEmail accepts trimmed lowercase and mixed-case addresses', () => {
  assert.equal(isValidEmail('user@example.com'), true);
  assert.equal(isValidEmail('  User@Example.COM '), true);
});

test('isValidEmail rejects blank, missing-at, and missing-domain inputs', () => {
  assert.equal(isValidEmail(''), false);
  assert.equal(isValidEmail('   '), false);
  assert.equal(isValidEmail('no-at-sign'), false);
  assert.equal(isValidEmail('missing@domain'), false);
  assert.equal(isValidEmail(null), false);
  assert.equal(isValidEmail(undefined), false);
});

test('validateRegisterForm flags every problem it finds', () => {
  const errors = validateRegisterForm('bad-email', 'short', 'mismatch');
  assert.ok(errors.email);
  assert.ok(errors.password);
  assert.ok(errors.confirm);
  assert.equal(hasErrors(errors), true);
});

test('validateRegisterForm passes when everything is valid', () => {
  const errors = validateRegisterForm('alice@example.com', 'longenough', 'longenough');
  assert.deepEqual(errors, {});
  assert.equal(hasErrors(errors), false);
});

test('validateRegisterForm requires the exact minimum password length', () => {
  const password = 'a'.repeat(MIN_PASSWORD_LENGTH - 1);
  const errors = validateRegisterForm('alice@example.com', password, password);
  assert.ok(errors.password);
});

test('verificationDisplay renders a friendly success message and hides resend', () => {
  const display = verificationDisplay('SUCCESS');
  assert.equal(display.variant, 'success');
  assert.equal(display.canResend, false);
});

test('verificationDisplay treats EXPIRED and INVALID as resendable', () => {
  assert.equal(verificationDisplay('EXPIRED').canResend, true);
  assert.equal(verificationDisplay('INVALID').canResend, true);
});

test('verificationDisplay treats ALREADY_USED as final (no resend)', () => {
  assert.equal(verificationDisplay('ALREADY_USED').canResend, false);
});

test('verificationDisplay falls back to invalid when status is null', () => {
  const display = verificationDisplay(null);
  assert.equal(display.variant, 'error');
  assert.equal(display.canResend, true);
});
