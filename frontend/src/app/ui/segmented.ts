import { ChangeDetectionStrategy, Component, computed, input, model } from '@angular/core';

export interface Segment {
  value: string;
  label: string;
}

/** HIG segmented control: the thumb slides with a spring to the selected segment. */
@Component({
  selector: 'app-segmented',
  template: `
    <div class="segmented" role="group" [attr.aria-label]="label()" [style.--seg-count]="segments().length" [style.--seg-index]="index()">
      <span class="thumb" aria-hidden="true"></span>
      @for (s of segments(); track s.value) {
        <button type="button" [attr.aria-pressed]="s.value === value()" (click)="value.set(s.value)">{{ s.label }}</button>
      }
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Segmented {
  readonly segments = input.required<Segment[]>();
  readonly label = input('');
  readonly value = model.required<string>();
  protected index = computed(() => Math.max(0, this.segments().findIndex((s) => s.value === this.value())));
}
