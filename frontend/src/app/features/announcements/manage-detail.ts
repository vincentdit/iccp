import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Api } from '../../core/api.service';
import { I18n, TPipe } from '../../core/i18n.service';
import { ManageDetail, Reach } from '../../core/models';
import { Session } from '../../core/session.service';
import { Toasts } from '../../core/toast.service';
import { Icon } from '../../ui/icon';
import { PageHeader } from '../../ui/page-header';
import { Sheet } from '../../ui/sheet';
import { Status } from '../../ui/status';

type Decision = 'approve' | 'return' | 'reject' | 'withdraw';

/** One notice from the publisher's or approver's side: content, workflow, reach (SCR-COM-04/05/06). */
@Component({
  selector: 'app-manage-detail',
  imports: [RouterLink, FormsModule, PageHeader, Icon, Sheet, Status, TPipe],
  templateUrl: './manage-detail.html',
  styleUrl: './manage-detail.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ManageDetailPage {
  private api = inject(Api);
  private toasts = inject(Toasts);
  private router = inject(Router);
  protected i18n = inject(I18n);
  protected session = inject(Session);

  readonly id = input.required<string>();
  protected a = signal<ManageDetail | null>(null);
  protected reach = signal<Reach | null>(null);
  protected decision = signal<Decision | null>(null);
  protected comment = '';
  protected busy = signal(false);

  protected ackPct = computed(() => {
    const r = this.reach();
    return r && r.targeted ? Math.round((100 * (r.requiresAck ? r.acknowledged : r.read)) / r.targeted) : 0;
  });
  protected readPct = computed(() => {
    const r = this.reach();
    return r && r.targeted ? Math.round((100 * r.read) / r.targeted) : 0;
  });

  constructor() {
    effect(() => this.load(this.id()));
  }

  private load(id: string) {
    this.api.get<ManageDetail>(`/com/announcements/${id}`).subscribe((a) => {
      this.a.set(a);
      if (a.status === 'PUBLISHED' || a.status === 'ARCHIVED' || a.status === 'EXPIRED') {
        this.api.get<Reach>(`/com/announcements/${id}/reach`).subscribe({ next: (r) => this.reach.set(r), error: () => {} });
      }
    });
  }

  protected submit() {
    this.busy.set(true);
    this.api.post<{ status: string }>(`/com/announcements/${this.id()}/submit`).subscribe({
      next: (r) => {
        this.toasts.show(this.i18n.t('manage.submitted.' + r.status));
        this.busy.set(false);
        this.load(this.id());
      },
      error: (e) => {
        this.busy.set(false);
        this.toasts.error(e);
      },
    });
  }

  protected ask(d: Decision) {
    this.comment = '';
    this.decision.set(d);
  }

  protected decide() {
    const d = this.decision();
    if (!d) return;
    this.busy.set(true);
    const body = { comment: this.comment.trim() || null };
    this.api.post(`/com/announcements/${this.id()}/${d}`, body).subscribe({
      next: () => {
        this.toasts.show(this.i18n.t('manage.decided.' + d));
        this.decision.set(null);
        this.busy.set(false);
        this.load(this.id());
      },
      error: (e) => {
        this.busy.set(false);
        this.toasts.error(e);
      },
    });
  }

  protected upload(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    this.busy.set(true);
    this.api.upload<ManageDetail>(`/com/announcements/${this.id()}/attachments`, file).subscribe({
      next: (a) => {
        this.a.set(a);
        this.busy.set(false);
        input.value = '';
        this.toasts.show(this.i18n.t('file.uploaded'));
        setTimeout(() => this.load(this.id()), 4000);
      },
      error: (e) => {
        this.busy.set(false);
        input.value = '';
        this.toasts.error(e);
      },
    });
  }

  protected download(id: number, name: string) {
    this.api.download(`/com/attachments/${id}`, name);
  }

  protected size(bytes: number): string {
    return bytes > 1048576 ? `${(bytes / 1048576).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`;
  }

  protected audienceText(type: string, value?: string | null): string {
    const t = this.i18n.t('audience.' + type);
    return value ? `${t}: ${value}` : t;
  }

  protected edit() {
    this.router.navigate(['/announcements/manage', this.id(), 'edit']);
  }
}
