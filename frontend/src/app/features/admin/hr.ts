import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api } from '../../core/api.service';
import { I18n, TPipe } from '../../core/i18n.service';
import { ActingAppointment, HrBatch, HrException } from '../../core/models';
import { Toasts } from '../../core/toast.service';
import { Empty } from '../../ui/empty';
import { Icon } from '../../ui/icon';
import { PageHeader } from '../../ui/page-header';
import { Segmented } from '../../ui/segmented';
import { Status } from '../../ui/status';

/** HR officer tools (SCR-DIR-04): the daily HR import, its exception queue and acting appointments. */
@Component({
  selector: 'app-hr-admin',
  imports: [RouterLink, PageHeader, Segmented, Icon, Empty, Status, TPipe],
  template: `
    <div class="page">
      <app-page-header [title]="'nav.hr' | t" [subtitle]="'hr.subtitle' | t" />
      <app-segmented [segments]="tabs()" [(value)]="tab" />

      @if (tab() === 'imports') {
        <label class="drop">
          <input type="file" accept=".csv,text/csv" class="visually-hidden" (change)="upload(file)" #file [disabled]="busy()" />
          <span class="tile teal"><app-icon name="fileUp" /></span>
          <span class="grow"><span class="t-headline">{{ (busy() ? 'ws.import.uploading' : 'hr.importTitle') | t }}</span><span class="t-footnote secondary">{{ 'hr.importHelp' | t }}</span></span>
          <span class="btn btn-tinted">{{ 'ws.import.choose' | t }}</span>
        </label>

        <div class="table-wrap section">
          <table class="table">
            <thead><tr><th>{{ 'ws.batch.file' | t }}</th><th>{{ 'manage.status' | t }}</th><th class="r">{{ 'hr.created' | t }}</th><th class="r">{{ 'hr.updated' | t }}</th><th class="r">{{ 'hr.left' | t }}</th><th class="r">{{ 'hr.exceptions' | t }}</th></tr></thead>
            <tbody>
              @for (b of batches(); track b.id) {
                <tr class="clickable" (click)="openBatch(b)" [class.selected]="selected()?.id === b.id">
                  <td><strong>{{ b.fileName }}</strong><div class="t-footnote secondary">{{ i18n.dateTime(b.createdAt) }}, {{ b.createdBy }}</div></td>
                  <td><app-status [value]="b.status" /></td>
                  <td class="r num">{{ b.createdCount }}</td><td class="r num">{{ b.updatedCount }}</td><td class="r num">{{ b.deactivatedCount }}</td>
                  <td class="r num" [class.warn]="b.exceptionCount">{{ b.exceptionCount }}</td>
                </tr>
              }
            </tbody>
          </table>
          @if (!batches().length) {<app-empty icon="building" [title]="'hr.noImports' | t" />}
        </div>

        @if (selected(); as b) {
          <div class="section">
            <div class="section-header"><h2>{{ 'hr.exceptionsFor' | t: { file: b.fileName } }}</h2></div>
            <div class="list">
              @for (x of exceptions(); track x.id) {
                <div class="list-row">
                  <span class="grow"><span class="title">{{ ('hrReason.' + x.reasonCode) | t }}@if (x.employeeNo) { <span class="secondary">{{ x.employeeNo }}</span>}</span><code class="sub">{{ x.payload }}</code></span>
                  @if (x.status === 'OPEN') {<button class="btn" type="button" (click)="closeException(x)">{{ 'hr.markResolved' | t }}</button>} @else {<app-status [value]="x.status" />}
                </div>
              } @empty {<div class="list-empty">{{ 'hr.noExceptions' | t }}</div>}
            </div>
          </div>
        }
      } @else {
        <div class="list">
          @for (a of acting(); track a.id) {
            <div class="list-row">
              <span class="grow"><span class="title"><a [routerLink]="['/directory', a.staffPublicId]">{{ a.staffName }}</a> {{ 'hr.actingAs' | t: { position: a.positionTitle } }}</span>
                <span class="sub">{{ a.unitName }}, {{ i18n.date(a.startsOn) }} – {{ i18n.date(a.endsOn) }}@if (a.reason) {, {{ a.reason }}}</span></span>
              <button class="btn btn-destructive" type="button" (click)="endActing(a)">{{ 'hr.end' | t }}</button>
            </div>
          } @empty {<app-empty icon="briefcase" [title]="'hr.noActing' | t" [text]="'hr.actingHelp' | t" />}
        </div>
      }
    </div>
  `,
  styles: `
    app-segmented { display: block; margin-bottom: 20px; }
    .drop { display: flex; align-items: center; gap: 14px; padding: 18px; border-radius: var(--r-lg); border: 1.5px dashed var(--separator); background: var(--bg-elevated); cursor: pointer; }
    .drop .grow { flex: 1; display: flex; flex-direction: column; gap: 2px; }
    .drop .tile { width: 44px; height: 44px; border-radius: 11px; --icon-size: 24px; }
    tr.selected td { background: var(--tint-soft); }
    .warn { color: var(--orange); font-weight: 600; }
    code { font-family: ui-monospace, 'SF Mono', Menlo, monospace; font-size: var(--fs-caption); word-break: break-all; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HrAdmin {
  private api = inject(Api);
  private toasts = inject(Toasts);
  protected i18n = inject(I18n);
  protected tab = signal('imports');
  protected tabs = computed(() => {
    this.i18n.lang();
    return [
      { value: 'imports', label: this.i18n.t('hr.imports') },
      { value: 'acting', label: this.i18n.t('hr.acting') },
    ];
  });
  protected batches = signal<HrBatch[]>([]);
  protected selected = signal<HrBatch | null>(null);
  protected exceptions = signal<HrException[]>([]);
  protected acting = signal<ActingAppointment[]>([]);
  protected busy = signal(false);

  constructor() {
    this.load();
    this.api.get<ActingAppointment[]>('/dir/acting').subscribe((l) => this.acting.set(l));
  }

  private load() {
    this.api.get<HrBatch[]>('/dir/imports').subscribe((l) => this.batches.set(l));
  }

  protected openBatch(b: HrBatch) {
    this.selected.set(b);
    this.api.get<HrException[]>(`/dir/imports/${b.id}/exceptions`).subscribe((l) => this.exceptions.set(l));
  }

  protected upload(input: HTMLInputElement) {
    const f = input.files?.[0];
    input.value = '';
    if (!f) return;
    this.busy.set(true);
    this.api.upload<{ status: string; duplicate: boolean; exceptions: number }>('/dir/imports', f).subscribe({
      next: (r) => {
        this.busy.set(false);
        this.toasts.show(this.i18n.t(r.duplicate ? 'ws.import.duplicate' : 'hr.imported', { n: r.exceptions }));
        this.load();
      },
      error: (e) => {
        this.busy.set(false);
        this.toasts.error(e);
      },
    });
  }

  protected closeException(x: HrException) {
    this.api.post(`/dir/exceptions/${x.id}/close`, { status: 'RESOLVED' }).subscribe({
      next: () => this.openBatch(this.selected()!),
      error: (e) => this.toasts.error(e),
    });
  }

  protected endActing(a: ActingAppointment) {
    this.api.delete(`/dir/acting/${a.id}`).subscribe({
      next: () => {
        this.acting.update((l) => l.filter((x) => x.id !== a.id));
        this.toasts.show(this.i18n.t('hr.ended'));
      },
      error: (e) => this.toasts.error(e),
    });
  }
}
