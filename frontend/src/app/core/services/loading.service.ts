import { Injectable, signal, computed } from '@angular/core';

/**
 * Tracks in-flight HTTP requests application-wide.
 *
 * <p>Driven by {@link loadingInterceptor}: every outgoing request increments
 * the counter; every response/error/cancel decrements it. The {@link isLoading}
 * signal flips true the moment the first request goes out and back to false
 * once every concurrent request resolves.
 *
 * <p>Why a counter rather than a boolean: the dashboard fires several requests
 * in parallel (cycles + reviews + employees) and a boolean would oscillate
 * mid-load. A counter only flips off when the LAST one finishes.
 *
 * <p>Cognito SRP login goes through the Cognito SDK (not Angular HttpClient)
 * so it bypasses this — the login component has its own button-level spinner.
 */
@Injectable({ providedIn: 'root' })
export class LoadingService {
  private readonly inflight = signal(0);

  /** True while at least one HTTP request is in flight. */
  readonly isLoading = computed(() => this.inflight() > 0);

  start(): void {
    this.inflight.update(n => n + 1);
  }

  stop(): void {
    this.inflight.update(n => Math.max(0, n - 1));
  }
}
