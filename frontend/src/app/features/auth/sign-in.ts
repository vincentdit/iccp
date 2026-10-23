import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { HttpClient } from '@angular/common/http';
import { Auth } from '../../core/auth.service';
import { I18n, TPipe } from '../../core/i18n.service';
import { DevUser, PublicConfig } from '../../core/models';
import { Session } from '../../core/session.service';
import { errorText } from '../../core/toast.service';
import { Avatar } from '../../ui/avatar';
import { Icon } from '../../ui/icon';

@Component({
  selector: 'app-sign-in',
  imports: [Avatar, Icon, TPipe],
  templateUrl: './sign-in.html',
  styleUrl: './sign-in.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SignIn {
  private auth = inject(Auth);
  private session = inject(Session);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private http = inject(HttpClient);
  protected i18n = inject(I18n);

  protected config = signal<PublicConfig | null>(null);
  protected people = signal<DevUser[]>([]);
  protected busy = signal<string | null>(null);
  protected error = signal<string | null>(null);

  constructor() {
    this.init();
  }

  private async init() {
    try {
      const c = await this.auth.loadConfig();
      this.config.set(c);
      if (c.authMode === 'dev') this.people.set(await firstValueFrom(this.http.get<DevUser[]>('/api/v1/dev/users')));
    } catch (e) {
      this.error.set(errorText(e));
    }
  }

  protected async devSignIn(p: DevUser) {
    this.busy.set(p.employeeNo);
    this.error.set(null);
    try {
      await this.auth.devSignIn(p.employeeNo);
      this.session.clear();
      await this.session.load();
      await this.router.navigateByUrl(this.route.snapshot.queryParamMap.get('returnTo') || '/');
    } catch (e) {
      this.error.set(errorText(e));
    } finally {
      this.busy.set(null);
    }
  }

  protected oidc() {
    this.busy.set('oidc');
    this.auth.oidcSignIn(this.route.snapshot.queryParamMap.get('returnTo') || '/');
  }

  protected roleSummary(p: DevUser): string {
    const shown = p.roles.filter((r) => r !== 'STAFF' && r !== 'LINE_MANAGER' && r !== 'DIRECTOR');
    return shown.map((r) => this.i18n.t('role.' + r)).join(', ');
  }
}
