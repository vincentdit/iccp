import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Api } from '../../core/api.service';
import { I18n, TPipe } from '../../core/i18n.service';
import { ClaimDetail } from '../../core/models';
import { Toasts } from '../../core/toast.service';
import { Empty } from '../../ui/empty';
import { Icon } from '../../ui/icon';
import { Sheet } from '../../ui/sheet';
import { Status } from '../../ui/status';
import { CLAIM_ICON } from './diary';

/** One claim: how the balance is made up, every payment and adjustment, and any queries (SCR-FIN-02). */
@Component({
  selector: 'app-claim',
  imports: [RouterLink, FormsModule, Icon, Empty, Sheet, Status, TPipe],
  template: `
    <div class="page page-narrow">
      <a class="back" routerLink="/finance"><app-icon name="chevronLeft" /> {{ 'nav.diary' | t }}</a>
      @if (d(); as d) {
        <header class="head">
          <span class="tile" [class]="icon().tile"><app-icon [name]="icon().icon" /></span>
          <div>
            <p class="t-subhead secondary">{{ type() }}, {{ d.claim.sourceRef }}</p>
            <h1 class="t-title1">{{ d.claim.description }}</h1>
            <div class="row" style="margin-top: 10px"><app-status [value]="d.claim.paymentStatus" /></div>
          </div>
        </header>

        <div class="outstanding card card-raised">
          <div class="t-footnote secondary">{{ 'diary.outstanding' | t }}</div>
          <div class="big num" [class.settled]="d.claim.outstandingAmount <= 0">{{ i18n.money(d.claim.outstandingAmount, d.claim.currency) }}</div>
          <div class="bar" aria-hidden="true"><span [style.width.%]="pct()" [class.full]="pct() >= 100"></span></div>
        </div>

        <div class="group-label">{{ 'claim.breakdown' | t }}</div>
        <ul class="list">
          <li class="list-row"><span class="grow">{{ 'claim.approved' | t: { date: i18n.date(d.claim.approvalDate) } }}</span><span class="num">{{ i18n.money(d.claim.approvedAmount, d.claim.currency) }}</span></li>
          @if (d.claim.adjustmentAmount) {
            <li class="list-row"><span class="grow">{{ 'claim.adjustments' | t }}</span><span class="num">{{ sign(d.claim.adjustmentAmount) }}{{ i18n.money(abs(d.claim.adjustmentAmount), d.claim.currency) }}</span></li>
          }
          <li class="list-row"><span class="grow">{{ 'diary.paidToDate' | t }}</span><span class="num">−{{ i18n.money(d.claim.paidAmount, d.claim.currency) }}</span></li>
          <li class="list-row total"><span class="grow t-headline">{{ 'diary.outstanding' | t }}</span><span class="num t-headline">{{ i18n.money(d.claim.outstandingAmount, d.claim.currency) }}</span></li>
        </ul>
        @if (d.voucherRef) {
          <p class="group-foot">{{ 'claim.voucher' | t: { ref: d.voucherRef } }}</p>
        }

        <div class="section">
          <div class="group-label">{{ 'claim.payments' | t }}</div>
          <ol class="list timeline">
            @for (a of d.allocations; track a.id) {
              <li class="list-row" style="--sep-inset: 52px">
                <span class="node" [class.rev]="a.reversal"><app-icon [name]="a.reversal ? 'undo' : 'check'" /></span>
                <span class="grow">
                  <span class="title">{{ (a.reversal ? 'claim.reversal' : 'claim.paidOn') | t: { date: i18n.date(a.paidOn) } }}</span>
                  <span class="sub">{{ a.paymentRef || a.paymentSourceRef }}</span>
                </span>
                <span class="num" [class.neg]="a.reversal">{{ i18n.money(a.amount, d.claim.currency) }}</span>
              </li>
            } @empty {
              <li class="list-empty">{{ 'claim.noPayments' | t }}</li>
            }
          </ol>
        </div>

        @if (d.adjustments.length) {
          <div class="section">
            <div class="group-label">{{ 'claim.adjustments' | t }}</div>
            <ul class="list">
              @for (j of d.adjustments; track j.sourceRef) {
                <li class="list-row">
                  <span class="grow"><span class="title">{{ j.reason }}</span><span class="sub">{{ j.sourceRef }}, {{ i18n.date(j.publishedAt) }}</span></span>
                  <span class="num">{{ sign(j.amount) }}{{ i18n.money(abs(j.amount), d.claim.currency) }}</span>
                </li>
              }
            </ul>
          </div>
        }

        <div class="section">
          <div class="group-label">{{ 'claim.queries' | t }}</div>
          <div class="list">
            @for (q of d.queries; track q.publicId) {
              <a class="list-row" [routerLink]="['/finance/queries', q.publicId]">
                <span class="grow"><span class="title">{{ q.description }}</span><span class="sub">{{ i18n.relative(q.updatedAt) }}</span></span>
                <app-status [value]="q.status" />
                <app-icon class="chev" name="chevronRight" />
              </a>
            }
            @if (canAsk()) {
              <button class="list-row ask" type="button" (click)="asking.set(true)"><app-icon name="question" /> {{ 'claim.ask' | t }}</button>
            }
          </div>
          <p class="group-foot">{{ 'claim.askHelp' | t }}</p>
        </div>

        <app-sheet [title]="'claim.ask' | t" [open]="asking()" (closed)="asking.set(false)">
          <label class="field">
            <span class="label">{{ 'claim.queryAbout' | t }}</span>
            <select class="select" [(ngModel)]="category" name="cat">
              @for (c of ['MISSING_PAYMENT', 'AMOUNT', 'WRONG_CLAIM', 'OTHER']; track c) {
                <option [value]="c">{{ ('query.cat.' + c) | t }}</option>
              }
            </select>
          </label>
          <label class="field" style="margin-top: 14px">
            <span class="label">{{ 'claim.queryDetail' | t }}</span>
            <textarea class="textarea" [(ngModel)]="description" name="desc" maxlength="2000" [placeholder]="'claim.queryHint' | t"></textarea>
          </label>
          <p class="t-footnote secondary" style="margin-top: 10px">{{ 'claim.querySla' | t }}</p>
          <div sheet-actions>
            <button class="btn" type="button" (click)="asking.set(false)">{{ 'common.cancel' | t }}</button>
            <button class="btn btn-filled" type="button" (click)="raise()" [disabled]="busy() || description.trim().length < 5">{{ 'claim.send' | t }}</button>
          </div>
        </app-sheet>
      } @else if (missing()) {
        <app-empty icon="wallet" [title]="'detail.missing' | t" [text]="'claim.missingText' | t" />
      } @else {
        <div class="stack" style="margin-top: 32px"><div class="skeleton" style="height: 30px; width: 70%"></div><div class="skeleton" style="height: 120px"></div></div>
      }
    </div>
  `,
  styles: `
    .back { display: inline-flex; align-items: center; gap: 2px; margin: 16px 0 12px -6px; --icon-size: 22px; }
    .head { display: flex; gap: 16px; align-items: flex-start; margin-bottom: 20px; animation: rise var(--dur-3) var(--spring); }
    .head .tile { width: 48px; height: 48px; border-radius: 12px; --icon-size: 26px; margin-top: 4px; }
    .outstanding { margin-bottom: 24px; padding: 20px 22px; }
    .big { font-size: var(--fs-large); font-weight: 700; letter-spacing: -0.026em; margin: 2px 0 14px; }
    .big.settled { color: var(--green); }
    .bar { height: 6px; border-radius: 3px; background: var(--fill); overflow: hidden; }
    .bar span { display: block; height: 100%; background: var(--orange); border-radius: 3px; animation: grow 900ms var(--spring) both; transform-origin: left; }
    .bar span.full { background: var(--green); }
    @keyframes grow { from { transform: scaleX(0); } }
    .total { background: var(--fill-quiet); }
    .node { width: 26px; height: 26px; border-radius: 50%; display: grid; place-items: center; background: var(--green); color: #fff; --icon-size: 15px; --icon-stroke: 2.6; flex: none; }
    .node.rev { background: var(--orange); }
    .neg { color: var(--orange); }
    .ask { color: var(--tint); gap: 8px; --icon-size: 18px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ClaimPage {
  private api = inject(Api);
  private toasts = inject(Toasts);
  private router = inject(Router);
  protected i18n = inject(I18n);

  readonly id = input.required<string>();
  protected d = signal<ClaimDetail | null>(null);
  protected missing = signal(false);
  protected asking = signal(false);
  protected busy = signal(false);
  protected category = 'MISSING_PAYMENT';
  protected description = '';

  protected icon = computed(() => CLAIM_ICON[this.d()?.claim.claimTypeCode ?? ''] ?? { icon: 'receipt', tile: 'gray' });
  protected type = computed(() => (this.i18n.lang() === 'sw' ? this.d()?.claim.claimTypeSw : this.d()?.claim.claimTypeEn));
  protected pct = computed(() => {
    const c = this.d()?.claim;
    if (!c) return 0;
    const owed = c.approvedAmount + c.adjustmentAmount;
    return owed > 0 ? Math.min(100, (100 * c.paidAmount) / owed) : 0;
  });
  protected canAsk = computed(() => !(this.d()?.queries ?? []).some((q) => ['OPEN', 'RESPONDED', 'REOPENED', 'ESCALATED'].includes(q.status)));

  constructor() {
    effect(() => {
      this.api.get<ClaimDetail>(`/fin/me/claims/${this.id()}`).subscribe({ next: (d) => this.d.set(d), error: () => this.missing.set(true) });
    });
  }

  protected raise() {
    this.busy.set(true);
    this.api.post<{ publicId: string }>(`/fin/me/claims/${this.id()}/queries`, { category: this.category, description: this.description.trim() }).subscribe({
      next: (r) => {
        this.toasts.show(this.i18n.t('claim.sent'));
        this.router.navigate(['/finance/queries', r.publicId]);
      },
      error: (e) => {
        this.busy.set(false);
        this.toasts.error(e);
      },
    });
  }

  protected sign(n: number) {
    return n < 0 ? '−' : '+';
  }
  protected abs(n: number) {
    return Math.abs(n);
  }
}
