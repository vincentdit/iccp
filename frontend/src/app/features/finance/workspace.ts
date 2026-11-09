import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Api } from '../../core/api.service';
import { I18n, TPipe } from '../../core/i18n.service';
import { Batch, DeskItem, FinDashboard, ImportResult, Page, PendingAllocation } from '../../core/models';
import { Session } from '../../core/session.service';
import { Toasts } from '../../core/toast.service';
import { Empty } from '../../ui/empty';
import { Icon } from '../../ui/icon';
import { PageHeader } from '../../ui/page-header';
import { Segmented } from '../../ui/segmented';
import { Sheet } from '../../ui/sheet';
import { Status } from '../../ui/status';

/** Finance workspace (SCR-FIN-04..07): overview, imports, the query desk and manual allocations. */
@Component({
  selector: 'app-workspace',
  imports: [FormsModule, RouterLink, PageHeader, Segmented, Icon, Empty, Sheet, Status, TPipe],
  templateUrl: './workspace.html',
  styleUrl: './workspace.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Workspace {
  private api = inject(Api);
  private toasts = inject(Toasts);
  protected router = inject(Router);
  protected i18n = inject(I18n);
  protected session = inject(Session);

  protected tab = signal(inject(ActivatedRoute).snapshot.queryParamMap.get('tab') ?? 'overview');
  protected tabs = computed(() => {
    this.i18n.lang();
    return ['overview', 'batches', 'desk', 'allocations'].map((v) => ({ value: v, label: this.i18n.t('ws.tab.' + v) }));
  });

  protected dash = signal<FinDashboard | null>(null);
  protected batches = signal<Batch[]>([]);
  protected desk = signal<DeskItem[]>([]);
  protected deskFilter = signal('active');
  protected deskFilters = computed(() => {
    this.i18n.lang();
    return ['active', 'overdue', 'ESCALATED', 'all'].map((v) => ({ value: v, label: this.i18n.t('ws.desk.' + v) }));
  });
  protected pending = signal<PendingAllocation[]>([]);
  protected uploading = signal(false);
  protected dragging = signal(false);
  protected allocating = signal(false);
  protected alloc = { paymentSourceRef: '', claimSourceRef: '', amount: null as number | null, reversesSourceRef: '' };

  protected maxLiability = computed(() => Math.max(1, ...(this.dash()?.liabilities ?? []).filter((l) => l.currency === 'TZS').map((l) => l.outstanding)));
  protected batchCount = (s: string) => this.dash()?.batches.find((b) => b.status === s)?.count ?? 0;

  constructor() {
    effect(() => {
      const t = this.tab();
      this.router.navigate([], { queryParams: { tab: t === 'overview' ? null : t }, replaceUrl: true });
      if (t === 'overview') this.loadDash();
      if (t === 'batches' || t === 'overview') this.loadBatches();
      if (t === 'allocations') this.loadPending();
    });
    effect(() => {
      if (this.tab() !== 'desk') return;
      this.api.get<Page<DeskItem>>('/fin/desk', { status: this.deskFilter(), size: 100 }).subscribe((p) => this.desk.set(p.items));
    });
  }

  private loadDash() {
    this.api.get<FinDashboard>('/fin/dashboard').subscribe((d) => this.dash.set(d));
  }
  private loadBatches() {
    this.api.get<Page<Batch>>('/fin/batches', { size: 50 }).subscribe((p) => this.batches.set(p.items));
  }
  private loadPending() {
    this.api.get<PendingAllocation[]>('/fin/allocations/pending').subscribe((l) => this.pending.set(l));
  }

  protected onFile(input: HTMLInputElement) {
    const f = input.files?.[0];
    input.value = '';
    if (f) this.upload(f);
  }

  protected onDrop(e: DragEvent) {
    e.preventDefault();
    this.dragging.set(false);
    const f = e.dataTransfer?.files?.[0];
    if (f) this.upload(f);
  }

  protected downloadTemplate() {
    this.api.download('/fin/imports/template', 'iccp-staff-diary-template.csv');
  }

  private upload(file: File) {
    this.uploading.set(true);
    this.api.upload<ImportResult>('/fin/imports', file).subscribe({
      next: (r) => {
        this.uploading.set(false);
        const key = r.duplicate ? 'ws.import.duplicate' : r.status === 'REJECTED' ? 'ws.import.rejected' : 'ws.import.done';
        this.toasts.show(this.i18n.t(key, { loaded: r.loaded, q: r.quarantined, reason: r.rejectionReason ?? '' }), r.status === 'REJECTED' ? 'error' : 'success', 6000);
        this.router.navigate(['/finance/batches', r.batchPublicId]);
      },
      error: (e) => {
        this.uploading.set(false);
        this.toasts.error(e);
      },
    });
  }

  protected decideAllocation(a: PendingAllocation, action: 'approve' | 'reject') {
    this.api.post(`/fin/allocations/${a.id}/${action}`).subscribe({
      next: () => {
        this.toasts.show(this.i18n.t(action === 'approve' ? 'ws.alloc.approved' : 'ws.alloc.rejected'));
        this.loadPending();
      },
      error: (e) => this.toasts.error(e),
    });
  }

  protected createAllocation() {
    const body = { ...this.alloc, reversesSourceRef: this.alloc.reversesSourceRef || null };
    this.api.post('/fin/allocations', body).subscribe({
      next: () => {
        this.toasts.show(this.i18n.t('ws.alloc.created'));
        this.allocating.set(false);
        this.alloc = { paymentSourceRef: '', claimSourceRef: '', amount: null, reversesSourceRef: '' };
        this.loadPending();
      },
      error: (e) => this.toasts.error(e),
    });
  }

  protected ageCount(b: string): number {
    return this.dash()?.openQueriesByAge.find((x) => x.bucket === b)?.count ?? 0;
  }

  protected bucketLabel(b: string) {
    return this.i18n.t('ws.age.' + b);
  }
}
