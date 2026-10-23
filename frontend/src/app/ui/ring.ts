import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * Settlement ring for the Financial Diary: the arc fills to the share paid, drawn with a spring on
 * first view (the one signature motion in the app). Colour moves from orange to green as it settles.
 */
@Component({
  selector: 'app-ring',
  template: `
    <svg [attr.viewBox]="'0 0 ' + box + ' ' + box" [style.width.px]="size()" [style.height.px]="size()" role="img" [attr.aria-label]="label()">
      <circle [attr.cx]="c" [attr.cy]="c" [attr.r]="r" class="track" [attr.stroke-width]="stroke()" />
      <circle [attr.cx]="c" [attr.cy]="c" [attr.r]="r" class="arc" [attr.stroke-width]="stroke()" [class.done]="pct() >= 100"
        [attr.stroke-dasharray]="circ" [style.--target]="offset()" [attr.stroke-dashoffset]="offset()" />
    </svg>
    <div class="center"><ng-content /></div>
  `,
  styles: `
    :host { position: relative; display: inline-grid; place-items: center; flex: none; }
    svg { transform: rotate(-90deg); overflow: visible; }
    .track { fill: none; stroke: var(--fill); }
    .arc { fill: none; stroke: var(--ring-color, var(--orange)); stroke-linecap: round; animation: draw 1100ms var(--spring) both; }
    .arc.done { stroke: var(--green); }
    .center { position: absolute; inset: 0; display: grid; place-items: center; text-align: center; }
    @keyframes draw { from { stroke-dashoffset: 276.46; } to { stroke-dashoffset: var(--target); } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Ring {
  readonly pct = input(0);
  readonly size = input(168);
  readonly stroke = input(12);
  readonly label = input('');
  protected readonly box = 100;
  protected readonly c = 50;
  protected readonly r = 44;
  protected readonly circ = 2 * Math.PI * 44;
  protected offset = computed(() => this.circ * (1 - Math.min(100, Math.max(0, this.pct())) / 100));
}
