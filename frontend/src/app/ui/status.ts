import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { I18n } from '../core/i18n.service';

const TONE: Record<string, string> = {
  PUBLISHED: 'green', APPROVED: 'tint', FULLY_PAID: 'green', RESOLVED: 'green', CLOSED: 'green', APPLIED: 'green', SUCCESS: 'green',
  PARTLY_PAID: 'orange', IN_REVIEW: 'orange', RECONCILED: 'orange', OPEN: 'orange', REOPENED: 'orange', SCHEDULED: 'indigo',
  RESPONDED: 'tint', UNDER_QUERY: 'indigo', ESCALATED: 'red', REJECTED: 'red', WITHDRAWN: 'red', CANCELLED: 'red', DENIED: 'red',
  RETURNED: 'orange', DRAFT: '', ARCHIVED: '', EXPIRED: '', RECEIVED: '', ERROR: 'red', PARTIAL: 'orange',
};

/** Status pill: colour carries meaning, the label always says it in words too. */
@Component({
  selector: 'app-status',
  template: `{{ text() }}`,
  host: { '[class]': '"badge " + tone()' },
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Status {
  private i18n = inject(I18n);
  readonly value = input.required<string>();
  protected tone = computed(() => TONE[this.value()] ?? '');
  protected text = computed(() => {
    const key = 'status.' + this.value();
    const t = this.i18n.t(key);
    return t === key ? this.value().replaceAll('_', ' ').toLowerCase().replace(/^./, (c) => c.toUpperCase()) : t;
  });
}
