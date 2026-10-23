import { ChangeDetectionStrategy, Component, ElementRef, effect, input, model, viewChild } from '@angular/core';
import { Icon } from '../../ui/icon';

/**
 * Minimal rich-text field: bold, italic, lists and links — the formatting the server keeps after
 * sanitising (HtmlSanitizer). Paste is reduced to plain text so Word styling never leaks in.
 */
@Component({
  selector: 'app-rich-text',
  imports: [Icon],
  template: `
    <div class="editor">
      <div class="toolbar" role="toolbar" [attr.aria-label]="label()">
        <button type="button" (mousedown)="$event.preventDefault(); cmd('bold')" aria-label="Bold"><b>B</b></button>
        <button type="button" (mousedown)="$event.preventDefault(); cmd('italic')" aria-label="Italic"><i>I</i></button>
        <button type="button" (mousedown)="$event.preventDefault(); cmd('insertUnorderedList')" aria-label="Bulleted list">•</button>
        <button type="button" (mousedown)="$event.preventDefault(); cmd('insertOrderedList')" aria-label="Numbered list">1.</button>
        <button type="button" (mousedown)="$event.preventDefault(); link()" aria-label="Link"><app-icon name="external" /></button>
      </div>
      <div #area class="area prose" contenteditable="true" role="textbox" aria-multiline="true" [attr.aria-label]="label()"
        [attr.data-placeholder]="placeholder()" (input)="sync()" (blur)="sync()" (paste)="paste($event)"></div>
    </div>
  `,
  styles: `
    .editor { border-radius: var(--r-md); background: var(--bg-elevated); box-shadow: inset 0 0 0 0.5px var(--separator); overflow: hidden; }
    .editor:focus-within { box-shadow: inset 0 0 0 1px var(--tint), 0 0 0 4px var(--tint-soft); }
    .toolbar { display: flex; gap: 2px; padding: 6px; border-bottom: 0.5px solid var(--separator); }
    .toolbar button { min-width: 34px; height: 30px; border: 0; border-radius: 7px; background: transparent; cursor: pointer; color: var(--label); --icon-size: 15px; display: grid; place-items: center; }
    .toolbar button:hover { background: var(--fill-quiet); }
    .area { min-height: 180px; padding: 12px 14px; outline: none; max-width: none; }
    .area:empty::before { content: attr(data-placeholder); color: var(--label-3); }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RichText {
  readonly value = model('');
  readonly label = input('');
  readonly placeholder = input('');
  private area = viewChild.required<ElementRef<HTMLDivElement>>('area');
  private last = '';

  constructor() {
    effect(() => {
      const v = this.value() ?? '';
      const el = this.area().nativeElement;
      if (v !== this.last) {
        el.innerHTML = v;
        this.last = v;
      }
    });
  }

  protected cmd(name: string) {
    document.execCommand(name);
    this.sync();
  }

  protected link() {
    const url = prompt('Link address (https://…)');
    if (url && /^(https?:\/\/|mailto:)/i.test(url)) this.cmd2('createLink', url);
  }

  private cmd2(name: string, arg: string) {
    document.execCommand(name, false, arg);
    this.sync();
  }

  protected paste(e: ClipboardEvent) {
    e.preventDefault();
    const text = e.clipboardData?.getData('text/plain') ?? '';
    document.execCommand('insertText', false, text);
  }

  protected sync() {
    const html = this.area().nativeElement.innerHTML.replace(/^<br>$/, '');
    this.last = html;
    this.value.set(html);
  }
}
