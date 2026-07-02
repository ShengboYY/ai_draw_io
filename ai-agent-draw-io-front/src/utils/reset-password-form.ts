/**
 * Pure helpers for the password-reset request and confirmation pages.
 *
 * Keeping the rules here makes both reset pages small and gives the token status copy a focused
 * unit-test seam.
 */

import type { PasswordResetStatus } from '@/types/api';

const MIN_PASSWORD_LENGTH = 8;
const BASIC_EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

const isEmailLike = (email: string | null | undefined) =>
  email != null && BASIC_EMAIL.test(email.trim());

export interface PasswordResetRequestErrors {
  email?: string;
}

export interface PasswordResetConfirmErrors {
  token?: string;
  password?: string;
  confirm?: string;
}

export const validatePasswordResetRequest = (email: string): PasswordResetRequestErrors => {
  const errors: PasswordResetRequestErrors = {};
  if (!isEmailLike(email)) {
    errors.email = 'Please enter a valid email address.';
  }
  return errors;
};

export const validatePasswordResetConfirm = (
  token: string | null,
  password: string,
  confirm: string,
): PasswordResetConfirmErrors => {
  const errors: PasswordResetConfirmErrors = {};
  if (!token || token.trim().length === 0) {
    errors.token = 'Reset token is missing.';
  }
  if (password.length < MIN_PASSWORD_LENGTH) {
    errors.password = `Password must be at least ${MIN_PASSWORD_LENGTH} characters.`;
  }
  if (password !== confirm) {
    errors.confirm = 'Passwords do not match.';
  }
  return errors;
};

export const hasPasswordResetRequestErrors = (errors: PasswordResetRequestErrors): boolean =>
  Boolean(errors.email);

export const hasPasswordResetConfirmErrors = (errors: PasswordResetConfirmErrors): boolean =>
  Boolean(errors.token || errors.password || errors.confirm);

export interface PasswordResetStatusDisplay {
  title: string;
  message: string;
  variant: 'success' | 'error' | 'warning';
  canRequestNew: boolean;
  canSignIn: boolean;
}

export const passwordResetStatusDisplay = (
  status: PasswordResetStatus | null,
): PasswordResetStatusDisplay => {
  switch (status) {
    case 'SUCCESS':
      return {
        title: 'Password updated',
        message: 'Your password has been changed. Sign in again with the new password.',
        variant: 'success',
        canRequestNew: false,
        canSignIn: true,
      };
    case 'EXPIRED':
      return {
        title: 'Link expired',
        message: 'This password-reset link expired after 30 minutes. Request a new one to continue.',
        variant: 'warning',
        canRequestNew: true,
        canSignIn: false,
      };
    case 'ALREADY_USED':
      return {
        title: 'Link already used',
        message: 'This reset link has already been used. Request a new link if you still need access.',
        variant: 'warning',
        canRequestNew: true,
        canSignIn: true,
      };
    case 'INVALID':
    default:
      return {
        title: 'Invalid link',
        message: 'We could not use this reset link. Request a new password-reset email to try again.',
        variant: 'error',
        canRequestNew: true,
        canSignIn: false,
      };
  }
};
