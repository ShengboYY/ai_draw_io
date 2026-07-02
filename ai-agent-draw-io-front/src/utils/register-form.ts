/**
 * Pure helpers for the register / verify-email UI.
 *
 * Kept as tiny data-only functions so they can be unit-tested with `node --test` the same way the
 * other `utils/*.ts` files are (see `tests/register-form.test.mjs`). The React pages import these
 * and never re-implement the rules.
 */

import type { EmailVerificationStatus } from '@/types/api';

/** Minimum client-side password length. The backend enforces the same rule. */
export const MIN_PASSWORD_LENGTH = 8;

const BASIC_EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

export const isValidEmail = (email: string | null | undefined): boolean => {
  if (email == null) return false;
  return BASIC_EMAIL.test(email.trim());
};

export interface RegisterFormErrors {
  email?: string;
  password?: string;
  confirm?: string;
}

export const validateRegisterForm = (
  email: string,
  password: string,
  confirm: string,
): RegisterFormErrors => {
  const errors: RegisterFormErrors = {};
  if (!isValidEmail(email)) {
    errors.email = 'Please enter a valid email address.';
  }
  if (password.length < MIN_PASSWORD_LENGTH) {
    errors.password = `Password must be at least ${MIN_PASSWORD_LENGTH} characters.`;
  }
  if (password !== confirm) {
    errors.confirm = 'Passwords do not match.';
  }
  return errors;
};

export const hasErrors = (errors: RegisterFormErrors): boolean =>
  Boolean(errors.email || errors.password || errors.confirm);

export interface VerificationDisplay {
  title: string;
  message: string;
  variant: 'success' | 'error' | 'warning';
  canResend: boolean;
}

/** Maps a backend {@link EmailVerificationStatus} to the copy the verify-email page renders. */
export const verificationDisplay = (status: EmailVerificationStatus | null): VerificationDisplay => {
  switch (status) {
    case 'SUCCESS':
      return {
        title: 'Email verified',
        message: 'Your account is now active. You can sign in and start using Draw.io.',
        variant: 'success',
        canResend: false,
      };
    case 'EXPIRED':
      return {
        title: 'Link expired',
        message: 'This verification link expired after 30 minutes. Request a new one to continue.',
        variant: 'warning',
        canResend: true,
      };
    case 'ALREADY_USED':
      return {
        title: 'Link already used',
        message: 'This verification link has already been used. If your account is active, sign in as usual.',
        variant: 'warning',
        canResend: false,
      };
    case 'INVALID':
    default:
      return {
        title: 'Invalid link',
        message: 'We could not verify this link. Request a new verification email to try again.',
        variant: 'error',
        canResend: true,
      };
  }
};
