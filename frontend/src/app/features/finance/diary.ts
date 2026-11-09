import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subject, debounceTime } from 'rxjs';
import { Api } from '../../core/api.service';
import { I18n, TPipe } from '../../core/i18n.service';
import { ClaimLine, FinSummary, Page, QuerySummary } from '../../core/models';
import { Session } from '../../core/session.service';
import { Empty } from '../../ui/empty';
import { Icon } from '../../ui/icon';
import { PageHeader } from '../../ui/page-header';
import { Ring } from '../../ui/ring';
import { Segmented } from '../../ui/segmented';
import { Status } from '../../ui/status';

export const CLAIM_ICON: Record<string, { icon: string; tile: string }> = {
  TRAVEL: { icon: 'pin', tile: 'blue' },
  PER_DIEM: { icon: 'calendar', tile: 'orange' },
  CONSULTANCY: { icon: 'briefcase', tile: 'indigo' },
  EXTRA_DUTY: { icon: 'clock', tile: 'teal' },
  OTHER: { icon: 'receipt', tile: 'gray' },
};

/** Staff Financial Diary (SCR-FIN-01): what TIRDO owes me, what has been paid, what is outstanding. */
@Component({
  selector: 'app-diary',
  imports: [RouterLink, FormsModule, PageHeader, Ring, Segmented, Icon, Empty, Status, TPipe],
  templateUrl: './diary.html',
  styleUrl: './diary.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Diary {
  private api = inject(Api);
  protected i18n = inject(I18n);
  protected session = inject(Session);
  protected icons = CLAIM_ICON;

  protected summaries = signal<FinSummary[] | null>(null);
  protected primary = computed(() => this.summaries()?.[0] ?? null);
  protected others = computed(() => this.summaries()?.slice(1) ?? []);
  protected claims = signal<ClaimLine[] | null>(null);
  protected total = signal(0);
  protected queries = signal<QuerySummary[]>([]);
  protected status = signal('');
  protected q = '';
  protected statementFrom = new Date(new Date().setFullYear(new Date().getFullYear() - 1)).toISOString().slice(0, 10);
  protected statementTo = new Date().toISOString().slice(0, 10);
  private q$ = new Subject<string>();
  private search = signal('');

  protected segments = computed(() => {
    this.i18n.lang();
    return ['', 'PARTLY_PAID', 'APPROVED', 'FULLY_PAID', 'UNDER_QUERY'].map((v) => ({ value: v, label: this.i18n.t(v ? 'diary.filter.' + v : 'feed.all') }));
  });

  constructor() {
    this.api.get<FinSummary[]>('/fin/me/summary').subscribe((s) => this.summaries.set(s));
    this.api.get<QuerySummary[]>('/fin/me/queries').subscribe((q) => this.queries.set(q));
    this.q$.pipe(debounceTime(250)).subscribe((v) => this.search.set(v));
    effect(() => {
      const status = this.status();
      const q = this.search();
      this.api.get<Page<ClaimLine>>('/fin/me/claims', { status, q, size: 100 }).subscribe((p) => {
        this.claims.set(p.items);
        this.total.set(p.total);
      });
    });
  }

  protected onSearch(v: string) {
    this.q$.next(v);
  }

  protected paidPct(c: ClaimLine): number {
    const owed = c.approvedAmount + c.adjustmentAmount;
    return owed > 0 ? Math.min(100, (100 * c.paidAmount) / owed) : 0;
  }

  protected claimType(c: ClaimLine): string {
    return this.i18n.lang() === 'sw' ? c.claimTypeSw : c.claimTypeEn;
  }

  protected downloadStatement(): void {
    this.api.download(`/fin/me/statement?from=${this.statementFrom}&to=${this.statementTo}`, `iccp-financial-statement-${this.statementFrom}-to-${this.statementTo}.pdf`);
  }
}
