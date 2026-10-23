import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Api } from '../../core/api.service';
import { I18n, TPipe } from '../../core/i18n.service';
import { ManageItem } from '../../core/models';
import { Session } from '../../core/session.service';
import { Empty } from '../../ui/empty';
import { Icon } from '../../ui/icon';
import { PageHeader } from '../../ui/page-header';
import { Segmented } from '../../ui/segmented';
import { Status } from '../../ui/status';

/** Publisher and approver workspace (SCR-COM-03/05). */
@Component({
  selector: 'app-manage',
  imports: [RouterLink, PageHeader, Segmented, Icon, Empty, Status, TPipe],
  template: `
    <div class="page">
      <app-page-header [title]="'nav.manageNotices' | t" back="/announcements" [backLabel]="'nav.announcements' | t">
        @if (session.can()?.publishAnnouncements) {
          <a class="btn btn-filled" routerLink="/announcements/manage/new"><app-icon name="plus" /> {{ 'feed.new' | t }}</a>
        }
      </app-page-header>

      <app-segmented [segments]="boxes()" [(value)]="box" [label]="'manage.box' | t" />

      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>{{ 'manage.notice' | t }}</th>
              <th>{{ 'manage.status' | t }}</th>
              <th class="hide-sm">{{ 'manage.updated' | t }}</th>
              <th class="r hide-sm">{{ 'manage.reach' | t }}</th>
            </tr>
          </thead>
          <tbody>
            @for (a of items(); track a.publicId) {
              <tr class="clickable" (click)="open(a)">
                <td>
                  <a [routerLink]="['/announcements/manage', a.publicId]" class="title" (click)="$event.stopPropagation()">{{ a.titleEn || a.titleSw }}</a>
                  <div class="t-footnote secondary">{{ ('category.' + a.category) | t }}, {{ a.authorName }}</div>
                </td>
                <td><app-status [value]="a.status" /></td>
                <td class="hide-sm secondary">{{ i18n.relative(a.updatedAt) }}</td>
                <td class="r hide-sm num">
                  @if (a.recipients) {
                    @if (a.requiresAck) {
                      {{ 'manage.ackOf' | t: { a: i18n.number(a.acknowledged), n: i18n.number(a.recipients) } }}
                    } @else {
                      {{ 'manage.people' | t: { n: i18n.number(a.recipients) } }}
                    }
                  } @else {
                    <span class="tertiary">—</span>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
        @if (!items().length) {
          <app-empty icon="send" [title]="('manage.empty.' + box()) | t" />
        }
      </div>
    </div>
  `,
  styles: `
    app-segmented { display: block; margin-bottom: 16px; }
    .title { color: var(--label); font-weight: 600; }
    @media (max-width: 720px) { .hide-sm { display: none; } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Manage {
  private api = inject(Api);
  private router = inject(Router);
  protected i18n = inject(I18n);
  protected session = inject(Session);

  protected box = signal(this.session.can()?.publishAnnouncements ? 'mine' : 'review');
  protected items = signal<ManageItem[]>([]);
  protected boxes = computed(() => {
    this.i18n.lang();
    const can = this.session.can();
    const b = [];
    if (can?.publishAnnouncements) b.push({ value: 'mine', label: this.i18n.t('manage.mine') });
    if (can?.approveAnnouncements) b.push({ value: 'review', label: this.i18n.t('manage.review') });
    b.push({ value: 'all', label: this.i18n.t('manage.all') });
    return b;
  });

  constructor() {
    effect(() => {
      this.api.get<ManageItem[]>('/com/announcements', { box: this.box() }).subscribe((l) => this.items.set(l));
    });
  }

  protected open(a: ManageItem) {
    this.router.navigate(['/announcements/manage', a.publicId]);
  }
}
