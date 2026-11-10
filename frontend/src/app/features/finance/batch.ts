import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from '../../core/api.service';
import { I18n, TPipe } from '../../core/i18n.service';
import { Batch, QuarantineItem } from '../../core/models';
import { Session } from '../../core/session.service';
import { Toasts } from '../../core/toast.service';
import { Icon } from '../../ui/icon';
import { PageHeader } from '../../ui/page-header';
import { Sheet } from '../../ui/sheet';
import { Status } from '../../ui/status';

/** One Finance import: control totals, quarantined rows and the checker's decision (BP-FIN-01). */
@Component({
  selector: 'app-batch',
  imports: [FormsModule, PageHeader, Icon, Sheet, Status, TPipe],
  template: `
    <div class="page">
      @if (b(); as b) {
        <app-page-header [title]="b.fileName" back="/finance/workspace" [backLabel]="'nav.financeWorkspace' | t">
          <button class="btn" type="button" (click)="downloadReport()"><app-icon name="download" /> {{ 'ws.batch.downloadReport' | t }}</button>
        </app-page-header>
        <div class="row wrap status-line">
          <app-status [value]="b.status" />
          <span class="t-subhead secondary">{{ 'ws.batch.importedBy' | t: { who: b.importedBy, when: i18n.dateTime(b.createdAt) } }}</span>
          @if (b.reviewedBy) {
            <span class="t-subhead secondary">{{ 'ws.batch.reviewedBy' | t: { who: b.reviewedBy, when: i18n.dateTime(b.reviewedAt) } }}</span>
          }
        </div>

        @if (b.status === 'RECONCILED') {
          <div class="decide card card-raised">
            <app-icon [name]="b.canApprove ? 'shield' : 'clock'" />
            <div class="grow">
              <div class="t-headline">{{ (b.canApprove ? 'ws.batch.reviewTitle' : 'ws.batch.waitingTitle') | t }}</div>
              <div class="t-footnote secondary">{{ (b.canApprove ? 'ws.batch.reviewText' : 'ws.batch.waitingText') | t: { n: b.openQuarantine } }}</div>
            </div>
            @if (b.canApprove) {
              <button class="btn btn-destructive" type="button" (click)="rejecting.set(true)">{{ 'manage.reject' | t }}</button>
              <button class="btn btn-filled" type="button" (click)="approve()" [disabled]="busy()"><app-icon name="check" /> {{ 'ws.batch.publish' | t }}</button>
            }
          </div>
        }

        <div class="grid grid-3 totals">
          <div class="card metric"><div class="value num">{{ b.sourceRecordCount }}</div><div class="label">{{ 'ws.batch.recordsInFile' | t }}</div></div>
          <div class="card metric"><div class="value num ok">{{ b.loadedCount }}</div><div class="label">{{ 'ws.batch.loaded' | t }}</div></div>
          <div class="card metric"><div class="value num" [class.warn]="b.quarantinedCount">{{ b.quarantinedCount }}</div><div class="label">{{ 'ws.batch.quarantinedRows' | t }}</div></div>
        </div>
        <p class="group-foot">{{ 'ws.batch.controlTotal' | t: { amount: i18n.money(b.sourceAmountTotal) } }}</p>

        <div class="section">
          <div class="section-header"><h2>{{ 'ws.batch.quarantineTitle' | t }}</h2></div>
          <div class="list">
            @for (q of items(); track q.id) {
              <div class="list-row item">
                <span class="tile" [class]="q.status === 'OPEN' ? 'orange' : 'gray'"><app-icon [name]="q.status === 'OPEN' ? 'warning' : 'check'" /></span>
                <span class="grow">
                  <span class="title">{{ ('quarantine.' + q.reasonCode) | t }} <span class="secondary t-footnote">{{ 'ws.batch.line' | t: { n: q.rowNo } }}</span></span>
                  <code class="sub payload">{{ cells(q) }}</code>
                  @if (error(q); as err) {<span class="sub err">{{ err }}</span>}
                  @if (q.resolutionNote) {<span class="sub">{{ q.resolutionNote }}, {{ q.resolvedBy }}</span>}
                </span>
                @if (q.status === 'OPEN') {
                  <button class="btn" type="button" (click)="closing.set(q)">{{ 'ws.batch.resolve' | t }}</button>
                } @else {
                  <app-status [value]="q.status" />
                }
              </div>
            } @empty {
              <div class="list-empty">{{ 'ws.batch.noQuarantine' | t }}</div>
            }
          </div>
          <p class="group-foot">{{ 'ws.batch.quarantineHelp' | t }}</p>
        </div>

        <app-sheet [title]="'ws.batch.resolve' | t" [open]="!!closing()" (closed)="closing.set(null)">
          <p class="t-subhead secondary">{{ 'ws.batch.resolveHelp' | t }}</p>
          <div class="row" style="margin: 14px 0">
            <label class="row"><input type="radio" name="st" value="RESOLVED" [(ngModel)]="closeStatus" /> {{ 'ws.batch.resolvedAtSource' | t }}</label>
            <label class="row"><input type="radio" name="st" value="REJECTED" [(ngModel)]="closeStatus" /> {{ 'ws.batch.discard' | t }}</label>
          </div>
          <label class="field"><span class="label">{{ 'ws.batch.note' | t }}</span><textarea class="textarea" name="note" [(ngModel)]="note" maxlength="1000"></textarea></label>
          <div sheet-actions>
            <button class="btn" type="button" (click)="closing.set(null)">{{ 'common.cancel' | t }}</button>
            <button class="btn btn-filled" type="button" (click)="close()" [disabled]="!note.trim()">{{ 'common.save' | t }}</button>
          </div>
        </app-sheet>

        <app-sheet [title]="'ws.batch.rejectTitle' | t" [open]="rejecting()" (closed)="rejecting.set(false)">
          <p class="t-subhead secondary">{{ 'ws.batch.rejectHelp' | t }}</p>
          <label class="field" style="margin-top: 14px"><span class="label">{{ 'manage.reason' | t }}</span><textarea class="textarea" name="reason" [(ngModel)]="reason"></textarea></label>
          <div sheet-actions>
            <button class="btn" type="button" (click)="rejecting.set(false)">{{ 'common.cancel' | t }}</button>
            <button class="btn btn-destructive" type="button" (click)="reject()" [disabled]="!reason.trim() || busy()">{{ 'manage.reject' | t }}</button>
          </div>
        </app-sheet>
      }
    </div>
  `,
  styles: `
    .status-line { gap: 6px 14px; margin: -6px 0 20px; }
    .decide { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; margin-bottom: 20px; --icon-size: 28px; animation: rise var(--dur-3) var(--spring); }
    .decide > app-icon { color: var(--tint); }
    .decide .grow { flex: 1; min-width: 220px; }
    .totals .value.ok { color: var(--green); }
    .totals .value.warn { color: var(--orange); }
    .item { align-items: flex-start; padding-top: 12px; padding-bottom: 12px; --sep-inset: 58px; }
    .payload { font-family: ui-monospace, 'SF Mono', Menlo, monospace; font-size: var(--fs-caption); display: block; white-space: pre-wrap; word-break: break-all; }
    .err { color: var(--red); }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BatchPage {
  private api = inject(Api);
  private toasts = inject(Toasts);
  protected i18n = inject(I18n);
  protected session = inject(Session);

  readonly id = input.required<string>();
  protected b = signal<Batch | null>(null);
  protected items = signal<QuarantineItem[]>([]);
  protected busy = signal(false);
  protected closing = signal<QuarantineItem | null>(null);
  protected rejecting = signal(false);
  protected closeStatus = 'RESOLVED';
  protected note = '';
  protected reason = '';

  constructor() {
    effect(() => this.load(this.id()));
  }

  private load(id: string) {
    this.api.get<Batch>(`/fin/batches/${id}`).subscribe((b) => this.b.set(b));
    this.api.get<QuarantineItem[]>(`/fin/batches/${id}/quarantine`).subscribe((q) => this.items.set(q));
  }

  protected cells(q: QuarantineItem): string {
    try {
      const p = JSON.parse(q.payload);
      return Array.isArray(p.cells) ? p.cells.join(', ') : '';
    } catch {
      return '';
    }
  }

  protected downloadReport() {
    this.api.download(`/fin/batches/${this.id()}/reconciliation-report`, `iccp-finance-reconciliation-${this.id()}.csv`);
  }

  protected error(q: QuarantineItem): string | null {
    try {
      return JSON.parse(q.payload).error ?? null;
    } catch {
      return null;
    }
  }

  protected approve() {
    this.busy.set(true);
    this.api.post<Batch>(`/fin/batches/${this.id()}/approve`).subscribe({
      next: (b) => {
        this.b.set(b);
        this.busy.set(false);
        this.toasts.show(this.i18n.t('ws.batch.published'));
      },
      error: (e) => {
        this.busy.set(false);
        this.toasts.error(e);
      },
    });
  }

  protected reject() {
    this.busy.set(true);
    this.api.post<Batch>(`/fin/batches/${this.id()}/reject`, { reason: this.reason.trim() }).subscribe({
      next: (b) => {
        this.b.set(b);
        this.busy.set(false);
        this.rejecting.set(false);
        this.toasts.show(this.i18n.t('ws.batch.rejected'));
        this.load(this.id());
      },
      error: (e) => {
        this.busy.set(false);
        this.toasts.error(e);
      },
    });
  }

  protected close() {
    const q = this.closing();
    if (!q) return;
    this.api.post(`/fin/quarantine/${q.id}/close`, { status: this.closeStatus, note: this.note.trim() }).subscribe({
      next: () => {
        this.closing.set(null);
        this.note = '';
        this.toasts.show(this.i18n.t('ws.batch.itemClosed'));
        this.load(this.id());
      },
      error: (e) => this.toasts.error(e),
    });
  }
}
