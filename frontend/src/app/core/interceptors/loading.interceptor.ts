import { inject } from '@angular/core';
import { HttpInterceptorFn } from '@angular/common/http';
import { finalize } from 'rxjs';
import { LoadingService } from '../services/loading.service';

/**
 * Increments the global in-flight counter when a request starts and
 * decrements it on completion (success / error / cancellation), so the
 * app-root overlay can show a spinner during any API wait.
 *
 * <p>finalize() runs on terminal events only — it covers HttpResponse,
 * HttpErrorResponse, and unsubscription. Critical: a forgotten error
 * branch would leave the counter stuck above zero and the spinner pinned.
 *
 * <p>Skips internal Angular asset URLs (the dev-mode source map fetches
 * etc.) by gating on http(s):// — keeps the overlay focused on real APIs.
 */
export const loadingInterceptor: HttpInterceptorFn = (req, next) => {
  const loading = inject(LoadingService);

  const isApiCall = /^https?:/i.test(req.url);
  if (!isApiCall) {
    return next(req);
  }

  loading.start();
  return next(req).pipe(finalize(() => loading.stop()));
};
