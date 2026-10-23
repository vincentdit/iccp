import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from '../../core/api.service';
import { Auth } from '../../core/auth.service';
import { I18n, Lang, TPipe } from '../../core/i18n.service';
import { Session } from '../../core/session.service';
import { Contrast, Motion, Settings, TEXT_SIZES, Theme } from '../../core/settings.service';
import { Toasts } from '../../core/toast.service';
import { Icon } from '../../ui/icon';
import { PageHeader } from '../../ui/page-header';
import { Segmented } from '../../ui/segmented';

/** Appearance, accessibility and language (SCR-SET-01). Appearance is per device; language follows your profile. */
@Component({
  selector: 'app-settings',
  imports: [FormsModule, PageHeader, Segmented, Icon, TPipe],
  template: `
    <div class="page page-narrow">
      <app-page-header [title]="'nav.settings' | t" />

      <div class="group-label">{{ 'settings.appearance' | t }}</div>
      <div class="themes" role="radiogroup" [attr.aria-label]="'settings.appearance' | t">
        @for (t of themes; track t) {
          <button type="button" role="radio" class="theme" [attr.aria-checked]="settings.theme() === t" (click)="settings.theme.set(t)">
            <span class="preview" [class]="t"><span class="win"><span class="side"></span><span class="lines"><i></i><i></i><i></i></span></span></span>
            <span class="t-subhead">{{ ('settings.theme.' + t) | t }}</span>
            <span class="check" aria-hidden="true">@if (settings.theme() === t) {<app-icon name="checkCircle" />}</span>
          </button>
        }
      </div>

      <div class="section">
        <div class="group-label">{{ 'settings.textSize' | t }}</div>
        <div class="list">
          <div class="list-row size">
            <span class="small" aria-hidden="true">A</span>
            <input type="range" min="0" [max]="sizes.length - 1" step="1" [ngModel]="sizeIndex()" (ngModelChange)="settings.textSize.set(sizes[$event])" [attr.aria-label]="'settings.textSize' | t" [attr.aria-valuetext]="sizeLabel()" />
            <span class="large" aria-hidden="true">A</span>
          </div>
          <div class="list-row"><span class="grow secondary t-subhead sample">{{ 'settings.textSample' | t }}</span></div>
        </div>
      </div>

      <div class="section">
        <div class="group-label">{{ 'settings.accessibility' | t }}</div>
        <div class="list">
          <div class="list-row" style="--sep-inset: 58px">
            <span class="tile blue"><app-icon name="contrast" /></span>
            <span class="grow">{{ 'settings.contrast' | t }}</span>
            <select class="select inline" [ngModel]="settings.contrast()" (ngModelChange)="settings.contrast.set($event)" [attr.aria-label]="'settings.contrast' | t">
              @for (c of contrasts; track c) {<option [value]="c">{{ ('settings.opt.' + c) | t }}</option>}
            </select>
          </div>
          <div class="list-row" style="--sep-inset: 58px">
            <span class="tile indigo"><app-icon name="sliders" /></span>
            <span class="grow">{{ 'settings.motion' | t }}</span>
            <select class="select inline" [ngModel]="settings.motion()" (ngModelChange)="settings.motion.set($event)" [attr.aria-label]="'settings.motion' | t">
              @for (m of motions; track m) {<option [value]="m">{{ ('settings.motionOpt.' + m) | t }}</option>}
            </select>
          </div>
        </div>
        <p class="group-foot">{{ 'settings.systemHelp' | t }}</p>
      </div>

      <div class="section">
        <div class="group-label">{{ 'settings.language' | t }}</div>
        <app-segmented [segments]="langs" [value]="i18n.lang()" (valueChange)="setLang($event)" [label]="'settings.language' | t" />
        <p class="group-foot">{{ 'settings.languageHelp' | t }}</p>
      </div>

      <div class="section">
        <div class="list">
          <button class="list-row signout" type="button" (click)="auth.signOut()">{{ 'shell.signOut' | t }}</button>
        </div>
        <p class="group-foot center">ICCP {{ auth.config()?.version }}<br />TIRDO ICTT Department</p>
      </div>
    </div>
  `,
  styles: `
    .themes { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
    .theme { display: grid; justify-items: center; gap: 8px; padding: 14px 8px 10px; border: 0; border-radius: var(--r-lg); background: var(--bg-elevated); box-shadow: var(--shadow-1); cursor: pointer; }
    .theme[aria-checked='true'] { box-shadow: inset 0 0 0 2px var(--tint); }
    .preview { width: 92px; height: 62px; border-radius: 10px; padding: 8px; display: block; box-shadow: inset 0 0 0 0.5px var(--separator); }
    .preview.light { background: #f2f2f7; }
    .preview.dark { background: #1c1c1e; }
    .preview.system { background: linear-gradient(110deg, #f2f2f7 50%, #1c1c1e 50%); }
    .win { display: flex; gap: 4px; height: 100%; }
    .side { width: 22px; border-radius: 4px; background: rgba(120, 120, 128, 0.35); }
    .lines { flex: 1; display: grid; gap: 4px; align-content: start; }
    .lines i { display: block; height: 6px; border-radius: 3px; background: var(--tint); opacity: 0.8; }
    .lines i + i { background: rgba(120, 120, 128, 0.4); width: 80%; }
    .check { height: 22px; color: var(--tint); --icon-size: 22px; }
    .size { gap: 14px; }
    .size input { flex: 1; accent-color: var(--tint); }
    .small { font-size: 13px; }
    .large { font-size: 22px; }
    .sample { line-height: 1.45; }
    .inline { width: auto; min-height: 34px; padding: 6px 30px 6px 10px; box-shadow: none; background-color: var(--fill-quiet); font-size: var(--fs-subhead); }
    .signout { justify-content: center; color: var(--red); font-weight: 500; }
    .center { text-align: center; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SettingsPage {
  protected settings = inject(Settings);
  protected i18n = inject(I18n);
  protected auth = inject(Auth);
  private api = inject(Api);
  private session = inject(Session);
  private toasts = inject(Toasts);

  protected themes: Theme[] = ['system', 'light', 'dark'];
  protected contrasts: Contrast[] = ['system', 'normal', 'more'];
  protected motions: Motion[] = ['system', 'full', 'reduce'];
  protected sizes = TEXT_SIZES;
  protected langs = [
    { value: 'en', label: 'English' },
    { value: 'sw', label: 'Kiswahili' },
  ];
  constructor() {
    this.auth.loadConfig().catch(() => {});
  }

  protected sizeIndex = computed(() => Math.max(0, this.sizes.indexOf(this.settings.textSize())));
  protected sizeLabel = computed(() => `${Math.round(this.settings.textSize() * 100)}%`);

  protected setLang(lang: string) {
    this.i18n.set(lang as Lang);
    this.api.put('/me/language', { language: lang }).subscribe({
      next: () => this.session.me.update((m) => (m ? { ...m, language: lang as Lang } : m)),
      error: (e) => this.toasts.error(e),
    });
  }
}
