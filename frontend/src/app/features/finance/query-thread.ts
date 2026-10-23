import { ChangeDetectionStrategy, Component, ElementRef, computed, effect, inject, input, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Api } from '../../core/api.service';
import { I18n, TPipe } from '../../core/i18n.service';
import { QueryDetail } from '../../core/models';
import { Session } from '../../core/session.service';
import { Toasts } from '../../core/toast.service';
import { Avatar } from '../../ui/avatar';
import { Icon } from '../../ui/icon';
import { Status } from '../../ui/status';

/**
 * A claim query as a conversation (BP-FIN-02), laid out like Messages: your own messages on the
 * right. Staff confirm or reopen; Finance responds, escalates or closes.
 */
@Component({
  selector: 'app-query-thread',
  imports: [RouterLink, FormsModule, Icon, Avatar, Status, TPipe],
  template: `
    <div class="page page-narrow">
      <a class="back" [routerLink]="backLink()"><app-icon name="chevronLeft" /> {{ backLabel() | t }}</a>
      @if (q(); as q) {
        <header class="head">
          <div class="row wrap" style="gap: 8px">
            <app-status [value]="q.status" />
            <span class="t-footnote secondary">{{ ('query.cat.' + q.category) | t }}</span>
            @if (q.dueAt && ['OPEN', 'REOPENED', 'ESCALATED'].includes(q.status)) {
              <span class="t-footnote" [class.overdue]="overdue()">{{ 'query.due' | t: { when: i18n.until(q.dueAt) } }}</span>
            }
          </div>
          <h1 class="t-title2">{{ q.claimDescription }}</h1>
          <p class="t-subhead secondary">
            {{ q.claimRef }}
            @if (asFinance()) {, <a [routerLink]="['/directory', q.staffPublicId]">{{ q.staffName }}</a>}
            @if (q.assignedTo) {, {{ 'query.handledBy' | t: { name: q.assignedTo } }}}
          </p>
        </header>

        <div class="thread" #thread>
          <div class="msg" [class.mine]="!asFinance()">
            <app-avatar [name]="q.staffName" [size]="28" />
            <div class="bubble">
              <div class="who">{{ q.staffName }}</div>
              <div class="text">{{ q.description }}</div>
              <div class="when">{{ i18n.dateTime(q.createdAt) }}</div>
            </div>
          </div>
          @for (m of q.messages; track $index) {
            <div class="msg" [class.mine]="m.fromFinance === asFinance()" [class.finance]="m.fromFinance">
              <app-avatar [name]="m.authorName" [size]="28" />
              <div class="bubble">
                <div class="who">{{ m.authorName }}@if (m.fromFinance) { <span class="secondary">, {{ 'query.finance' | t }}</span>}</div>
                <div class="text">{{ m.body }}</div>
                <div class="when">{{ i18n.dateTime(m.createdAt) }}</div>
              </div>
            </div>
          }
        </div>

        @if (q.canConfirm || q.canReopen) {
          <div class="resolve card">
            <div class="grow">
              <div class="t-headline">{{ 'query.resolvedQ' | t }}</div>
              <div class="t-footnote secondary">{{ 'query.resolvedHelp' | t: { n: 2 - q.reopenCount } }}</div>
            </div>
            @if (q.canConfirm) {
              <button class="btn btn-filled" type="button" (click)="act('confirm')" [disabled]="busy()"><app-icon name="check" /> {{ 'query.confirm' | t }}</button>
            }
          </div>
        }

        @if (q.canRespond || q.canReopen) {
          <form class="composer" (ngSubmit)="send()">
            <textarea class="textarea" name="body" [(ngModel)]="body" rows="2" maxlength="2000"
              [placeholder]="(asFinance() ? 'query.replyHint' : q.canReopen ? 'query.reopenHint' : 'query.addHint') | t" [attr.aria-label]="'query.message' | t"></textarea>
            <button class="send" type="submit" [disabled]="busy() || !body.trim()" [attr.aria-label]="'query.send' | t"><app-icon name="send" /></button>
          </form>
          @if (asFinance()) {
            <div class="row wrap finance-actions">
              <button class="btn" type="button" (click)="act('escalate')" [disabled]="busy() || q.status === 'ESCALATED'">{{ 'query.escalate' | t }}</button>
              <button class="btn" type="button" (click)="act('close')" [disabled]="busy() || !['RESPONDED', 'RESOLVED'].includes(q.status)">{{ 'query.close' | t }}</button>
            </div>
          }
        }
      }
    </div>
  `,
  styles: `
    .back { display: inline-flex; align-items: center; gap: 2px; margin: 16px 0 12px -6px; --icon-size: 22px; }
    .head { margin-bottom: 20px; }
    .head h1 { margin: 10px 0 4px; }
    .overdue { color: var(--red); font-weight: 600; }
    .thread { display: flex; flex-direction: column; gap: 14px; padding: 8px 0 20px; }
    .msg { display: flex; align-items: flex-end; gap: 8px; max-width: 86%; animation: pop var(--dur-2) var(--spring); }
    .msg.mine { flex-direction: row-reverse; align-self: flex-end; }
    .bubble { padding: 10px 14px 8px; border-radius: 20px 20px 20px 6px; background: var(--bg-elevated); box-shadow: var(--shadow-1); }
    .mine .bubble { border-radius: 20px 20px 6px 20px; background: var(--tint); color: var(--on-tint); }
    .who { font-size: var(--fs-footnote); font-weight: 600; margin-bottom: 2px; }
    .mine .who .secondary, .mine .when { color: color-mix(in srgb, var(--on-tint) 75%, transparent); }
    .text { white-space: pre-wrap; overflow-wrap: anywhere; }
    .when { font-size: var(--fs-caption); color: var(--label-2); margin-top: 4px; }
    .resolve { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; margin-bottom: 14px; }
    .resolve .grow { flex: 1; min-width: 200px; }
    .composer {
      position: sticky; bottom: 12px; display: flex; gap: 8px; align-items: flex-end; padding: 8px; border-radius: 26px;
      background: var(--glass-strong); backdrop-filter: var(--glass-blur); -webkit-backdrop-filter: var(--glass-blur);
      box-shadow: inset 0 0 0 0.5px var(--glass-edge), var(--shadow-2);
    }
    .composer .textarea { min-height: 40px; border-radius: 20px; box-shadow: none; background: transparent; resize: none; padding: 9px 12px; }
    .composer .textarea:focus { box-shadow: none; }
    .send { width: 36px; height: 36px; border-radius: 50%; border: 0; background: var(--tint); color: var(--on-tint); display: grid; place-items: center; cursor: pointer; flex: none; --icon-size: 17px; transition: transform var(--dur-1) var(--spring), opacity var(--dur-1); }
    .send:disabled { opacity: 0.35; cursor: default; }
    .send:not(:disabled):active { transform: scale(0.9); }
    .finance-actions { justify-content: flex-end; margin-top: 12px; }
    @media (max-width: 900px) { .composer { bottom: calc(var(--tabbar-h) + 20px); } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class QueryThread {
  private api = inject(Api);
  private toasts = inject(Toasts);
  private router = inject(Router);
  private session = inject(Session);
  protected i18n = inject(I18n);

  readonly id = input.required<string>();
  protected q = signal<QueryDetail | null>(null);
  protected busy = signal(false);
  protected body = '';
  private thread = viewChild<ElementRef<HTMLElement>>('thread');

  protected asFinance = computed(() => {
    const q = this.q();
    return !!q && !!this.session.can()?.financeWorkspace && q.staffPublicId !== this.session.me()?.staffPublicId;
  });
  protected backLink = computed(() => (this.router.url.startsWith('/finance/desk') ? '/finance/workspace' : '/finance'));
  protected backLabel = computed(() => (this.router.url.startsWith('/finance/desk') ? 'nav.financeWorkspace' : 'nav.diary'));
  protected overdue = computed(() => !!this.q()?.dueAt && new Date(this.q()!.dueAt!).getTime() < Date.now());

  constructor() {
    effect(() => this.api.get<QueryDetail>(`/fin/queries/${this.id()}`).subscribe((q) => this.q.set(q)));
  }

  protected send() {
    const q = this.q();
    if (!q) return;
    // A staff reply after Finance has answered means "not resolved yet": it reopens the query.
    const reopen = !this.asFinance() && q.canReopen;
    this.busy.set(true);
    this.api.post<QueryDetail>(`/fin/queries/${this.id()}/${reopen ? 'reopen' : 'messages'}`, { body: this.body.trim() }).subscribe({
      next: (d) => {
        this.q.set(d);
        this.body = '';
        this.busy.set(false);
        if (reopen) this.toasts.show(this.i18n.t(d.status === 'ESCALATED' ? 'query.escalated' : 'query.reopened'));
      },
      error: (e) => {
        this.busy.set(false);
        this.toasts.error(e);
      },
    });
  }

  protected act(action: 'confirm' | 'escalate' | 'close') {
    this.busy.set(true);
    this.api.post<QueryDetail>(`/fin/queries/${this.id()}/${action}`).subscribe({
      next: (d) => {
        this.q.set(d);
        this.busy.set(false);
        this.toasts.show(this.i18n.t('query.done.' + action));
      },
      error: (e) => {
        this.busy.set(false);
        this.toasts.error(e);
      },
    });
  }
}
