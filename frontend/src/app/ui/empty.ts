import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { Icon } from './icon';

/** Empty and error states say what to do next, in the interface's voice. */
@Component({
  selector: 'app-empty',
  imports: [Icon],
  template: `
    <app-icon [name]="icon()" />
    <p class="t-headline">{{ title() }}</p>
    @if (text()) {
      <p class="t-subhead secondary">{{ text() }}</p>
    }
    <ng-content />
  `,
  styles: `
    :host { display: grid; justify-items: center; gap: 6px; text-align: center; padding: 40px 20px; --icon-size: 40px; }
    app-icon { color: var(--label-3); margin-bottom: 6px; --icon-stroke: 1.5; }
    p { max-width: 44ch; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Empty {
  readonly icon = input('inbox');
  readonly title = input.required<string>();
  readonly text = input<string>();
}
