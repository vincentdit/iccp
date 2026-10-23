import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api } from '../../core/api.service';
import { I18n, TPipe } from '../../core/i18n.service';
import { UnitNode } from '../../core/models';
import { Avatar } from '../../ui/avatar';
import { Icon } from '../../ui/icon';
import { PageHeader } from '../../ui/page-header';

/** One level of the organisation outline; disclosure triangles expand children (HIG: outline views). */
@Component({
  selector: 'app-unit-node',
  imports: [RouterLink, Avatar, Icon, TPipe],
  template: `
    <div class="node" [style.--depth]="depth()">
      <button class="disclose" type="button" [class.open]="open()" [style.visibility]="unit().children.length ? 'visible' : 'hidden'"
        (click)="open.set(!open())" [attr.aria-expanded]="open()" [attr.aria-label]="unit().nameEn"><app-icon name="chevronRight" /></button>
      <div class="grow">
        <div class="t-headline name">{{ i18n.lang() === 'sw' && unit().nameSw ? unit().nameSw : unit().nameEn }}</div>
        <div class="t-footnote secondary">{{ ('unitType.' + unit().unitType) | t }}, {{ 'org.staff' | t: { n: unit().staffCount } }}</div>
      </div>
      @if (unit().head; as h) {
        <a class="head" [routerLink]="['/directory', h.publicId]">
          <app-avatar [name]="h.displayName" [size]="30" />
          <span class="hide-sm"><span class="t-subhead">{{ h.displayName }}</span>@if (unit().headActing) {<span class="badge orange">{{ 'org.acting' | t }}</span>}<br /><span class="t-caption secondary">{{ h.positionTitle }}</span></span>
        </a>
      }
    </div>
    @if (open()) {
      <div class="children">
        @for (c of unit().children; track c.id) {
          <app-unit-node [unit]="c" [depth]="depth() + 1" />
        }
      </div>
    }
  `,
  styles: `
    :host { display: block; }
    .node { display: flex; align-items: center; gap: 10px; padding: 10px 16px 10px calc(8px + var(--depth) * 24px); min-height: 56px; border-top: 0.5px solid var(--separator); }
    :host(:first-child) > .node { border-top: 0; }
    .grow { flex: 1; min-width: 0; }
    .disclose { width: 28px; height: 28px; border: 0; border-radius: 6px; background: transparent; color: var(--label-2); display: grid; place-items: center; cursor: pointer; --icon-size: 16px; transition: transform var(--dur-2) var(--spring); }
    .disclose.open { transform: rotate(90deg); }
    .head { display: flex; align-items: center; gap: 8px; color: inherit; text-decoration: none !important; padding: 4px 8px; border-radius: 10px; }
    .head:hover { background: var(--fill-quiet); }
    .head .badge { margin-left: 6px; }
    .children { animation: rise var(--dur-2) var(--spring); }
    @media (max-width: 720px) { .hide-sm { display: none; } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UnitNodeView {
  protected i18n = inject(I18n);
  readonly unit = input.required<UnitNode>();
  readonly depth = input(0);
  protected open = signal(true);
}

@Component({
  selector: 'app-org-chart',
  imports: [PageHeader, UnitNodeView, TPipe],
  template: `
    <div class="page">
      <app-page-header [title]="'dir.organisation' | t" back="/directory" [backLabel]="'nav.directory' | t" />
      <div class="list tree">
        @for (u of tree(); track u.id) {
          <app-unit-node [unit]="u" />
        }
      </div>
      <p class="group-foot">{{ 'org.help' | t }}</p>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrgChart {
  protected tree = signal<UnitNode[]>([]);
  constructor() {
    inject(Api).get<UnitNode[]>('/dir/units/tree').subscribe((t) => this.tree.set(t));
  }
}
