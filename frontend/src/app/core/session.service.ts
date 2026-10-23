import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Api } from './api.service';
import { I18n } from './i18n.service';
import { Me } from './models';

/** The signed-in person and what they may do (from GET /me). */
@Injectable({ providedIn: 'root' })
export class Session {
  private api = inject(Api);
  private i18n = inject(I18n);

  readonly me = signal<Me | null>(null);
  readonly unread = signal(0);
  readonly can = computed(() => this.me()?.can);
  readonly initials = computed(() => initials(this.me()?.displayName ?? ''));

  async load(): Promise<Me> {
    const me = await firstValueFrom(this.api.get<Me>('/me'));
    this.me.set(me);
    this.i18n.setFromProfile(me.language);
    this.refreshUnread();
    return me;
  }

  refreshUnread() {
    this.api.get<{ count: number }>('/ntf/unread-count').subscribe({
      next: (r) => this.unread.set(r.count ?? 0),
      error: () => {},
    });
  }

  clear() {
    this.me.set(null);
    this.unread.set(0);
  }
}

export function initials(name: string): string {
  const parts = name.replace(/^(Dr|Prof|Eng|CPA|Mr|Mrs|Ms)\.?\s+/i, '').trim().split(/\s+/);
  return ((parts[0]?.[0] ?? '') + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase();
}
