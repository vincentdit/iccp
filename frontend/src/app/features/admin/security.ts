import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from '../../core/api.service';
import { I18n, TPipe } from '../../core/i18n.service';
import { AuditRow, NotificationOperationsSummary, Page, RoleAssignment, RoleInfo, StaffSummary, UserAccount } from '../../core/models';
import { Session } from '../../core/session.service';
import { Toasts } from '../../core/toast.service';
import { Avatar } from '../../ui/avatar';
import { Empty } from '../../ui/empty';
import { Icon } from '../../ui/icon';
import { PageHeader } from '../../ui/page-header';
import { Segmented } from '../../ui/segmented';
import { Sheet } from '../../ui/sheet';
import { Status } from '../../ui/status';

/** Access and audit (SCR-SEC-01/02): role grants with approval evidence, and the tamper-evident audit trail. */
@Component({
  selector: 'app-security-admin',
  imports: [FormsModule, PageHeader, Segmented, Icon, Avatar, Empty, Sheet, Status, TPipe],
  templateUrl: './security.html',
  styleUrl: './security.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SecurityAdmin {
  private api = inject(Api);
  private toasts = inject(Toasts);
  protected i18n = inject(I18n);
  protected session = inject(Session);

  protected tab = signal(this.session.can()?.securityAdmin ? 'users' : 'audit');
  protected tabs = computed(() => {
    this.i18n.lang();
    const t = [];
    if (this.session.can()?.securityAdmin) t.push({ value: 'users', label: this.i18n.t('sec.users') });
    if (this.session.can()?.securityAdmin) t.push({ value: 'roles', label: this.i18n.t('sec.roles') });
    t.push({ value: 'notifications', label: this.i18n.t('ntfOps.tab') });
    t.push({ value: 'audit', label: this.i18n.t('sec.audit') });
    return t;
  });

  protected roles = signal<RoleInfo[]>([]);
  protected assignments = signal<RoleAssignment[]>([]);
  protected roleFilter = signal('');
  protected grouped = computed(() => {
    const by = new Map<string, RoleAssignment[]>();
    for (const a of this.assignments()) by.set(a.roleCode, [...(by.get(a.roleCode) ?? []), a]);
    return [...by.entries()].map(([code, list]) => ({ code, name: list[0].roleName, privileged: list[0].privileged, list }));
  });

  protected granting = signal(false);
  protected staffQuery = '';
  protected staffResults = signal<StaffSummary[]>([]);
  protected grant = { staff: null as StaffSummary | null, roleCode: '', endsOn: '', approvedBy: '', approvalRef: '' };
  protected grantRole = computed(() => this.roles().find((r) => r.code === this.grantCode()));
  private grantCode = signal('');

  protected audit = signal<AuditRow[]>([]);
  protected auditTotal = signal(0);
  protected filters = { actor: '', module: '', action: '', resourceId: '' };
  protected chain = signal<{ intact: boolean; firstBrokenSeq?: number; events: number } | null>(null);
  protected expanded = signal<number | null>(null);
  protected notificationOps = signal<NotificationOperationsSummary | null>(null);
  protected users = signal<UserAccount[]>([]);
  protected usersTotal = signal(0);
  protected userFilters = { q: '', status: 'ALL' };
  protected userBusy = signal<number | null>(null);


  constructor() {
    effect(() => {
      if (this.tab() === 'users') {
        this.loadUsers();
      } else if (this.tab() === 'roles') {
        this.api.get<RoleInfo[]>('/sec/roles').subscribe((r) => this.roles.set(r));
        this.loadAssignments();
      } else if (this.tab() === 'notifications') {
        this.loadNotificationOps();
      } else {
        this.searchAudit();
      }
    });
  }

  protected loadUsers() {
    this.api.get<Page<UserAccount>>('/sec/users', { ...this.userFilters, size: 100 }).subscribe({
      next: (page) => {
        this.users.set(page.items);
        this.usersTotal.set(page.total);
      },
      error: (e) => this.toasts.error(e),
    });
  }

  protected setUserStatus(user: UserAccount) {
    const next = user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE';
    if (!confirm(this.i18n.t(next === 'DISABLED' ? 'sec.disableConfirm' : 'sec.enableConfirm', { name: user.staffName }))) return;
    this.userBusy.set(user.id);
    this.api.post('/sec/users/' + user.id + '/status', { status: next }).subscribe({
      next: () => {
        this.toasts.show(this.i18n.t(next === 'ACTIVE' ? 'sec.enabled' : 'sec.disabled'));
        this.userBusy.set(null);
        this.loadUsers();
      },
      error: (e) => { this.userBusy.set(null); this.toasts.error(e); },
    });
  }

  protected retryDelivery(notificationId: number, channel: string) {
    if (!confirm(this.i18n.t('ntfOps.retryConfirm'))) return;
    this.api.post('/ntf/admin/dead-letters/' + notificationId + '/' + channel + '/retry').subscribe({
      next: () => {
        this.toasts.show(this.i18n.t('ntfOps.requeued'));
        this.loadNotificationOps();
      },
      error: (e) => this.toasts.error(e),
    });
  }

  protected loadNotificationOps() {
    this.api.get<NotificationOperationsSummary>('/ntf/admin/summary').subscribe({
      next: (summary) => this.notificationOps.set(summary),
      error: (e) => this.toasts.error(e),
    });
  }

  protected loadAssignments() {
    this.api.get<RoleAssignment[]>('/sec/role-assignments', { role: this.roleFilter() }).subscribe((l) => this.assignments.set(l));
  }

  protected findStaff() {
    const q = this.staffQuery.trim();
    if (q.length < 2) return this.staffResults.set([]);
    this.api.get<Page<StaffSummary>>('/dir/staff', { q, size: 6 }).subscribe((p) => this.staffResults.set(p.items));
  }

  protected setRole(code: string) {
    this.grant.roleCode = code;
    this.grantCode.set(code);
  }

  protected submitGrant() {
    const g = this.grant;
    this.api
      .post('/sec/role-assignments', {
        staffPublicId: g.staff?.publicId,
        roleCode: g.roleCode,
        endsOn: g.endsOn || null,
        approvedBy: g.approvedBy || null,
        approvalRef: g.approvalRef || null,
      })
      .subscribe({
        next: () => {
          this.toasts.show(this.i18n.t('sec.granted'));
          this.granting.set(false);
          this.grant = { staff: null, roleCode: '', endsOn: '', approvedBy: '', approvalRef: '' };
          this.grantCode.set('');
          this.loadAssignments();
        },
        error: (e) => this.toasts.error(e),
      });
  }

  protected revoke(a: RoleAssignment) {
    if (!confirm(this.i18n.t('sec.revokeConfirm', { role: a.roleName, name: a.staffName }))) return;
    this.api.delete(`/sec/role-assignments/${a.id}`).subscribe({
      next: () => {
        this.toasts.show(this.i18n.t('sec.revoked'));
        this.loadAssignments();
      },
      error: (e) => this.toasts.error(e),
    });
  }

  protected searchAudit() {
    this.api.get<Page<AuditRow>>('/sec/audit', { ...this.filters, size: 100 }).subscribe((p) => {
      this.audit.set(p.items);
      this.auditTotal.set(p.total);
    });
  }

  protected verify() {
    this.chain.set(null);
    this.api.post<{ intact: boolean; firstBrokenSeq?: number; events: number }>('/sec/audit/verify').subscribe({
      next: (c) => this.chain.set(c),
      error: (e) => this.toasts.error(e),
    });
  }

  protected details(r: AuditRow): string {
    try {
      return JSON.stringify(JSON.parse(r.details ?? '{}'), null, 2);
    } catch {
      return r.details ?? '';
    }
  }
}
