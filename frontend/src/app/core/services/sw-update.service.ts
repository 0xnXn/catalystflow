import { Injectable, inject, ApplicationRef } from '@angular/core';
import { SwUpdate, VersionReadyEvent } from '@angular/service-worker';
import { Router, NavigationEnd } from '@angular/router';
import { concat, interval } from 'rxjs';
import { filter, first, switchMap, take } from 'rxjs/operators';

@Injectable({ providedIn: 'root' })
export class SwUpdateService {
  private readonly swUpdate = inject(SwUpdate);
  private readonly appRef = inject(ApplicationRef);
  private readonly router = inject(Router);

  init(): void {
    if (!this.swUpdate.isEnabled) {
      return;
    }

    this.swUpdate.versionUpdates
      .pipe(
        filter((e): e is VersionReadyEvent => e.type === 'VERSION_READY'),
        // Don't blow away an in-progress edit. Wait for the next navigation — a
        // safe point where no form is mid-entry (autosave is debounced 2.5s and
        // the golden edit buffer is only persisted on Save) — then reload to
        // pick up the new version.
        switchMap(() => this.router.events.pipe(
          filter((e): e is NavigationEnd => e instanceof NavigationEnd),
          take(1),
        )),
      )
      .subscribe(() => this.reload());

    // A long-open tab won't notice a deploy on its own; check once the app is
    // stable, then hourly, so VERSION_READY fires without a manual refresh.
    const appStable$ = this.appRef.isStable.pipe(first(stable => stable === true));
    concat(appStable$, interval(60 * 60 * 1000)).subscribe(() => {
      this.swUpdate.checkForUpdate().catch(() => {});
    });
  }

  // Seam so tests can assert the reload is deferred to navigation rather than
  // fired immediately on VERSION_READY.
  private reload(): void {
    document.location.reload();
  }
}
