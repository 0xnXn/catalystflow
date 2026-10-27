import { ApplicationConfig, provideZoneChangeDetection, isDevMode, importProvidersFrom } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { FormlyModule } from '@ngx-formly/core';

import { routes } from './app.routes';
import { authInterceptor } from './core/interceptors/auth.interceptor';
import { loadingInterceptor } from './core/interceptors/loading.interceptor';
import { provideServiceWorker } from '@angular/service-worker';
import { RatingFieldComponent } from './features/review-cycles/template-builder/formly-fields/rating-field.component';
import { FormlyTextareaFieldComponent } from './features/review-cycles/template-builder/formly-fields/formly-textarea-field.component';
import { FormlyMulticheckboxFieldComponent } from './features/review-cycles/template-builder/formly-fields/formly-multicheckbox-field.component';
import { FormlyRadioFieldComponent } from './features/review-cycles/template-builder/formly-fields/formly-radio-field.component';
import { FormlySelectFieldComponent } from './features/review-cycles/template-builder/formly-fields/formly-select-field.component';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    // loadingInterceptor first so its finalize() outermost wraps the auth
    // interceptor — request-counting must include any auth-driven retries.
    provideHttpClient(withInterceptors([loadingInterceptor, authInterceptor])),
    importProvidersFrom(
      FormlyModule.forRoot({
        types: [
          { name: 'textarea',      component: FormlyTextareaFieldComponent },
          { name: 'rating',        component: RatingFieldComponent },
          { name: 'multicheckbox', component: FormlyMulticheckboxFieldComponent },
          { name: 'radio',         component: FormlyRadioFieldComponent },
          { name: 'select',        component: FormlySelectFieldComponent },
        ],
      })
    ),
    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode(),
      registrationStrategy: 'registerWhenStable:30000'
    }),
  ],
};
