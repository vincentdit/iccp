import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { I18n, TPipe } from '../../core/i18n.service';
import { Session } from '../../core/session.service';
import { Avatar } from '../../ui/avatar';
import { Icon } from '../../ui/icon';
import { PageHeader } from '../../ui/page-header';

/** Phone-only overflow for destinations that do not fit in the tab bar. */
@Component({
  selector: 'app-more',
  imports: [RouterLink, PageHeader, Avatar, Icon, TPipe],
  template: `
    <div class="page page-narrow">
      <app-page-header [title]="'nav.more' | t" />
      <div class="list">
        <a class="list-row me" routerLink="/directory/me" style="--sep-inset: 74px">
          <app-avatar [name]="session.me()?.displayName ?? ''" [size]="46" />
          <span class="grow"><span class="title t-headline">{{ session.me()?.displayName }}</span><span class="sub">{{ session.me()?.positionTitle }}</span></span>
          <app-icon class="chev" name="chevronRight" />
        </a>
      </div>
      <div class="section list">
        <a class="list-row" routerLink="/notifications" style="--sep-inset: 58px">
          <span class="tile red"><app-icon name="bell" /></span><span class="grow">{{ 'nav.notifications' | t }}</span>
          @if (session.unread()) {<span class="count-badge">{{ session.unread() }}</span>}
          <app-icon class="chev" name="chevronRight" />
        </a>
        <a class="list-row" routerLink="/directory/organisation" style="--sep-inset: 58px">
          <span class="tile teal"><app-icon name="network" /></span><span class="grow">{{ 'dir.organisation' | t }}</span><app-icon class="chev" name="chevronRight" />
        </a>
        <a class="list-row" routerLink="/settings" style="--sep-inset: 58px">
          <span class="tile gray"><app-icon name="settings" /></span><span class="grow">{{ 'nav.settings' | t }}</span><app-icon class="chev" name="chevronRight" />
        </a>
      </div>
      @if (work().length) {
        <div class="section">
          <div class="group-label">{{ 'nav.work' | t }}</div>
          <div class="list">
            @for (w of work(); track w.path) {
              <a class="list-row" [routerLink]="w.path" style="--sep-inset: 58px">
                <span class="tile" [class]="w.tile"><app-icon [name]="w.icon" /></span><span class="grow">{{ w.label | t }}</span><app-icon class="chev" name="chevronRight" />
              </a>
            }
          </div>
        </div>
      }
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class More {
  protected session = inject(Session);
  protected i18n = inject(I18n);
  protected work = computed(() => {
    const can = this.session.can();
    const l: { path: string; icon: string; tile: string; label: string }[] = [];
    if (can?.publishAnnouncements || can?.approveAnnouncements) l.push({ path: '/announcements/manage', icon: 'send', tile: 'orange', label: 'nav.manageNotices' });
    if (can?.financeWorkspace) l.push({ path: '/finance/workspace', icon: 'landmark', tile: 'indigo', label: 'nav.financeWorkspace' });
    if (can?.hrAdmin) l.push({ path: '/admin/hr', icon: 'building', tile: 'teal', label: 'nav.hr' });
    if (can?.securityAdmin || can?.auditSearch) l.push({ path: '/admin/security', icon: 'shield', tile: 'gray', label: 'nav.security' });
    return l;
  });
}
