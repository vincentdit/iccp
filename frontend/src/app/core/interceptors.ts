import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { Auth } from './auth.service';
import { I18n } from './i18n.service';

/** Adds the bearer token and the interface language to API calls; sends people to sign in on 401. */
export const apiInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/api/')) return next(req);
  const auth = inject(Auth);
  const i18n = inject(I18n);
  const router = inject(Router);
  const token = auth.accessToken();
  const headers: Record<string, string> = { 'Accept-Language': i18n.lang() };
  if (token && !req.url.startsWith('/api/v1/public') && !req.url.startsWith('/api/v1/dev')) {
    headers['Authorization'] = `Bearer ${token}`;
  }
  return next(req.clone({ setHeaders: headers })).pipe(
    catchError((e: HttpErrorResponse) => {
      if (e.status === 401 && !req.url.startsWith('/api/v1/dev')) {
        router.navigate(['/sign-in'], { queryParams: { returnTo: router.url } });
      }
      return throwError(() => e);
    }),
  );
};
