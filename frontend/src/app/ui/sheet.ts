import { ChangeDetectionStrategy, Component, ElementRef, effect, inject, input, output, viewChild } from '@angular/core';
import { Icon } from './icon';

/**
 * Modal sheet built on <dialog>: it rises from the bottom on narrow screens and appears as a centred
 * card on wide ones (HIG: sheets). Escape and the close button dismiss it; focus stays inside.
 */
@Component({
  selector: 'app-sheet',
  imports: [Icon],
  template: `
    <dialog #dlg (close)="closed.emit()" (click)="backdrop($event)" [attr.aria-labelledby]="'sheet-title-' + uid">
      <div class="sheet" [style.max-width]="width()">
        <header>
          <h2 class="t-headline" [id]="'sheet-title-' + uid">{{ title() }}</h2>
          <button class="icon-btn close" type="button" (click)="dlg.close()" aria-label="Close"><app-icon name="x" /></button>
        </header>
        <div class="body"><ng-content /></div>
        <footer><ng-content select="[sheet-actions]" /></footer>
      </div>
    </dialog>
  `,
  styles: `
    dialog { padding: 0; border: 0; background: transparent; max-width: 100vw; max-height: 100dvh; overflow: visible; color: inherit; }
    dialog::backdrop { background: var(--scrim); animation: fade var(--dur-2) var(--ease-out); }
    .sheet {
      width: min(92vw, 560px); max-height: min(86dvh, 820px); display: flex; flex-direction: column;
      background: var(--bg-tertiary); border-radius: var(--r-xl); box-shadow: var(--shadow-3);
      animation: pop var(--dur-2) var(--spring);
    }
    :root[data-scheme='dark'] .sheet { background: var(--bg-elevated); }
    header { display: flex; align-items: center; justify-content: center; position: relative; padding: 16px 52px 8px; }
    h2 { text-align: center; }
    .close { position: absolute; right: 10px; top: 8px; color: var(--label-2); background: var(--fill); width: 30px; height: 30px; --icon-size: 16px; }
    .body { padding: 8px 20px 12px; overflow: auto; }
    footer { display: flex; gap: 10px; justify-content: flex-end; padding: 8px 20px 20px; }
    footer:empty { display: none; }
    @media (max-width: 720px) {
      dialog { margin: auto 0 0; width: 100vw; }
      .sheet { width: 100vw; max-width: none !important; border-radius: var(--r-xl) var(--r-xl) 0 0; animation: slide-up var(--dur-3) var(--spring); padding-bottom: env(safe-area-inset-bottom); }
    }
    @keyframes fade { from { opacity: 0; } }
    @keyframes slide-up { from { transform: translateY(100%); } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Sheet {
  private static seq = 0;
  protected uid = ++Sheet.seq;
  readonly title = input.required<string>();
  readonly open = input(false);
  readonly width = input('560px');
  readonly closed = output<void>();
  private dlg = viewChild.required<ElementRef<HTMLDialogElement>>('dlg');

  constructor() {
    effect(() => {
      const d = this.dlg().nativeElement;
      if (this.open() && !d.open) d.showModal();
      if (!this.open() && d.open) d.close();
    });
  }

  protected backdrop(e: MouseEvent) {
    if (e.target === this.dlg().nativeElement) this.dlg().nativeElement.close();
  }
}
