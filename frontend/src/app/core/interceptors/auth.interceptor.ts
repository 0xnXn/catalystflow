import { inject } from '@angular/core';
import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from '../auth/auth.service';

/**
 * AuthInterceptor — runs on every outgoing HTTP request.
 *
 * Two responsibilities:
 *
 * 1. Attach the Cognito ID token as a Bearer token when the user is logged in.
 *    The backend Lambda reads this from the Authorization header.
 *    API Gateway's Cognito Authorizer validates the signature — the Lambda only
 *    decodes the payload (ClaimsContext.java).
 *
 * 2. Handle 401 Unauthorized responses — BUT only when we sent a token.
 *    If we had no token and the API returns 401, that is expected (unauthenticated
 *    request). We do NOT redirect in that case — it prevents a redirect loop when
 *    the root AppComponent makes an API health check before the user is logged in.
 *    If we DID send a token and still got 401, the token has expired — force re-login.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const router      = inject(Router);

  const token = authService.getIdToken();

  // ── No token: pass the request through without touching it ─────────────────
  // Unauthenticated requests may legitimately fail with 401 (API Gateway
  // rejecting requests that have no Authorization header). We do not redirect
  // in that case — the user is already on /login or heading there via authGuard.
  if (!token) {
    return next(req);
  }

  // ── Authenticated: attach Bearer token and watch for expiry ────────────────
  const authReq = req.clone({
    setHeaders: { Authorization: `Bearer ${token}` },
  });

  return next(authReq).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status === 401) {
        // We sent a valid token but API Gateway still rejected it — the token
        // has expired and the refresh token could not renew it. Force re-login.
        authService.logout();
        router.navigate(['/login']);
      }
      return throwError(() => error);
    }),
  );
};
