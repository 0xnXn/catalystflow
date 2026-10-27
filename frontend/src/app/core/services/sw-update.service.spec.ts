import { TestBed } from '@angular/core/testing';
import { SwUpdate } from '@angular/service-worker';
import { Router, NavigationEnd } from '@angular/router';
import { Subject } from 'rxjs';

import { SwUpdateService } from './sw-update.service';

describe('SwUpdateService', () => {
  let versionUpdates$: Subject<{ type: string }>;
  let routerEvents$: Subject<unknown>;
  let service: SwUpdateService;

  beforeEach(() => {
    versionUpdates$ = new Subject<{ type: string }>();
    routerEvents$ = new Subject<unknown>();

    TestBed.configureTestingModule({
      providers: [
        SwUpdateService,
        {
          provide: SwUpdate,
          useValue: {
            isEnabled: true,
            versionUpdates: versionUpdates$.asObservable(),
            checkForUpdate: () => Promise.resolve(false),
          },
        },
        { provide: Router, useValue: { events: routerEvents$.asObservable() } },
        // ApplicationRef is left as the real TestBed instance — mocking it breaks
        // Angular's change-detection scheduler. We only drive the reload path via
        // versionUpdates + router events here.
      ],
    });
    service = TestBed.inject(SwUpdateService);
  });

  it('defers the reload to the next navigation, not the VERSION_READY event (NEW-4)', () => {
    const reload = spyOn(service as unknown as { reload: () => void }, 'reload');
    service.init();

    versionUpdates$.next({ type: 'VERSION_READY' });
    // An immediate reload here would discard an in-progress, unsaved answer.
    expect(reload).not.toHaveBeenCalled();

    routerEvents$.next(new NavigationEnd(1, '/a', '/a'));
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('does not reload on navigation when no update is pending', () => {
    const reload = spyOn(service as unknown as { reload: () => void }, 'reload');
    service.init();

    routerEvents$.next(new NavigationEnd(1, '/a', '/a'));
    expect(reload).not.toHaveBeenCalled();
  });
});
