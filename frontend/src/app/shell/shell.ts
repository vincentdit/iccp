import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { filter } from 'rxjs';
import { Api } from '../core/api.service';
import { Auth } from '../core/auth.service';
import { I18n, TPipe } from '../core/i18n.service';
import { FeedItem } from '../core/models';
import { Session } from '../core/session.service';
import { Avatar } from '../ui/avatar';
import { Icon } from '../ui/icon';

interface NavItem {
  path: string;
  icon: string;
  label: string;
  tile: string;
  exact?: boolean;
  badge?: () => number;
  external?: boolean;
}

/**
 * Window chrome: a floating glass sidebar on wide screens (HIG: sidebars, split views) and a glass
 * tab bar on phones (HIG: tab bars). Content always scrolls underneath the translucent chrome.
 */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, FormsModule, Icon, Avatar, TPipe],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Shell {
  protected session = inject(Session);
  protected i18n = inject(I18n);
  private auth = inject(Auth);
  private api = inject(Api);
  private router = inject(Router);

  protected query = '';
  protected emergencies = signal<FeedItem[]>([]);
  protected dismissed = signal<string[]>([]);
  protected visibleEmergencies = computed(() => this.emergencies().filter((e) => !this.dismissed().includes(e.publicId)));

  protected primary = computed<NavItem[]>(() => {
    this.i18n.lang();
    return [
      { path: '/', icon: 'home', label: this.i18n.t('nav.home'), tile: 'blue', exact: true },
      { path: '/announcements', icon: 'megaphone', label: this.i18n.t('nav.announcements'), tile: 'orange' },
      { path: '/finance', icon: 'wallet', label: this.i18n.t('nav.diary'), tile: 'green', exact: true },
      ...(this.auth.config()?.mmsEnabled ? [{ path: this.mmsMeetingsUrl(), icon: 'calendar', label: this.i18n.lang() === 'sw' ? 'Mikutano' : 'Meetings', tile: 'indigo', external: true }] : []),
      { path: '/directory', icon: 'users', label: this.i18n.t('nav.directory'), tile: 'teal' },
      { path: '/notifications', icon: 'bell', label: this.i18n.t('nav.notifications'), tile: 'red', badge: () => this.session.unread() },
    ];
  });

  protected mmsMeetingsUrl = computed(() => (this.auth.config()?.mmsUrl ?? 'http://localhost:4200').replace(/\/$/, '') + '/meetings');

  protected work = computed<NavItem[]>(() => {
    this.i18n.lang();
    const can = this.session.can();
    const items: NavItem[] = [{ path: '/work', icon: 'briefcase', label: this.i18n.t('nav.workHub'), tile: 'indigo' }];
    if (can?.publishAnnouncements || can?.approveAnnouncements) {
      items.push({ path: '/announcements/manage', icon: 'send', label: this.i18n.t('nav.manageNotices'), tile: 'orange' });
    }
    if (can?.financeWorkspace) {
      items.push({ path: '/finance/workspace', icon: 'landmark', label: this.i18n.t('nav.financeWorkspace'), tile: 'indigo' });
    }
    if (can?.hrAdmin) items.push({ path: '/admin/hr', icon: 'building', label: this.i18n.t('nav.hr'), tile: 'teal' });
    if (can?.securityAdmin || can?.auditSearch) {
      items.push({ path: '/admin/security', icon: 'shield', label: this.i18n.t('nav.security'), tile: 'gray' });
    }
    return items;
  });

  // Tab bar titles are shorter versions of the sidebar names so five fit on a phone.
  protected tabs = computed(() => {
    const short = ['nav.home', 'tab.announcements', 'tab.diary', 'tab.directory'];
    return this.primary()
      .slice(0, 4)
      .map((item, i) => ({ ...item, label: i === 3 ? (this.i18n.lang() === 'sw' ? 'Mikutano' : 'Meetings') : this.i18n.t(short[i]) }));
  });

  constructor() {
    this.loadEmergencies();
    const sub = this.router.events.pipe(filter((e) => e instanceof NavigationEnd)).subscribe(() => this.session.refreshUnread());
    const timer = setInterval(() => {
      this.session.refreshUnread();
      this.loadEmergencies();
    }, 60_000);
    inject(DestroyRef).onDestroy(() => {
      sub.unsubscribe();
      clearInterval(timer);
    });
  }

  protected search() {
    const q = this.query.trim();
    this.router.navigate(['/directory'], { queryParams: q ? { q } : {} });
  }

  protected signOut() {
    this.session.clear();
    this.auth.signOut();
  }

  private loadEmergencies() {
    this.api.get<FeedItem[]>('/com/feed/emergency').subscribe({ next: (e) => this.emergencies.set(e), error: () => {} });
  }
}
