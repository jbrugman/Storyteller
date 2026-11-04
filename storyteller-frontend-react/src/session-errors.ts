import { ApiError } from './api';

// The JSON API distinguishes a gone session from a merely missing one only by its code.
const sessionGoneCodes = ['session_not_found', 'session_gone'];

// The model backend is reported through one code on every turn; only the story shows a modal for it.
const modelUnavailableCode = 'model_backend_error';

export type ErrorOutcome = {
  kind: 'session-expired' | 'model-unavailable' | 'notice' | 'ignored';
  message: string;
};

export function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : 'An unexpected error occurred.';
}

// Aborting an in-flight generation is a user action, not a failure to report.
export function isAbort(cause: unknown): boolean {
  return cause instanceof Error && cause.name === 'AbortError';
}

// One place decides how every API failure should be shown, so the expired session is not limited to startup.
export function errorOutcome(cause: unknown): ErrorOutcome {
  if (isAbort(cause)) return { kind: 'ignored', message: '' };
  if (cause instanceof ApiError && sessionGoneCodes.includes(cause.code)) {
    return {
      kind: 'session-expired',
      message: 'The session has expired or was stopped. Start a new story or resume an infinite session.',
    };
  }
  if (cause instanceof ApiError && cause.code === modelUnavailableCode) {
    return {
      kind: 'model-unavailable',
      message: 'The model is temporarily unavailable. Your prompt was not saved; please try again.',
    };
  }
  return { kind: 'notice', message: errorMessage(cause) };
}
