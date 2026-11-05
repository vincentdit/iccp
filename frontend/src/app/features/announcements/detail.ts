import { ChangeDetectionStrategy, Component, computed, inject, input, signal, effect } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api } from '../../core/api.service';
import { I18n, TPipe } from '../../core/i18n.service';
import { FeedDetail } from '../../core/models';
import { Session } from '../../core/session.service';
import { Toasts } from '../../core/toast.service';
import { Empty } from '../../ui/empty';
import { Icon } from '../../ui/icon';

/** Reading view for one notice (SCR-COM-02). Opening it marks it read; acknowledgement is explicit. */
@Component({
  selector: 'app-notice-detail',
  imports: [RouterLink, Icon, Empty, TPipe],
  template: `
    <div class="page page-narrow">
      <a class="back" routerLink="/announcements"><app-icon name="chevronLeft" /> {{ 'nav.announcements' | t }}</a>
      @if (notice(); as n) {
        <article>
          <div class="meta">
            <span class="badge" [class.red]="n.category === 'EMERGENCY_ALERT'" [class.indigo]="n.category === 'CIRCULAR' || n.category === 'DIRECTIVE_NOTICE'">{{ ('category.' + n.category) | t }}</span>
            @if (n.priority === 1) {
              <span class="badge red">{{ 'priority.1' | t }}</span>
            }
            @if (n.currentVersion > 1) {
              <span class="badge orange">{{ 'feed.amendedVersion' | t: { v: n.currentVersion } }}</span>
            }
          </div>
          <h1 class="t-title1">{{ title() }}</h1>
          <p class="byline t-subhead secondary">
            {{ n.authorUnit }}<br />
            {{ 'detail.published' | t: { date: i18n.dateTime(n.publishedAt) } }}
            @if (n.expiresAt) {
              <br />{{ 'detail.until' | t: { date: i18n.date(n.expiresAt) } }}
            }
          </p>
          @if (otherLanguage()) {
            <button class="btn btn-plain lang" type="button" (click)="showOther.set(!showOther())">
              <app-icon name="languages" /> {{ (showOther() ? 'detail.showOriginal' : 'detail.showOther') | t }}
            </button>
          }
          <div class="prose body" [innerHTML]="body()"></div>

          @if (n.attachments.length) {
            <div class="group-label">{{ 'detail.attachments' | t }}</div>
            <div class="list">
              @for (a of n.attachments; track a.id) {
                <button class="list-row" type="button" (click)="download(a.id, a.fileName)" [disabled]="a.scanStatus !== 'CLEAN'" style="--sep-inset: 58px">
                  <span class="tile gray"><app-icon name="file" /></span>
                  <span class="grow"><span class="title">{{ a.fileName }}</span><span class="sub">{{ size(a.sizeBytes) }}, {{ ('file.' + a.scanStatus.toLowerCase()) | t }}</span></span>
                  <app-icon class="chev" name="download" />
                </button>
              }
            </div>
          }
        </article>

        @if (n.requiresAck) {
          <div class="ack" [class.done]="!!n.acknowledgedAt">
            @if (n.acknowledgedAt) {
              <app-icon name="checkCircle" />
              <div>
                <div class="t-headline">{{ 'detail.acknowledged' | t }}</div>
                <div class="t-footnote secondary">{{ i18n.dateTime(n.acknowledgedAt) }}</div>
              </div>
            } @else {
              <div class="grow">
                <div class="t-headline">{{ 'detail.ackTitle' | t }}</div>
                <div class="t-footnote secondary">{{ 'detail.ackText' | t }}</div>
              </div>
              <button class="btn btn-filled btn-large" type="button" (click)="acknowledge()" [disabled]="busy()">{{ 'detail.ackButton' | t }}</button>
            }
          </div>
        }
      } @else if (missing()) {
        <app-empty icon="megaphone" [title]="'detail.missing' | t" [text]="'detail.missingText' | t" />
      } @else {
        <div class="stack" style="margin-top: 32px"><div class="skeleton" style="width: 30%"></div><div class="skeleton" style="height: 34px; width: 80%"></div><div class="skeleton" style="height: 120px"></div></div>
      }
    </div>
  `,
  styles: `
    .back { display: inline-flex; align-items: center; gap: 2px; margin: 16px 0 12px -6px; --icon-size: 22px; }
    article { animation: rise var(--dur-3) var(--spring); }
    .meta { display: flex; gap: 6px; margin-bottom: 12px; flex-wrap: wrap; }
    .byline { margin: 10px 0 20px; line-height: 1.5; }
    .lang { margin: -8px 0 12px -8px; }
    .body { margin-bottom: 28px; }
    .group-label { margin-top: 8px; }
    .ack {
      position: sticky; bottom: 16px; margin-top: 28px; display: flex; align-items: center; gap: 16px; padding: 16px 16px 16px 20px;
      border-radius: var(--r-xl); background: var(--glass-strong); backdrop-filter: var(--glass-blur); -webkit-backdrop-filter: var(--glass-blur);
      box-shadow: inset 0 0 0 0.5px var(--glass-edge), var(--shadow-3); --icon-size: 30px;
    }
    .ack .grow { flex: 1; }
    .ack.done { position: static; box-shadow: var(--shadow-1); background: var(--bg-elevated); animation: pop var(--dur-2) var(--spring-bounce); }
    .ack.done app-icon { color: var(--green); }
    @media (max-width: 900px) { .ack { bottom: calc(var(--tabbar-h) + 22px); flex-wrap: wrap; } .ack .btn { width: 100%; } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Detail {
  private api = inject(Api);
  private toasts = inject(Toasts);
  private session = inject(Session);
  protected i18n = inject(I18n);

  readonly id = input.required<string>();
  protected notice = signal<FeedDetail | null>(null);
  protected missing = signal(false);
  protected busy = signal(false);
  protected showOther = signal(false);

  private useSw = computed(() => {
    const n = this.notice();
    if (!n) return false;
    const wantSw = this.i18n.lang() === 'sw';
    const pick = wantSw ? !!n.titleSw : !n.titleEn;
    return this.showOther() ? !pick : pick;
  });
  protected title = computed(() => (this.useSw() ? this.notice()?.titleSw : this.notice()?.titleEn) ?? '');
  protected body = computed(() => (this.useSw() ? this.notice()?.bodySw : this.notice()?.bodyEn) ?? '');
  protected otherLanguage = computed(() => !!(this.notice()?.titleEn && this.notice()?.titleSw));

  constructor() {
    effect(() => {
      const id = this.id();
      this.api.get<FeedDetail>(`/com/feed/${id}`).subscribe({
        next: (n) => {
          this.notice.set(n);
          this.session.refreshUnread();
        },
        error: () => this.missing.set(true),
      });
    });
  }

  protected acknowledge() {
    this.busy.set(true);
    this.api.post(`/com/feed/${this.id()}/acknowledge`, { channel: 'WEB' }).subscribe({
      next: () => {
        this.notice.update((n) => (n ? { ...n, acknowledgedAt: new Date().toISOString() } : n));
        this.toasts.show(this.i18n.t('detail.ackDone'));
        this.busy.set(false);
      },
      error: (e) => {
        this.toasts.error(e);
        this.busy.set(false);
      },
    });
  }

  protected download(id: number, name: string) {
    this.api.download(`/com/attachments/${id}`, name);
  }

  protected size(bytes: number): string {
    return bytes > 1048576 ? `${(bytes / 1048576).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`;
  }
}
