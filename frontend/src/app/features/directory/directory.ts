import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subject, debounceTime } from 'rxjs';
import { Api } from '../../core/api.service';
import { I18n, TPipe } from '../../core/i18n.service';
import { Page, StaffSummary } from '../../core/models';
import { Avatar } from '../../ui/avatar';
import { Empty } from '../../ui/empty';
import { Icon } from '../../ui/icon';
import { PageHeader } from '../../ui/page-header';

/** Staff directory (SCR-DIR-01): search by name, position, unit or expertise. */
@Component({
  selector: 'app-directory',
  imports: [FormsModule, RouterLink, PageHeader, Avatar, Icon, Empty, TPipe],
  template: `
    <div class="page">
      <app-page-header [title]="'nav.directory' | t">
        <a class="btn" routerLink="/directory/organisation"><app-icon name="network" /> {{ 'dir.organisation' | t }}</a>
      </app-page-header>
      <div class="search big">
        <app-icon name="search" />
        <input class="input" type="search" [(ngModel)]="q" (ngModelChange)="q$.next($event)" [placeholder]="'dir.searchHint' | t" [attr.aria-label]="'dir.searchHint' | t" autofocus />
      </div>
      <p class="group-foot count" aria-live="polite">{{ 'dir.count' | t: { n: total() } }}</p>

      @for (g of groups(); track g.letter) {
        <div class="group-label letter">{{ g.letter }}</div>
        <div class="list">
          @for (s of g.people; track s.publicId) {
            <a class="list-row" [routerLink]="['/directory', s.publicId]" style="--sep-inset: 64px">
              <app-avatar [name]="s.displayName" [size]="38" />
              <span class="grow">
                <span class="title">{{ s.displayName }}</span>
                <span class="sub">{{ s.positionTitle }}, {{ s.unitName }}</span>
              </span>
              @if (s.officePhone) {<span class="t-footnote secondary hide-sm num">{{ s.officePhone }}</span>}
              <app-icon class="chev" name="chevronRight" />
            </a>
          }
        </div>
      } @empty {
        @if (!loading()) {<app-empty icon="users" [title]="'dir.none' | t" [text]="'dir.noneText' | t" />}
      }
    </div>
  `,
  styles: `
    .search.big .input { min-height: 44px; font-size: var(--fs-body); }
    .count { margin-bottom: 8px; }
    .letter { margin-top: 18px; font-weight: 600; }
    @media (max-width: 720px) { .hide-sm { display: none; } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Directory {
  private api = inject(Api);
  private router = inject(Router);
  protected i18n = inject(I18n);
  protected q = inject(ActivatedRoute).snapshot.queryParamMap.get('q') ?? '';
  protected q$ = new Subject<string>();
  private term = signal(this.q);
  protected people = signal<StaffSummary[]>([]);
  protected total = signal(0);
  protected loading = signal(true);

  protected groups = computed(() => {
    const by = new Map<string, StaffSummary[]>();
    for (const p of this.people()) {
      const letter = (p.displayName.replace(/^(Dr|Prof|Eng|CPA)\.?\s+/i, '')[0] ?? '#').toUpperCase();
      by.set(letter, [...(by.get(letter) ?? []), p]);
    }
    return [...by.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([letter, people]) => ({ letter, people }));
  });

  constructor() {
    this.q$.pipe(debounceTime(220)).subscribe((v) => {
      this.term.set(v.trim());
      this.router.navigate([], { queryParams: { q: v.trim() || null }, replaceUrl: true });
    });
    effect(() => {
      this.loading.set(true);
      this.api.get<Page<StaffSummary>>('/dir/staff', { q: this.term(), size: 200 }).subscribe((p) => {
        this.people.set(p.items);
        this.total.set(p.total);
        this.loading.set(false);
      });
    });
  }
}
