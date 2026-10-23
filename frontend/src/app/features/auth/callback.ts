import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Auth } from '../../core/auth.service';
import { TPipe } from '../../core/i18n.service';
import { errorText } from '../../core/toast.service';

@Component({
  selector: 'app-auth-callback',
  imports: [RouterLink, TPipe],
  template: `
    <div class="page page-narrow" style="padding-top: 20vh; text-align: center">
      @if (error()) {
        <p class="t-headline">{{ error() }}</p>
        <a class="btn btn-tinted" routerLink="/sign-in" style="margin-top: 16px">{{ 'signin.again' | t }}</a>
      } @else {
        <p class="secondary">{{ 'signin.completing' | t }}</p>
      }
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuthCallback {
  protected error = signal<string | null>(null);
  constructor() {
    const auth = inject(Auth);
    const router = inject(Router);
    auth
      .completeOidc(new URLSearchParams(location.search))
      .then((to) => router.navigateByUrl(to))
      .catch((e) => this.error.set(errorText(e)));
  }
}
