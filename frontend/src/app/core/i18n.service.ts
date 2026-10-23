import { Injectable, Pipe, PipeTransform, inject, signal } from '@angular/core';
import { DICTIONARY } from './i18n.dictionary';

export type Lang = 'en' | 'sw';
const KEY = 'iccp.lang';

/** English/Kiswahili interface text (FR-COM-08; NFR-UX-03). Missing Kiswahili falls back to English. */
@Injectable({ providedIn: 'root' })
export class I18n {
  readonly lang = signal<Lang>(readLang());
  private chosenHere = false;

  t(key: string, params?: Record<string, string | number>): string {
    // Plural forms: "key#one" is used when params.n === 1 (English and Kiswahili both need only one/other here).
    const entry = (params?.['n'] === 1 && DICTIONARY[key + '#one']) || DICTIONARY[key];
    let text = entry ? (entry[this.lang()] ?? entry.en) : key;
    if (params) for (const [k, v] of Object.entries(params)) text = text.replaceAll(`{${k}}`, String(v));
    return text;
  }

  set(lang: Lang) {
    this.chosenHere = true;
    this.lang.set(lang);
    document.documentElement.lang = lang;
    try {
      localStorage.setItem(KEY, lang);
    } catch {
      /* ignore */
    }
  }

  /** The profile language wins unless the person switched language on this device during this visit. */
  setFromProfile(lang: string) {
    if (!this.chosenHere && (lang === 'en' || lang === 'sw')) {
      this.lang.set(lang);
      document.documentElement.lang = lang;
    }
  }

  locale(): string {
    return this.lang() === 'sw' ? 'sw-TZ' : 'en-GB';
  }

  money(amount: number | null | undefined, currency = 'TZS'): string {
    if (amount === null || amount === undefined) return '—';
    return new Intl.NumberFormat(this.locale(), {
      style: 'currency',
      currency,
      currencyDisplay: 'code',
      minimumFractionDigits: currency === 'TZS' ? 0 : 2,
      maximumFractionDigits: 2,
    }).format(amount);
  }

  number(n: number | null | undefined): string {
    return n === null || n === undefined ? '—' : new Intl.NumberFormat(this.locale()).format(n);
  }

  date(iso: string | null | undefined, style: 'short' | 'medium' | 'long' = 'medium'): string {
    if (!iso) return '—';
    const d = new Date(iso.length === 10 ? iso + 'T00:00:00' : iso);
    return new Intl.DateTimeFormat(this.locale(), {
      dateStyle: style,
      timeZone: 'Africa/Dar_es_Salaam',
    }).format(d);
  }

  dateTime(iso: string | null | undefined): string {
    if (!iso) return '—';
    return new Intl.DateTimeFormat(this.locale(), {
      dateStyle: 'medium',
      timeStyle: 'short',
      timeZone: 'Africa/Dar_es_Salaam',
    }).format(new Date(iso));
  }

  /** "5 min ago", "Yesterday", or a date — the way Mail and Messages show time. */
  relative(iso: string | null | undefined): string {
    if (!iso) return '';
    const d = new Date(iso);
    const s = (Date.now() - d.getTime()) / 1000;
    const rtf = new Intl.RelativeTimeFormat(this.locale(), { numeric: 'auto' });
    if (s < 60) return rtf.format(0, 'second');
    if (s < 3600) return rtf.format(-Math.floor(s / 60), 'minute');
    if (s < 86400) return rtf.format(-Math.floor(s / 3600), 'hour');
    if (s < 7 * 86400) return rtf.format(-Math.floor(s / 86400), 'day');
    return this.date(iso, 'medium');
  }

  /** Time until a deadline, e.g. "in 2 days" or "2 days ago". */
  until(iso: string | null | undefined): string {
    if (!iso) return '';
    const s = (new Date(iso).getTime() - Date.now()) / 1000;
    const rtf = new Intl.RelativeTimeFormat(this.locale(), { numeric: 'auto' });
    const abs = Math.abs(s);
    if (abs < 3600) return rtf.format(Math.round(s / 60), 'minute');
    if (abs < 86400) return rtf.format(Math.round(s / 3600), 'hour');
    return rtf.format(Math.round(s / 86400), 'day');
  }
}

/** {{ 'key' | t }} or {{ 'key' | t: { n: 3 } }} — impure so it follows language changes. */
@Pipe({ name: 't', pure: false })
export class TPipe implements PipeTransform {
  private i18n = inject(I18n);
  transform(key: string, params?: Record<string, string | number>): string {
    return this.i18n.t(key, params);
  }
}

function readLang(): Lang {
  try {
    const l = localStorage.getItem(KEY);
    if (l === 'en' || l === 'sw') return l;
  } catch {
    /* ignore */
  }
  return navigator.language?.toLowerCase().startsWith('sw') ? 'sw' : 'en';
}
