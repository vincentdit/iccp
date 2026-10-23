import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Subject, debounceTime, switchMap, catchError, of } from 'rxjs';
import { Api } from '../../core/api.service';
import { I18n, TPipe } from '../../core/i18n.service';
import { AnnouncementInput, AudienceRule, Category, ManageDetail, UnitNode } from '../../core/models';
import { Session } from '../../core/session.service';
import { Toasts } from '../../core/toast.service';
import { Icon } from '../../ui/icon';
import { PageHeader } from '../../ui/page-header';
import { Segmented } from '../../ui/segmented';
import { RichText } from './rich-text';

interface UnitOption {
  code: string;
  label: string;
}

/** Create, edit or amend a notice (SCR-COM-03). Bilingual content; live audience count. */
@Component({
  selector: 'app-compose',
  imports: [FormsModule, PageHeader, Segmented, Icon, RichText, TPipe],
  templateUrl: './compose.html',
  styleUrl: './compose.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Compose {
  private api = inject(Api);
  private router = inject(Router);
  private toasts = inject(Toasts);
  protected i18n = inject(I18n);
  protected session = inject(Session);

  readonly id = input<string>();
  protected existing = signal<ManageDetail | null>(null);
  protected amending = computed(() => this.existing()?.status === 'PUBLISHED');

  protected category = signal<Category>('NOTICE');
  protected lang = signal('en');
  protected priority = 3;
  protected classification = 'INTERNAL';
  protected requiresAck = false;
  protected effectiveAt = '';
  protected expiresAt = '';
  protected titleEn = signal('');
  protected bodyEn = signal('');
  protected titleSw = signal('');
  protected bodySw = signal('');
  protected changeNote = '';
  protected audiences = signal<AudienceRule[]>([{ type: 'ALL', value: null }]);
  protected units = signal<UnitOption[]>([]);
  protected reach = signal<number | null>(null);
  protected busy = signal(false);
  private preview$ = new Subject<AudienceRule[]>();

  protected categories = computed(() => {
    this.i18n.lang();
    const list: Category[] = ['NOTICE', 'EVENT', 'CIRCULAR', 'DIRECTIVE_NOTICE'];
    if (this.session.can()?.emergencyAlerts) list.push('EMERGENCY_ALERT');
    return list;
  });
  protected langs = computed(() => {
    this.i18n.lang();
    return [
      { value: 'en', label: 'English' + (this.titleEn() ? ' ✓' : '') },
      { value: 'sw', label: 'Kiswahili' + (this.titleSw() ? ' ✓' : '') },
    ];
  });
  protected needsApproval = computed(() => ['CIRCULAR', 'DIRECTIVE_NOTICE'].includes(this.category()));
  protected valid = computed(() => !!((this.titleEn().trim() && plain(this.bodyEn())) || (this.titleSw().trim() && plain(this.bodySw()))));

  constructor() {
    this.api.get<UnitNode[]>('/dir/units/tree').subscribe((tree) => this.units.set(flatten(tree, 0)));
    this.preview$
      .pipe(
        debounceTime(350),
        switchMap((a) => this.api.post<{ count: number }>('/com/audience/preview', { audiences: a }).pipe(catchError(() => of(null)))),
      )
      .subscribe((r) => this.reach.set(r ? r.count : null));
    effect(() => {
      const id = this.id();
      if (id) this.api.get<ManageDetail>(`/com/announcements/${id}`).subscribe((a) => this.fill(a));
    });
    effect(() => this.preview$.next(this.audiences().filter((r) => r.type === 'ALL' || r.value)));
  }

  private fill(a: ManageDetail) {
    this.existing.set(a);
    this.category.set(a.category);
    this.priority = a.priority;
    this.classification = a.classification;
    this.requiresAck = a.requiresAck;
    this.effectiveAt = toLocal(a.effectiveAt);
    this.expiresAt = toLocal(a.expiresAt);
    this.titleEn.set(a.titleEn ?? '');
    this.bodyEn.set(a.bodyEn ?? '');
    this.titleSw.set(a.titleSw ?? '');
    this.bodySw.set(a.bodySw ?? '');
    this.audiences.set(a.audiences.length ? a.audiences : [{ type: 'ALL' }]);
  }

  protected setRule(i: number, patch: Partial<AudienceRule>) {
    this.audiences.update((l) => l.map((r, j) => (j === i ? { ...r, ...patch, ...(patch.type ? { value: patch.type === 'ALL' ? null : '' } : {}) } : r)));
  }
  protected addRule() {
    this.audiences.update((l) => [...l.filter((r) => r.type !== 'ALL'), { type: 'ORG_UNIT_TREE', value: '' }]);
  }
  protected removeRule(i: number) {
    this.audiences.update((l) => (l.length > 1 ? l.filter((_, j) => j !== i) : [{ type: 'ALL', value: null }]));
  }

  private input(): AnnouncementInput {
    return {
      category: this.category(),
      classification: this.classification,
      requiresAck: this.requiresAck || this.category() === 'EMERGENCY_ALERT',
      priority: this.category() === 'EMERGENCY_ALERT' ? 1 : this.priority,
      effectiveAt: this.effectiveAt ? new Date(this.effectiveAt).toISOString() : null,
      expiresAt: this.expiresAt ? new Date(this.expiresAt).toISOString() : null,
      titleEn: this.titleEn().trim() || undefined,
      bodyEn: plain(this.bodyEn()) ? this.bodyEn() : undefined,
      titleSw: this.titleSw().trim() || undefined,
      bodySw: plain(this.bodySw()) ? this.bodySw() : undefined,
      audiences: this.audiences().filter((r) => r.type === 'ALL' || r.value),
      changeNote: this.changeNote.trim() || undefined,
    };
  }

  /** Save as draft (and optionally submit). Amendments to a published notice are saved in one step. */
  protected save(submit: boolean) {
    this.busy.set(true);
    const body = this.input();
    const id = this.existing()?.publicId;
    const done = (publicId: string) => {
      if (!submit) {
        this.busy.set(false);
        this.toasts.show(this.i18n.t(this.amending() ? 'compose.amended' : 'compose.saved'));
        this.router.navigate(['/announcements/manage', publicId]);
        return;
      }
      this.api.post<{ status: string }>(`/com/announcements/${publicId}/submit`).subscribe({
        next: (r) => {
          this.toasts.show(this.i18n.t('manage.submitted.' + r.status));
          this.router.navigate(['/announcements/manage', publicId]);
        },
        error: (e) => {
          this.busy.set(false);
          this.toasts.error(e);
          this.router.navigate(['/announcements/manage', publicId]);
        },
      });
    };
    const fail = (e: unknown) => {
      this.busy.set(false);
      this.toasts.error(e);
    };
    if (!id) {
      this.api.post<{ publicId: string }>('/com/announcements', body).subscribe({ next: (r) => done(r.publicId), error: fail });
    } else if (this.amending()) {
      this.api.post(`/com/announcements/${id}/amend`, body).subscribe({ next: () => done(id), error: fail });
    } else {
      this.api.put(`/com/announcements/${id}`, body).subscribe({ next: () => done(id), error: fail });
    }
  }
}

function plain(html: string): string {
  return (html ?? '').replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').trim();
}

function flatten(nodes: UnitNode[], depth: number): UnitOption[] {
  return nodes.flatMap((n) => [{ code: n.code, label: `${' '.repeat(depth * 3)}${n.nameEn}` }, ...flatten(n.children, depth + 1)]);
}

function toLocal(iso?: string): string {
  if (!iso) return '';
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
