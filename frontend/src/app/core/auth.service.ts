import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { PublicConfig } from './models';

const TOKEN_KEY = 'iccp.token';
const PKCE_KEY = 'iccp.pkce';

interface StoredToken {
  accessToken: string;
  expiresAt: number;
  idToken?: string;
}

/**
 * Sign-in. In production the browser uses Keycloak (OIDC authorization code + PKCE, Doc 05 §7).
 * In development the backend issues a short-lived token for a chosen demo person.
 * The token lives in sessionStorage so closing the tab signs out.
 */
@Injectable({ providedIn: 'root' })
export class Auth {
  private http = inject(HttpClient);
  private router = inject(Router);

  readonly config = signal<PublicConfig | null>(null);
  private token = signal<StoredToken | null>(readToken());
  readonly signedIn = computed(() => {
    const t = this.token();
    return !!t && t.expiresAt > Date.now();
  });

  async loadConfig(): Promise<PublicConfig> {
    const cached = this.config();
    if (cached) return cached;
    const c = await firstValueFrom(this.http.get<PublicConfig>('/api/v1/public/config'));
    this.config.set(c);
    return c;
  }

  accessToken(): string | null {
    const t = this.token();
    return t && t.expiresAt > Date.now() ? t.accessToken : null;
  }

  async devSignIn(employeeNo: string): Promise<void> {
    const r = await firstValueFrom(
      this.http.post<{ accessToken: string; expiresIn: number }>('/api/v1/dev/login', { employeeNo }),
    );
    this.store({ accessToken: r.accessToken, expiresAt: Date.now() + r.expiresIn * 1000 });
  }

  /** Starts the OIDC redirect to Keycloak. */
  async oidcSignIn(returnTo = '/'): Promise<void> {
    const c = await this.loadConfig();
    const verifier = randomString(64);
    const state = randomString(24);
    const challenge = await sha256Base64Url(verifier);
    sessionStorage.setItem(PKCE_KEY, JSON.stringify({ verifier, state, returnTo }));
    const url = new URL(`${c.issuer}/protocol/openid-connect/auth`);
    url.search = new URLSearchParams({
      client_id: c.clientId,
      redirect_uri: `${location.origin}/auth/callback`,
      response_type: 'code',
      scope: 'openid email profile',
      state,
      code_challenge: challenge,
      code_challenge_method: 'S256',
    }).toString();
    location.assign(url.toString());
  }

  /** Completes the OIDC redirect: exchanges the code for tokens. */
  async completeOidc(params: URLSearchParams): Promise<string> {
    const c = await this.loadConfig();
    const saved = JSON.parse(sessionStorage.getItem(PKCE_KEY) ?? '{}');
    sessionStorage.removeItem(PKCE_KEY);
    if (!params.get('code') || params.get('state') !== saved.state) {
      throw new Error('The sign-in response did not match this browser session. Start again.');
    }
    const body = new URLSearchParams({
      grant_type: 'authorization_code',
      client_id: c.clientId,
      code: params.get('code')!,
      redirect_uri: `${location.origin}/auth/callback`,
      code_verifier: saved.verifier,
    });
    const r = await fetch(`${c.issuer}/protocol/openid-connect/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body,
    });
    if (!r.ok) throw new Error('Keycloak did not accept the sign-in. Start again.');
    const t = await r.json();
    this.store({ accessToken: t.access_token, idToken: t.id_token, expiresAt: Date.now() + (t.expires_in - 30) * 1000 });
    return saved.returnTo || '/';
  }

  async signOut(): Promise<void> {
    const t = this.token();
    this.store(null);
    const c = this.config();
    if (c?.authMode === 'oidc' && t?.idToken) {
      const url = new URL(`${c.issuer}/protocol/openid-connect/logout`);
      url.search = new URLSearchParams({ id_token_hint: t.idToken, post_logout_redirect_uri: `${location.origin}/sign-in` }).toString();
      location.assign(url.toString());
      return;
    }
    await this.router.navigateByUrl('/sign-in');
  }

  private store(t: StoredToken | null) {
    this.token.set(t);
    try {
      if (t) sessionStorage.setItem(TOKEN_KEY, JSON.stringify(t));
      else sessionStorage.removeItem(TOKEN_KEY);
    } catch {
      /* private mode: token kept in memory */
    }
  }
}

function readToken(): StoredToken | null {
  try {
    const t = JSON.parse(sessionStorage.getItem(TOKEN_KEY) ?? 'null') as StoredToken | null;
    return t && t.expiresAt > Date.now() ? t : null;
  } catch {
    return null;
  }
}

function randomString(length: number): string {
  const bytes = crypto.getRandomValues(new Uint8Array(length));
  return Array.from(bytes, (b) => 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~'[b % 66]).join('');
}

async function sha256Base64Url(text: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return btoa(String.fromCharCode(...new Uint8Array(digest))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
