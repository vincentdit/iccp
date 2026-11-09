import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api } from '../../core/api.service';
import { I18n, TPipe } from '../../core/i18n.service';
import { Batch, DeskItem, FeedItem, FinSummary, InboxItem, ManageItem, Page } from '../../core/models';
import { Session } from '../../core/session.service';
import { Icon } from '../../ui/icon';
import { PageHeader } from '../../ui/page-header';
import { Ring } from '../../ui/ring';

interface Task {
  icon: string;
  tile: string;
  title: string;
  detail: string;
  link: string;
}

@Component({
  selector: 'app-home',
  imports: [RouterLink, PageHeader, Icon, Ring, TPipe],
  templateUrl: './home.html',
  styleUrl: './home.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Home {
  private api = inject(Api);
  protected i18n = inject(I18n);
  protected session = inject(Session);

  protected feed = signal<FeedItem[] | null>(null);
  protected finance = signal<FinSummary | null>(null);
  protected inbox = signal<InboxItem[] | null>(null);
  protected tasks = signal<Task[]>([]);

  protected toAcknowledge = computed(() => (this.feed() ?? []).filter((f) => f.requiresAck && !f.acknowledgedAt));
  protected latest = computed(() => (this.feed() ?? []).slice(0, 4));

  protected greeting = computed(() => {
    this.i18n.lang();
    const h = Number(new Intl.DateTimeFormat('en-GB', { hour: 'numeric', hour12: false, timeZone: 'Africa/Dar_es_Salaam' }).format(new Date()));
    const key = h < 12 ? 'home.morning' : h < 17 ? 'home.afternoon' : 'home.evening';
    const first = (this.session.me()?.displayName ?? '').replace(/^(Dr|Prof|Eng|CPA)\.?\s+/i, '').split(' ')[0];
    return this.i18n.t(key, { name: first });
  });
  protected today = computed(() => {
    return new Intl.DateTimeFormat(this.i18n.locale(), { weekday: 'long', day: 'numeric', month: 'long', timeZone: 'Africa/Dar_es_Salaam' }).format(new Date());
  });

  constructor() {
    if(this.session.feature('MODULE_ANNOUNCEMENTS'))this.api.get<Page<FeedItem>>('/com/feed', { size: 20 }).subscribe({ next: (p) => this.feed.set(p.items), error: () => this.feed.set([]) });
    if(this.session.feature('MODULE_FINANCE'))this.api.get<FinSummary[]>('/fin/me/summary').subscribe({ next: (s) => this.finance.set(s[0] ?? null), error: () => {} });
    if(this.session.feature('MODULE_NOTIFICATIONS'))this.api.get<Page<InboxItem>>('/ntf/inbox', { size: 4 }).subscribe({ next: (p) => this.inbox.set(p.items), error: () => this.inbox.set([]) });
    this.loadTasks();
  }

  private loadTasks() {
    const can = this.session.can();
    const add = (t: Task) => this.tasks.update((l) => [...l, t]);
    if (this.session.feature('MODULE_ANNOUNCEMENTS')&&can?.approveAnnouncements) {
      this.api.get<ManageItem[]>('/com/announcements', { box: 'review' }).subscribe((l) => {
        const n = l.filter((a) => a.status === 'IN_REVIEW').length;
        if (n) add({ icon: 'megaphone', tile: 'orange', title: this.i18n.t('home.taskApprove', { n }), detail: this.i18n.t('home.taskApproveDetail'), link: '/announcements/manage' });
      });
    }
    if (this.session.feature('MODULE_FINANCE')&&can?.financeReview) {
      this.api.get<Page<Batch>>('/fin/batches', { status: 'RECONCILED' }).subscribe((p) => {
        const n = p.items.filter((b) => b.canApprove).length;
        if (n) add({ icon: 'landmark', tile: 'indigo', title: this.i18n.t('home.taskBatches', { n }), detail: this.i18n.t('home.taskBatchesDetail'), link: '/finance/workspace' });
      });
    }
    if (this.session.feature('MODULE_FINANCE')&&can?.financeWorkspace) {
      this.api.get<Page<DeskItem>>('/fin/desk', { status: 'active' }).subscribe((p) => {
        const waiting = p.items.filter((q) => q.status !== 'RESPONDED').length;
        if (waiting) add({ icon: 'question', tile: 'green', title: this.i18n.t('home.taskQueries', { n: waiting }), detail: this.i18n.t('home.taskQueriesDetail'), link: '/finance/workspace?tab=desk' });
      });
    }
  }

  protected deepLink(n: InboxItem): string {
    return n.deepLink ?? '/notifications';
  }
}
