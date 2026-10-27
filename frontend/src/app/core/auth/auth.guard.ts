import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs';
import { AuthService } from './auth.service';

/**
 * AuthGuard — protects every route inside MainLayout (dashboard, employees, settings).
 *
 * How it works:
 *  1. Calls authService.isAuthenticated() — which calls Cognito's getSession().
 *     This also silently refreshes the tokens if the access/id tokens have expired
 *     but the refresh token (30-day lifetime) is still valid.
 *  2. If authenticated → allow navigation.
 *  3. If not          → redirect to /login, preserving the attempted URL as
 *     a ?returnUrl= query parameter so the user lands back after login.
 *
 * Usage in routes:
 *   { path: 'dashboard', component: DashboardComponent, canActivate: [authGuard] }
 */
export const authGuard: CanActivateFn = (_route, state) => {
  const authService = inject(AuthService);
  const router      = inject(Router);

  return authService.isAuthenticated().pipe(
    map(isAuthenticated => {
      if (isAuthenticated) return true;
      // Preserve the attempted URL so we can redirect back after login.
      return router.createUrlTree(['/login'], {
        queryParams: { returnUrl: state.url },
      });
    }),
  );
};

/**
 * AdminGuard — restricts a route to users in the ADMIN Cognito group.
 * Non-admin authenticated users are redirected to /dashboard.
 */
export const adminGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const router      = inject(Router);

  if (authService.isAdmin()) return true;
  return router.createUrlTree(['/dashboard']);
};

/**
 * ManagerGuard — restricts a route to Manager or Admin users.
 * Regular employees are redirected to /dashboard.
 */
export const managerGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const router      = inject(Router);

  if (authService.isManagerOrAdmin()) return true;
  return router.createUrlTree(['/dashboard']);
};

/**
 * ChallengeGuard — protects the /auth/new-password route.
 *
 * That route is only valid when a NEW_PASSWORD_REQUIRED challenge is active
 * (i.e., the user just logged in for the first time with a temp password).
 * Direct navigation to /auth/new-password without logging in first is rejected.
 */
export const challengeGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const router      = inject(Router);

  if (authService.hasPendingChallenge()) return true;
  return router.createUrlTree(['/login']);
};
