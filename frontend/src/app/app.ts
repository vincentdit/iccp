import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { Settings } from './core/settings.service';
import { Toasts } from './core/toast.service';
import { Icon } from './ui/icon';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, Icon],
  template: `
    <router-outlet />
    <div class="toasts" aria-live="polite">
      @for (t of toasts.items(); track t.id) {
        <div class="toast" [class]="t.kind" (click)="toasts.dismiss(t.id)">
          <app-icon [name]="t.kind === 'error' ? 'alert' : t.kind === 'success' ? 'checkCircle' : 'info'" />
          <span>{{ t.text }}</span>
        </div>
      }
    </div>
  `,
  styles: `
    .toasts { position: fixed; z-index: 200; top: 14px; left: 50%; transform: translateX(-50%); display: grid; gap: 8px; width: min(92vw, 440px); pointer-events: none; }
    .toast {
      pointer-events: auto; display: flex; align-items: center; gap: 10px; padding: 12px 16px; border-radius: 22px;
      background: var(--glass-strong); backdrop-filter: var(--glass-blur); -webkit-backdrop-filter: var(--glass-blur);
      box-shadow: inset 0 0 0 0.5px var(--glass-edge), var(--shadow-3); font-size: var(--fs-subhead); font-weight: 500;
      animation: drop var(--dur-3) var(--spring-bounce); --icon-size: 20px; cursor: default;
    }
    .toast.success app-icon { color: var(--green); }
    .toast.error app-icon { color: var(--red); }
    .toast.info app-icon { color: var(--tint); }
    @keyframes drop { from { opacity: 0; transform: translateY(-24px) scale(0.92); } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  protected toasts = inject(Toasts);
  // Instantiated at start so appearance preferences apply before first paint of the routes.
  private settings = inject(Settings);
}
