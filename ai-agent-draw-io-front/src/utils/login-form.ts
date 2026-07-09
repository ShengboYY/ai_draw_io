/**
 * Pure helpers for the login page. Kept data-only so they can be exercised with `node --test` the
 * same way `register-form.ts` is; the React page consumes these functions and never re-derives the
 * copy or error rules inline.
 */

import type { LoginStatus } from '@/types/api';

const BASIC_EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
export const LOGIN_RETURN_TO_PARAM = 'returnTo';
export const DEFAULT_POST_LOGIN_REDIRECT = '/diagrams';

const isEmailLike = (email: string | null | undefined) =>
  email != null && BASIC_EMAIL.test(email.trim());

export const resolvePostLoginRedirect = (returnTo: string | null | undefined): string => {
  const target = returnTo?.trim();
  if (!target) return DEFAULT_POST_LOGIN_REDIRECT;
  if (!target.startsWith('/') || target.startsWith('//') || target.includes('\\')) {
    return DEFAULT_POST_LOGIN_REDIRECT;
  }

  try {
    // Parse against a fixed same-origin base so only app-relative paths survive.
    const url = new URL(target, 'https://freedraw.local');
    if (url.origin !== 'https://freedraw.local') return DEFAULT_POST_LOGIN_REDIRECT;
    if (url.pathname === '/login' || url.pathname.startsWith('/login/')) {
      return DEFAULT_POST_LOGIN_REDIRECT;
    }
    return `${url.pathname}${url.search}${url.hash}`;
  } catch {
    return DEFAULT_POST_LOGIN_REDIRECT;
  }
};

export const buildLoginHref = (returnTo: string | null | undefined): string => {
  const target = resolvePostLoginRedirect(returnTo);
  if (target !== returnTo?.trim()) return '/login';
  return `/login?${LOGIN_RETURN_TO_PARAM}=${encodeURIComponent(target)}`;
};

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
    case 'LOCKED':
      return {
        message: 'Too many failed sign-in attempts. Try again in 15 minutes.',
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
