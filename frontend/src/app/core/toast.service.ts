import { Injectable, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';

export interface Toast {
  id: number;
  text: string;
  kind: 'info' | 'success' | 'error';
}

/** Brief, non-blocking confirmations (HUD style), shown at the top of the window. */
@Injectable({ providedIn: 'root' })
export class Toasts {
  readonly items = signal<Toast[]>([]);
  private seq = 0;

  show(text: string, kind: Toast['kind'] = 'success', ms = 3200) {
    const id = ++this.seq;
    this.items.update((l) => [...l, { id, text, kind }]);
    setTimeout(() => this.dismiss(id), ms);
  }

  error(e: unknown) {
    this.show(errorText(e), 'error', 5200);
  }

  dismiss(id: number) {
    this.items.update((l) => l.filter((t) => t.id !== id));
  }
}

/** The server already words errors for people (and in their language); fall back to a generic line. */
export function errorText(e: unknown): string {
  if (e instanceof HttpErrorResponse) {
    if (serverUnreachable(e)) return 'The ICCP server is not responding. Check that the backend is running, then try again.';
    const body = e.error;
    if (body?.fieldErrors?.length) return body.fieldErrors.map((f: { message: string }) => f.message).join(' ');
    if (body?.message) return body.message;
  }
  if (e instanceof Error) return e.message;
  return 'Something went wrong. Try again.';
}

/**
 * True when the request never reached the ICCP backend: no network (status 0), or a gateway or the
 * development proxy answered instead of the API (5xx without an ICCP error body).
 */
export function serverUnreachable(e: unknown): boolean {
  if (!(e instanceof HttpErrorResponse)) return false;
  if (e.status === 0) return true;
  const isApiError = !!e.error && typeof e.error === 'object' && 'code' in e.error;
  return e.status >= 500 && !isApiError;
}
