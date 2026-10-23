import { DOCUMENT } from '@angular/common';
import { Injectable, effect, inject, signal } from '@angular/core';

export type Theme = 'system' | 'light' | 'dark';
export type Contrast = 'system' | 'normal' | 'more';
export type Motion = 'system' | 'full' | 'reduce';

/** Text sizes offered in Settings — the web analogue of Dynamic Type (xSmall … AX1). */
export const TEXT_SIZES = [0.88, 0.94, 1, 1.06, 1.12, 1.2, 1.3];

interface Stored {
  theme: Theme;
  contrast: Contrast;
  motion: Motion;
  textSize: number;
}

const KEY = 'iccp.appearance';

/**
 * Appearance preferences kept per device (localStorage — a convenience, never required).
 * Applied as data attributes on <html> so styles.scss can switch tokens without re-rendering.
 */
@Injectable({ providedIn: 'root' })
export class Settings {
  private doc = inject(DOCUMENT);
  private stored = read();

  readonly theme = signal<Theme>(this.stored.theme);
  readonly contrast = signal<Contrast>(this.stored.contrast);
  readonly motion = signal<Motion>(this.stored.motion);
  readonly textSize = signal<number>(this.stored.textSize);
  /** Resolved scheme, used by components that draw (e.g. charts). */
  readonly scheme = signal<'light' | 'dark'>('light');

  constructor() {
    const media = this.doc.defaultView?.matchMedia?.('(prefers-color-scheme: dark)');
    const resolve = () => this.scheme.set(this.theme() === 'system' ? (media?.matches ? 'dark' : 'light') : (this.theme() as 'light' | 'dark'));
    media?.addEventListener?.('change', resolve);
    effect(() => {
      const root = this.doc.documentElement;
      const theme = this.theme();
      resolve();
      setAttr(root, 'data-theme', theme === 'system' ? null : theme);
      setAttr(root, 'data-scheme', this.scheme());
      setAttr(root, 'data-contrast', this.contrast() === 'system' ? null : this.contrast());
      setAttr(root, 'data-motion', this.motion() === 'system' ? null : this.motion());
      root.style.setProperty('--type-scale', String(this.textSize()));
      write({ theme, contrast: this.contrast(), motion: this.motion(), textSize: this.textSize() });
    });
  }

  reducedMotion(): boolean {
    if (this.motion() !== 'system') return this.motion() === 'reduce';
    return !!this.doc.defaultView?.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
  }
}

function setAttr(el: HTMLElement, name: string, value: string | null) {
  if (value === null) el.removeAttribute(name);
  else el.setAttribute(name, value);
}

function read(): Stored {
  const fallback: Stored = { theme: 'system', contrast: 'system', motion: 'system', textSize: 1 };
  try {
    return { ...fallback, ...JSON.parse(localStorage.getItem(KEY) ?? '{}') };
  } catch {
    return fallback;
  }
}

function write(s: Stored) {
  try {
    localStorage.setItem(KEY, JSON.stringify(s));
  } catch {
    /* storage unavailable: settings last for this visit only */
  }
}
