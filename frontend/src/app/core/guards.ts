import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Auth } from './auth.service';
import { Session } from './session.service';
import { Me } from './models';

/** Requires a signed-in person; loads their profile once. */
export const signedInGuard: CanActivateFn = async (_route, state) => {
  const auth = inject(Auth);
  const session = inject(Session);
  const router = inject(Router);
  if (!auth.signedIn()) return router.createUrlTree(['/sign-in'], { queryParams: { returnTo: state.url } });
  if (!session.me()) {
    try {
      await session.load();
    } catch {
      return router.createUrlTree(['/sign-in'], { queryParams: { returnTo: state.url } });
    }
  }
  return true;
};

/** Requires one of the "can" capabilities from /me; others see Home instead of a broken page. */
export function canGuard(...anyOf: (keyof Me['can'])[]): CanActivateFn {
  return () => {
    const session = inject(Session);
    const router = inject(Router);
    const can = session.can();
    return can && anyOf.some((k) => can[k]) ? true : router.createUrlTree(['/']);
  };
}
