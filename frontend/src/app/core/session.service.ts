import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Api } from './api.service';
import { I18n } from './i18n.service';
import { Me, SystemSettingsSnapshot } from './models';

/** The signed-in person and what they may do (from GET /me). */
@Injectable({ providedIn: 'root' })
export class Session {
  private api = inject(Api);
  private i18n = inject(I18n);

  readonly me = signal<Me | null>(null);
  readonly unread = signal(0);
  readonly system = signal<SystemSettingsSnapshot>({ features: [], settings: [] });
  readonly can = computed(() => this.me()?.can);
  readonly initials = computed(() => initials(this.me()?.displayName ?? ''));

  async load(): Promise<Me> {
    const [me,system] = await Promise.all([firstValueFrom(this.api.get<Me>('/me')),firstValueFrom(this.api.get<SystemSettingsSnapshot>('/system-settings/public'))]);
    this.me.set(me);
    this.system.set(system);
    this.i18n.setFromProfile(me.language);
    this.refreshUnread();
    return me;
  }

  feature(code: string): boolean { return this.system().features.find(f=>f.code===code)?.enabled ?? true; }

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
