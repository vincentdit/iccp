import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Api } from '../../core/api.service';
import { I18n, TPipe } from '../../core/i18n.service';
import { StaffProfile } from '../../core/models';
import { Session } from '../../core/session.service';
import { Toasts } from '../../core/toast.service';
import { Avatar } from '../../ui/avatar';
import { Empty } from '../../ui/empty';
import { Icon } from '../../ui/icon';
import { Sheet } from '../../ui/sheet';

/** Contact card (SCR-DIR-02). On your own card you can edit what HR does not own. */
@Component({
  selector: 'app-profile',
  imports: [FormsModule, RouterLink, Avatar, Icon, Empty, Sheet, TPipe],
  template: `
    <div class="page page-narrow">
      <a class="back" routerLink="/directory"><app-icon name="chevronLeft" /> {{ 'nav.directory' | t }}</a>
      @if (p(); as p) {
        <header class="card-head">
          <app-avatar [name]="p.displayName" [size]="104" />
          <h1 class="t-title1">{{ p.displayName }}</h1>
          <p class="t-callout secondary">{{ p.positionTitle }}</p>
          <p class="t-subhead secondary">{{ unitName() }}</p>
          <div class="actions">
            <a class="action" [href]="'mailto:' + p.email"><span><app-icon name="mail" /></span>{{ 'dir.email' | t }}</a>
            @if (p.officePhone) {<a class="action" [href]="'tel:' + p.officePhone"><span><app-icon name="phone" /></span>{{ 'dir.call' | t }}</a>}
            @if (p.self) {<button class="action" type="button" (click)="startEdit()"><span><app-icon name="pencil" /></span>{{ 'common.edit' | t }}</button>}
          </div>
        </header>

        <ul class="list">
          <li class="list-row"><span class="label">{{ 'dir.emailLabel' | t }}</span><a class="grow val" [href]="'mailto:' + p.email">{{ p.email }}</a></li>
          @if (p.officePhone) {<li class="list-row"><span class="label">{{ 'dir.office' | t }}</span><a class="grow val num" [href]="'tel:' + p.officePhone">{{ p.officePhone }}</a></li>}
          @if (p.mobilePhone) {<li class="list-row"><span class="label">{{ 'dir.mobile' | t }}</span><a class="grow val num" [href]="'tel:' + p.mobilePhone">{{ p.mobilePhone }}</a></li>}
          @if (p.dutyStation) {<li class="list-row"><span class="label">{{ 'dir.station' | t }}</span><span class="grow">{{ p.dutyStation }}</span></li>}
          <li class="list-row"><span class="label">{{ 'dir.employeeNo' | t }}</span><span class="grow num">{{ p.employeeNo }}</span></li>
        </ul>

        @if (p.bio || p.expertise.length) {
          <div class="section">
            <div class="group-label">{{ 'dir.about' | t }}</div>
            <div class="card">
              @if (p.bio) {<p class="bio">{{ p.bio }}</p>}
              @if (p.expertise.length) {
                <div class="chips">@for (e of p.expertise; track e) {<a class="badge tint" [routerLink]="['/directory']" [queryParams]="{ q: e }">{{ e }}</a>}</div>
              }
            </div>
          </div>
        }

        <div class="section">
          <div class="group-label">{{ 'dir.reporting' | t }}</div>
          <div class="list">
            @if (p.lineManager; as m) {
              <a class="list-row" [routerLink]="['/directory', m.publicId]" style="--sep-inset: 60px">
                <app-avatar [name]="m.displayName" [size]="32" />
                <span class="grow"><span class="title">{{ m.displayName }}</span><span class="sub">{{ 'dir.reportsTo' | t }}, {{ m.positionTitle }}</span></span>
                <app-icon class="chev" name="chevronRight" />
              </a>
            }
            @for (r of p.directReports; track r.publicId) {
              <a class="list-row" [routerLink]="['/directory', r.publicId]" style="--sep-inset: 60px">
                <app-avatar [name]="r.displayName" [size]="32" />
                <span class="grow"><span class="title">{{ r.displayName }}</span><span class="sub">{{ r.positionTitle }}</span></span>
                <app-icon class="chev" name="chevronRight" />
              </a>
            }
            @if (!p.lineManager && !p.directReports.length) {<div class="list-empty">{{ 'dir.noReporting' | t }}</div>}
          </div>
          @if (p.directReports.length) {<p class="group-foot">{{ 'dir.directReports' | t: { n: p.directReports.length } }}</p>}
        </div>

        @if (p.actingRoles.length) {
          <div class="section">
            <div class="group-label">{{ 'dir.acting' | t }}</div>
            <ul class="list">
              @for (a of p.actingRoles; track $index) {
                <li class="list-row"><span class="grow"><span class="title">{{ a.positionTitle }}</span><span class="sub">{{ a.unitName }}, {{ i18n.date(a.startsOn) }} – {{ i18n.date(a.endsOn) }}</span></span></li>
              }
            </ul>
          </div>
        }
        <p class="group-foot hr-note">{{ 'dir.hrOwned' | t }}</p>

        <app-sheet [title]="'dir.editTitle' | t" [open]="editing()" (closed)="editing.set(false)">
          <div class="stack">
            <label class="field"><span class="label">{{ 'dir.bio' | t }}</span><textarea class="textarea" name="bio" [(ngModel)]="bio" maxlength="1000"></textarea></label>
            <label class="field"><span class="label">{{ 'dir.expertise' | t }}</span><input class="input" name="exp" [(ngModel)]="expertise" [placeholder]="'dir.expertiseHint' | t" /></label>
            <div class="list">
              <label class="list-row"><span class="grow"><span class="title">{{ 'dir.showMobile' | t }}</span><span class="sub">{{ 'dir.showMobileHelp' | t }}</span></span><input type="checkbox" class="switch" name="mv" [(ngModel)]="mobileVisible" /></label>
            </div>
          </div>
          <div sheet-actions>
            <button class="btn" type="button" (click)="editing.set(false)">{{ 'common.cancel' | t }}</button>
            <button class="btn btn-filled" type="button" (click)="save()">{{ 'common.save' | t }}</button>
          </div>
        </app-sheet>
      } @else if (missing()) {
        <app-empty icon="user" [title]="'dir.missing' | t" />
      } @else {
        <div class="stack" style="margin-top: 40px; display: grid; justify-items: center"><div class="skeleton" style="width: 104px; height: 104px; border-radius: 50%"></div><div class="skeleton" style="width: 200px"></div></div>
      }
    </div>
  `,
  styles: `
    .back { display: inline-flex; align-items: center; gap: 2px; margin: 16px 0 12px -6px; --icon-size: 22px; }
    .card-head { display: grid; justify-items: center; text-align: center; gap: 4px; margin-bottom: 24px; animation: rise var(--dur-3) var(--spring); }
    .card-head h1 { margin-top: 14px; }
    .actions { display: flex; gap: 10px; margin-top: 18px; }
    .action { display: grid; justify-items: center; gap: 4px; min-width: 76px; padding: 10px 8px 8px; border-radius: var(--r-md); border: 0; background: var(--bg-elevated); box-shadow: var(--shadow-1); color: var(--tint); font-size: var(--fs-caption); font-weight: 500; cursor: pointer; text-decoration: none !important; transition: transform var(--dur-1) var(--spring); }
    .action span { --icon-size: 22px; }
    .action:active { transform: scale(0.95); }
    .label { width: 120px; flex: none; color: var(--label-2); font-size: var(--fs-subhead); }
    .val { overflow: hidden; text-overflow: ellipsis; }
    .bio { white-space: pre-wrap; line-height: 1.5; }
    .chips { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 12px; }
    .bio + .chips { margin-top: 14px; }
    .chips .badge { font-size: var(--fs-footnote); padding: 4px 10px; text-decoration: none; }
    .hr-note { margin-top: 24px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Profile {
  private api = inject(Api);
  private toasts = inject(Toasts);
  private session = inject(Session);
  protected i18n = inject(I18n);

  readonly id = input<string>();
  protected p = signal<StaffProfile | null>(null);
  protected missing = signal(false);
  protected editing = signal(false);
  protected bio = '';
  protected expertise = '';
  protected mobileVisible = false;
  protected unitName = computed(() => {
    const p = this.p();
    return p ? (this.i18n.lang() === 'sw' && p.unitNameSw ? p.unitNameSw : p.unitName) : '';
  });

  constructor() {
    effect(() => {
      const id = this.id();
      this.api.get<StaffProfile>(id ? `/dir/staff/${id}` : '/dir/me').subscribe({ next: (p) => this.p.set(p), error: () => this.missing.set(true) });
    });
  }

  protected startEdit() {
    const p = this.p()!;
    this.bio = p.bio ?? '';
    this.expertise = p.expertise.join(', ');
    this.mobileVisible = p.mobileVisible;
    this.editing.set(true);
  }

  protected save() {
    const body = {
      bio: this.bio.trim(),
      expertise: this.expertise.split(',').map((e) => e.trim()).filter(Boolean),
      mobileVisible: this.mobileVisible,
    };
    this.api.patch<StaffProfile>('/dir/me', body).subscribe({
      next: (p) => {
        this.p.set(p);
        this.editing.set(false);
        this.toasts.show(this.i18n.t('dir.saved'));
      },
      error: (e) => this.toasts.error(e),
    });
  }
}
