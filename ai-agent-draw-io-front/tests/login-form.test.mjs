import test from 'node:test';
import assert from 'node:assert/strict';

import {
  buildLoginHref,
  hasLoginErrors,
  loginStatusDisplay,
  resolvePostLoginRedirect,
  validateLoginForm,
} from '../src/utils/login-form.ts';

test('validateLoginForm accepts a well-formed email and any non-empty password', () => {
  const errors = validateLoginForm('alice@example.com', 'password123');
  assert.deepEqual(errors, {});
  assert.equal(hasLoginErrors(errors), false);
});

test('validateLoginForm flags missing password separately from missing email', () => {
  assert.equal(validateLoginForm('alice@example.com', '').password, 'Password is required.');
  assert.ok(validateLoginForm('nope', 'password123').email);
});

test('loginStatusDisplay treats NOT_VERIFIED as warning with resend offer', () => {
  const display = loginStatusDisplay('NOT_VERIFIED');
  assert.equal(display.variant, 'warning');
  assert.equal(display.offerResend, true);
});

test('loginStatusDisplay treats DISABLED as error without resend', () => {
  const display = loginStatusDisplay('DISABLED');
  assert.equal(display.variant, 'error');
  assert.equal(display.offerResend, false);
});

test('loginStatusDisplay maps INVALID_CREDENTIALS to a generic error', () => {
  const display = loginStatusDisplay('INVALID_CREDENTIALS');
  assert.equal(display.variant, 'error');
  assert.equal(display.offerResend, false);
});

test('loginStatusDisplay maps LOCKED to a cooldown error', () => {
  const display = loginStatusDisplay('LOCKED');
  assert.equal(display.variant, 'error');
  assert.match(display.message, /15 minutes/i);
  assert.equal(display.offerResend, false);
});

test('loginStatusDisplay maps ANONYMOUS to an info hint', () => {
  const display = loginStatusDisplay('ANONYMOUS');
  assert.equal(display.variant, 'info');
});

test('loginStatusDisplay falls back to a generic error when status is null', () => {
  const display = loginStatusDisplay(null);
  assert.equal(display.variant, 'error');
});

test('resolvePostLoginRedirect returns the requested admin page after sign-in', () => {
  assert.equal(resolvePostLoginRedirect('/admin'), '/admin');
  assert.equal(resolvePostLoginRedirect('/admin/runs?status=FAILED'), '/admin/runs?status=FAILED');
});

test('resolvePostLoginRedirect falls back for unsafe or looping targets', () => {
  assert.equal(resolvePostLoginRedirect(null), '/diagrams');
  assert.equal(resolvePostLoginRedirect('https://evil.example/admin'), '/diagrams');
  assert.equal(resolvePostLoginRedirect('//evil.example/admin'), '/diagrams');
  assert.equal(resolvePostLoginRedirect('/login?returnTo=/admin'), '/diagrams');
});

test('buildLoginHref carries safe return targets into the login URL', () => {
  assert.equal(buildLoginHref('/admin'), '/login?returnTo=%2Fadmin');
  assert.equal(buildLoginHref('/admin/runs?status=FAILED'), '/login?returnTo=%2Fadmin%2Fruns%3Fstatus%3DFAILED');
  assert.equal(buildLoginHref('https://evil.example/admin'), '/login');
});
