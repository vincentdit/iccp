import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { initials } from '../core/session.service';

const HUES = ['#5e5ce6', '#0e7c86', '#bf5af2', '#ff9f0a', '#30b0c7', '#ff375f', '#34c759', '#64748b'];

/** Initials avatar; the colour is stable per person so lists are easier to scan. */
@Component({
  selector: 'app-avatar',
  template: `{{ letters() }}`,
  host: { class: 'avatar', '[style.--avatar.px]': 'size()', '[style.background]': 'bg()', 'aria-hidden': 'true' },
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Avatar {
  readonly name = input('');
  readonly size = input(36);
  protected letters = computed(() => initials(this.name()));
  protected bg = computed(() => {
    let h = 0;
    for (const ch of this.name()) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
    const c = HUES[h % HUES.length];
    return `linear-gradient(180deg, color-mix(in srgb, ${c} 78%, white), ${c})`;
  });
}
