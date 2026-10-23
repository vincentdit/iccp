import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Api } from '../../core/api.service';
import { I18n, TPipe } from '../../core/i18n.service';
import { InboxItem, Page, Preferences } from '../../core/models';
import { Session } from '../../core/session.service';
import { Toasts } from '../../core/toast.service';
import { Empty } from '../../ui/empty';
import { Icon } from '../../ui/icon';
import { PageHeader } from '../../ui/page-header';
import { Segmented } from '../../ui/segmented';
import { Sheet } from '../../ui/sheet';

const CATEGORY_TILE: Record<string, { icon: string; tile: string }> = {
  COMMUNICATION: { icon: 'megaphone', tile: 'orange' },
  EMERGENCY: { icon: 'siren', tile: 'red' },
  FINANCE: { icon: 'wallet', tile: 'green' },
  APPROVALS: { icon: 'checklist', tile: 'indigo' },
  DIRECTORY: { icon: 'users', tile: 'teal' },
  SECURITY: { icon: 'shield', tile: 'gray' },
};

/** Notification centre (SCR-NTF-01/02): in-app inbox plus per-category email and SMS choices. */
@Component({
  selector: 'app-inbox',
  imports: [FormsModule, PageHeader, Segmented, Icon, Empty, Sheet, TPipe],
  template: `
    <div class="page page-narrow">
      <app-page-header [title]="'nav.notifications' | t">
        <button class="btn" type="button" (click)="readAll()" [disabled]="!session.unread()">{{ 'inbox.readAll' | t }}</button>
        <button class="icon-btn" type="button" (click)="openPrefs()" [attr.aria-label]="'inbox.prefs' | t" [title]="'inbox.prefs' | t"><app-icon name="sliders" /></button>
      </app-page-header>
      <app-segmented [segments]="segments()" [(value)]="view" />

      <div class="list">
        @for (n of items(); track n.publicId) {
          <button class="list-row" type="button" (click)="open(n)" [class.unread]="!n.readAt" style="--sep-inset: 58px">
            <span class="tile" [class]="tile(n).tile"><app-icon [name]="tile(n).icon" /></span>
            <span class="grow">
              <span class="title">{{ n.title }}</span>
              <span class="sub">{{ ('ntfCat.' + n.category) | t }}, {{ i18n.relative(n.createdAt) }}</span>
            </span>
            @if (!n.readAt) {<span class="dot" [attr.aria-label]="'feed.unread' | t"></span>}
          </button>
        } @empty {
          <app-empty icon="bell" [title]="(view() === 'unread' ? 'inbox.noneUnread' : 'inbox.empty') | t" />
        }
      </div>
      @if (total() > items().length) {
        <div class="more"><button class="btn" type="button" (click)="more()">{{ 'common.showMore' | t }}</button></div>
      }

      <app-sheet [title]="'inbox.prefs' | t" [open]="prefsOpen()" (closed)="prefsOpen.set(false)" width="600px">
        @if (prefs(); as p) {
          <p class="t-subhead secondary" style="margin-bottom: 14px">{{ 'prefs.intro' | t }}</p>
          <div class="list prefs">
            <div class="list-row head"><span class="grow"></span><span class="col">{{ 'prefs.email' | t }}</span><span class="col">SMS</span></div>
            @for (c of p.categories; track c.category) {
              <div class="list-row" style="--sep-inset: 58px">
                <span class="tile" [class]="catTile(c.category).tile"><app-icon [name]="catTile(c.category).icon" /></span>
                <span class="grow"><span class="title">{{ ('ntfCat.' + c.category) | t }}</span>
                  @if (c.mandatory) {<span class="sub">{{ 'prefs.mandatory' | t }}</span>}</span>
                <span class="col"><input type="checkbox" class="switch" [(ngModel)]="c.email" [name]="'e' + c.category" [disabled]="c.mandatory" [attr.aria-label]="('ntfCat.' + c.category | t) + ' email'" /></span>
                <span class="col"><input type="checkbox" class="switch" [(ngModel)]="c.sms" [name]="'s' + c.category" [disabled]="c.mandatory" [attr.aria-label]="('ntfCat.' + c.category | t) + ' SMS'" /></span>
              </div>
            }
          </div>
          <div class="group-label" style="margin-top: 20px">{{ 'prefs.quiet' | t }}</div>
          <div class="list">
            <label class="list-row"><span class="grow">{{ 'prefs.from' | t }}</span><input class="input inline" type="time" name="qs" [(ngModel)]="p.quietStart" /></label>
            <label class="list-row"><span class="grow">{{ 'prefs.to' | t }}</span><input class="input inline" type="time" name="qe" [(ngModel)]="p.quietEnd" /></label>
          </div>
          <p class="group-foot">{{ 'prefs.quietHelp' | t }}</p>
        }
        <div sheet-actions>
          <button class="btn" type="button" (click)="prefsOpen.set(false)">{{ 'common.cancel' | t }}</button>
          <button class="btn btn-filled" type="button" (click)="savePrefs()">{{ 'common.save' | t }}</button>
        </div>
      </app-sheet>
    </div>
  `,
  styles: `
    app-segmented { display: block; margin-bottom: 16px; }
    .unread .title { font-weight: 600; }
    .more { display: flex; justify-content: center; margin-top: 16px; }
    .prefs .col { width: 64px; display: grid; place-items: center; flex: none; }
    .prefs .head { min-height: 32px; font-size: var(--fs-footnote); color: var(--label-2); }
    .inline { width: auto; min-height: 34px; padding: 6px 10px; box-shadow: none; background: var(--fill-quiet); }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InboxPage {
  private api = inject(Api);
  private router = inject(Router);
  private toasts = inject(Toasts);
  protected i18n = inject(I18n);
  protected session = inject(Session);

  protected view = signal('all');
  protected items = signal<InboxItem[]>([]);
  protected total = signal(0);
  protected prefs = signal<Preferences | null>(null);
  protected prefsOpen = signal(false);
  private page = 0;
  protected segments = computed(() => {
    this.i18n.lang();
    return [
      { value: 'all', label: this.i18n.t('feed.all') },
      { value: 'unread', label: this.i18n.t('feed.unreadTab') },
    ];
  });

  constructor() {
    effect(() => {
      this.page = 0;
      this.load(this.view() === 'unread', true);
    });
  }

  private load(unread: boolean, reset: boolean) {
    this.api.get<Page<InboxItem>>('/ntf/inbox', { unread, page: this.page, size: 30 }).subscribe((p) => {
      this.items.set(reset ? p.items : [...this.items(), ...p.items]);
      this.total.set(p.total);
    });
  }

  protected more() {
    this.page++;
    this.load(this.view() === 'unread', false);
  }

  protected tile(n: InboxItem) {
    return CATEGORY_TILE[n.category] ?? { icon: 'bell', tile: 'gray' };
  }
  protected catTile(c: string) {
    return CATEGORY_TILE[c] ?? { icon: 'bell', tile: 'gray' };
  }

  protected open(n: InboxItem) {
    if (!n.readAt) {
      this.api.post(`/ntf/${n.publicId}/read`).subscribe(() => this.session.refreshUnread());
      this.items.update((l) => l.map((x) => (x.publicId === n.publicId ? { ...x, readAt: new Date().toISOString() } : x)));
    }
    if (n.deepLink) this.router.navigateByUrl(n.deepLink);
  }

  protected readAll() {
    this.api.post('/ntf/read-all').subscribe(() => {
      this.items.update((l) => l.map((x) => ({ ...x, readAt: x.readAt ?? new Date().toISOString() })));
      this.session.refreshUnread();
    });
  }

  protected openPrefs() {
    this.api.get<Preferences>('/ntf/preferences').subscribe((p) => {
      this.prefs.set({ ...p, quietStart: p.quietStart?.slice(0, 5), quietEnd: p.quietEnd?.slice(0, 5) });
      this.prefsOpen.set(true);
    });
  }

  protected savePrefs() {
    this.api.put<Preferences>('/ntf/preferences', this.prefs()).subscribe({
      next: () => {
        this.prefsOpen.set(false);
        this.toasts.show(this.i18n.t('prefs.saved'));
      },
      error: (e) => this.toasts.error(e),
    });
  }
}
