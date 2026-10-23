import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, afterNextRender, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Icon } from './icon';

/**
 * Large title that collapses into the window toolbar when it scrolls out of view (HIG: navigation
 * bars). Trailing actions are projected; an optional back link sits above the title.
 */
@Component({
  selector: 'app-page-header',
  imports: [RouterLink, Icon],
  template: `
    <div class="bar" [class.visible]="collapsed()" aria-hidden="true">
      @if (back()) {
        <a class="bar-back" [routerLink]="back()" tabindex="-1"><app-icon name="chevronLeft" /></a>
      }
      <span class="bar-title">{{ title() }}</span>
    </div>
    @if (back()) {
      <a class="back" [routerLink]="back()"><app-icon name="chevronLeft" /> {{ backLabel() }}</a>
    }
    <div class="head">
      <div class="titles">
        <h1 class="t-large">{{ title() }}</h1>
        @if (subtitle()) {
          <p class="sub">{{ subtitle() }}</p>
        }
      </div>
      <div class="actions"><ng-content /></div>
    </div>
  `,
  styles: `
    :host { display: block; padding-top: 12px; margin-bottom: 18px; }
    .head { display: flex; align-items: flex-end; gap: 12px; flex-wrap: wrap; }
    .titles { flex: 1; min-width: 0; }
    h1 { overflow-wrap: anywhere; }
    .sub { margin-top: 4px; color: var(--label-2); font-size: var(--fs-subhead); }
    .actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .back { display: inline-flex; align-items: center; gap: 2px; margin: 0 0 6px -6px; font-size: var(--fs-body); --icon-size: 22px; }
    .bar {
      position: fixed; z-index: 30; top: 0; left: var(--content-left, 0); right: 0; height: var(--toolbar-h);
      display: flex; align-items: center; justify-content: center; gap: 8px; padding: 0 56px;
      background: var(--glass); backdrop-filter: var(--glass-blur); -webkit-backdrop-filter: var(--glass-blur);
      border-bottom: 0.5px solid var(--separator);
      opacity: 0; transform: translateY(-6px); pointer-events: none;
      transition: opacity var(--dur-1) var(--ease-out), transform var(--dur-2) var(--spring);
    }
    .bar.visible { opacity: 1; transform: none; pointer-events: auto; }
    .bar-title { font-weight: 600; font-size: var(--fs-headline); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .bar-back { position: absolute; left: 12px; display: grid; place-items: center; --icon-size: 24px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PageHeader {
  readonly title = input.required<string>();
  readonly subtitle = input<string>();
  readonly back = input<string>();
  readonly backLabel = input('Back');
  protected collapsed = signal(false);
  private el = inject(ElementRef<HTMLElement>);

  constructor() {
    const destroy = inject(DestroyRef);
    afterNextRender(() => {
      const h1 = (this.el.nativeElement as HTMLElement).querySelector('h1');
      if (!h1) return;
      const io = new IntersectionObserver(([e]) => this.collapsed.set(!e.isIntersecting && e.boundingClientRect.top < 0), {
        rootMargin: '-52px 0px 0px 0px',
      });
      io.observe(h1);
      destroy.onDestroy(() => io.disconnect());
    });
  }
}
