import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api } from '../../core/api.service';
import { I18n, TPipe } from '../../core/i18n.service';
import { FeedItem, Page } from '../../core/models';
import { Session } from '../../core/session.service';
import { Empty } from '../../ui/empty';
import { Icon } from '../../ui/icon';
import { PageHeader } from '../../ui/page-header';
import { Segmented } from '../../ui/segmented';

@Component({
  selector: 'app-feed',
  imports: [RouterLink, PageHeader, Segmented, Icon, Empty, TPipe],
  template: `
    <div class="page">
      <app-page-header [title]="'nav.announcements' | t">
        @if (session.can()?.publishAnnouncements) {
          <a class="btn btn-filled" routerLink="/announcements/manage/new"><app-icon name="plus" /> {{ 'feed.new' | t }}</a>
        }
      </app-page-header>

      <app-segmented [segments]="segments()" [(value)]="filter" [label]="'feed.filter' | t" />

      <div class="list feed">
        @for (f of items(); track f.publicId) {
          <a class="list-row" [routerLink]="['/announcements', f.publicId]" style="--sep-inset: 34px">
            <span class="dot" [class.urgent]="f.priority === 1" [style.visibility]="f.readAt ? 'hidden' : 'visible'" [attr.aria-label]="f.readAt ? null : ('feed.unread' | t)"></span>
            <span class="grow">
              <span class="head">
                <span class="title t-headline">{{ f.title }}</span>
                <span class="when t-footnote secondary">{{ i18n.relative(f.publishedAt) }}</span>
              </span>
              <span class="sub">{{ excerpt(f) }}</span>
              <span class="meta">
                <span class="badge" [class.red]="f.category === 'EMERGENCY_ALERT'" [class.indigo]="f.category === 'CIRCULAR' || f.category === 'DIRECTIVE_NOTICE'">{{ ('category.' + f.category) | t }}</span>
                @if (f.requiresAck) {
                  @if (f.acknowledgedAt) {
                    <span class="badge green"><app-icon name="check" /> {{ 'feed.acknowledged' | t }}</span>
                  } @else {
                    <span class="badge orange">{{ 'feed.ackNeeded' | t }}</span>
                  }
                }
                @if (f.attachmentCount) {
                  <span class="t-footnote secondary att"><app-icon name="paperclip" /> {{ f.attachmentCount }}</span>
                }
                @if (f.currentVersion > 1) {
                  <span class="t-footnote secondary">{{ 'feed.amended' | t }}</span>
                }
              </span>
            </span>
          </a>
        } @empty {
          @if (loading()) {
            @for (i of [1, 2, 3, 4]; track i) {
              <div class="list-row"><span class="grow stack"><span class="skeleton" style="display:block;width:60%"></span><span class="skeleton" style="display:block;width:90%"></span></span></div>
            }
          } @else {
            <app-empty icon="megaphone" [title]="('feed.empty.' + filter()) | t" [text]="'feed.emptyText' | t" />
          }
        }
      </div>
      @if (total() > items().length) {
        <div class="more"><button class="btn" type="button" (click)="loadMore()">{{ 'common.showMore' | t }}</button></div>
      }
    </div>
  `,
  styles: `
    app-segmented { display: block; margin-bottom: 16px; }
    .feed .list-row { align-items: flex-start; padding-top: 14px; padding-bottom: 14px; }
    .dot { margin-top: 7px; }
    .dot.urgent { background: var(--red); }
    .head { display: flex; gap: 12px; align-items: baseline; }
    .head .title { flex: 1; min-width: 0; }
    .when { flex: none; }
    .meta { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; margin-top: 8px; --icon-size: 12px; }
    .att { display: inline-flex; align-items: center; gap: 2px; }
    .more { display: flex; justify-content: center; margin-top: 16px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Feed {
  private api = inject(Api);
  protected i18n = inject(I18n);
  protected session = inject(Session);

  protected filter = signal('all');
  protected items = signal<FeedItem[]>([]);
  protected total = signal(0);
  protected loading = signal(true);
  private page = 0;

  protected segments = computed(() => {
    this.i18n.lang();
    return [
      { value: 'all', label: this.i18n.t('feed.all') },
      { value: 'unread', label: this.i18n.t('feed.unreadTab') },
      { value: 'ack', label: this.i18n.t('feed.toAck') },
    ];
  });

  constructor() {
    effect(() => {
      const f = this.filter();
      this.i18n.lang();
      this.page = 0;
      this.load(f, true);
    });
  }

  protected excerpt(f: FeedItem): string {
    const e = (f.excerpt ?? '').trim();
    return e.length >= 219 ? e.replace(/\s+\S*$/, '') + '…' : e;
  }

  protected loadMore() {
    this.page++;
    this.load(this.filter(), false);
  }

  private load(filter: string, reset: boolean) {
    this.loading.set(true);
    this.api.get<Page<FeedItem>>('/com/feed', { filter, page: this.page, size: 20 }).subscribe({
      next: (p) => {
        this.items.set(reset ? p.items : [...this.items(), ...p.items]);
        this.total.set(p.total);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }
}
