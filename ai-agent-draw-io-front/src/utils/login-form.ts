/**
 * Pure helpers for the login page. Kept data-only so they can be exercised with `node --test` the
 * same way `register-form.ts` is; the React page consumes these functions and never re-derives the
 * copy or error rules inline.
 */

import type { LoginStatus } from '@/types/api';

const BASIC_EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

const isEmailLike = (email: string | null | undefined) =>
  email != null && BASIC_EMAIL.test(email.trim());

export interface LoginFormErrors {
  email?: string;
  password?: string;
}

export const validateLoginForm = (email: string, password: string): LoginFormErrors => {
  const errors: LoginFormErrors = {};
  if (!isEmailLike(email)) {
    errors.email = 'Please enter a valid email address.';
  }
  if (!password || password.length === 0) {
    errors.password = 'Password is required.';
  }
  return errors;
};

export const hasLoginErrors = (errors: LoginFormErrors): boolean =>
  Boolean(errors.email || errors.password);

export interface LoginStatusDisplay {
  message: string;
  variant: 'success' | 'error' | 'warning' | 'info';
  offerResend: boolean;
}

/** Maps a backend {@link LoginStatus} to the copy the login page renders. */
export const loginStatusDisplay = (status: LoginStatus | null): LoginStatusDisplay => {
  switch (status) {
    case 'SUCCESS':
      return {
        message: 'Signed in. Redirecting…',
        variant: 'success',
        offerResend: false,
      };
    case 'NOT_VERIFIED':
      return {
        message: 'This account is not verified yet. Check your email or request a new link.',
        variant: 'warning',
        offerResend: true,
      };
    case 'DISABLED':
      return {
        message: 'This account is disabled. Contact support if this is unexpected.',
        variant: 'error',
        offerResend: false,
      };
    case 'INVALID_CREDENTIALS':
      return {
        message: 'Incorrect email or password.',
        variant: 'error',
        offerResend: false,
      };
    case 'ANONYMOUS':
      return {
        message: 'Please sign in to continue.',
        variant: 'info',
        offerResend: false,
      };
    default:
      return {
        message: 'Sign-in failed. Please try again.',
        variant: 'error',
        offerResend: false,
      };
  }
};
