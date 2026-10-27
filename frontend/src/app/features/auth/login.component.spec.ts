import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { LoginComponent } from './login.component';
import { AuthService } from '../../core/auth/auth.service';
import { environment } from '../../../environment/environment';

describe('LoginComponent', () => {
  let component: LoginComponent;
  let fixture: ComponentFixture<LoginComponent>;
  let authService: jasmine.SpyObj<AuthService>;
  let router: Router;
  let httpMock: HttpTestingController;
  let activatedRoute: { snapshot: { queryParamMap: { get: jasmine.Spy } } };

  beforeEach(async () => {
    authService = jasmine.createSpyObj<AuthService>('AuthService', [
      'login',
      'getTokenPayload',
      'cacheUserRole',
      'cacheUserName',
      'logout',
    ]);
    activatedRoute = {
      snapshot: {
        queryParamMap: { get: jasmine.createSpy('get').and.returnValue(null) },
      },
    };

    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        { provide: AuthService, useValue: authService },
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        // ActivatedRoute override MUST come after provideRouter — provideRouter
        // registers its own ActivatedRoute which would otherwise shadow the mock.
        { provide: ActivatedRoute, useValue: activatedRoute },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(LoginComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.stub();
    spyOn(router, 'navigateByUrl').and.stub();
    // Do not detectChanges — template uses RouterLink which needs full router infra;
    // tests exercise component class methods directly.
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(component).toBeTruthy();
  });

  it('fills demo credentials without signing in or retaining a previous error', () => {
    component.errorMessage = 'Previous failure';
    component.useDemoAccount(component.demoAccounts[1].email);
    expect(component.email).toBe('demo.manager@catalystflow.example.com');
    expect(component.password).toBe(component.demoPassword);
    expect(component.errorMessage).toBe('');
    expect(authService.login).not.toHaveBeenCalled();
  });

  describe('onSubmit guard', () => {
    it('does not call AuthService when email or password is empty', () => {
      component.email = '';
      component.password = 'something';
      component.onSubmit();
      expect(authService.login).not.toHaveBeenCalled();

      component.email = 'user@example.com';
      component.password = '';
      component.onSubmit();
      expect(authService.login).not.toHaveBeenCalled();
    });

    it('clears errorMessage and sets isLoading when starting login', () => {
      component.email = 'user@example.com';
      component.password = 'pw';
      component.errorMessage = 'old error';
      // Return a non-emitting observable to inspect mid-flight state.
      authService.login.and.returnValue({
        subscribe: () => ({ unsubscribe: () => {} }),
      } as any);

      component.onSubmit();

      expect(component.errorMessage).toBe('');
      expect(component.isLoading).toBeTrue();
    });
  });

  describe('newPasswordRequired challenge', () => {
    it('navigates to the new-password page and does not fetch the user', () => {
      component.email = 'newbie@example.com';
      component.password = 'TempPass1!';
      authService.login.and.returnValue(of('newPasswordRequired' as const));

      component.onSubmit();

      expect(router.navigate).toHaveBeenCalledOnceWith(['/password_management/new-password']);
      expect(authService.getTokenPayload).not.toHaveBeenCalled();
      httpMock.expectNone(`${environment.apiUrl}/users/anything`);
    });
  });

  describe('successful login', () => {
    function primeSuccessfulLogin(userId: string) {
      component.email = 'user@example.com';
      component.password = 'pw';
      authService.login.and.returnValue(of('success' as const));
      authService.getTokenPayload.and.returnValue({
        sub: userId,
        email: 'user@example.com',
        'cognito:groups': [],
      });
    }

    it('fetches the user profile, caches role + name, and navigates to /dashboard', () => {
      primeSuccessfulLogin('u-1');

      component.onSubmit();

      const req = httpMock.expectOne(`${environment.apiUrl}/users/u-1`);
      expect(req.request.method).toBe('GET');
      req.flush({ status: 'Active', role: 'MANAGER', firstName: 'Pat', lastName: 'Lee' });

      expect(authService.cacheUserRole).toHaveBeenCalledOnceWith('MANAGER');
      expect(authService.cacheUserName).toHaveBeenCalledOnceWith('Pat', 'Lee');
      expect(router.navigateByUrl).toHaveBeenCalledOnceWith('/dashboard');
    });

    it('honours returnUrl query param when present', () => {
      primeSuccessfulLogin('u-2');
      activatedRoute.snapshot.queryParamMap.get.and.callFake(
        (key: string) => (key === 'returnUrl' ? '/admin/users' : null),
      );

      component.onSubmit();

      const req = httpMock.expectOne(`${environment.apiUrl}/users/u-2`);
      req.flush({ status: 'Active', role: 'ADMIN', firstName: 'A', lastName: 'B' });

      expect(router.navigateByUrl).toHaveBeenCalledOnceWith('/admin/users');
    });

    it('blocks Archived users and forces logout with a generic error', () => {
      primeSuccessfulLogin('u-3');

      component.onSubmit();

      const req = httpMock.expectOne(`${environment.apiUrl}/users/u-3`);
      req.flush({ status: 'Archived', role: 'EMPLOYEE', firstName: 'X', lastName: 'Y' });

      expect(authService.logout).toHaveBeenCalled();
      expect(component.isLoading).toBeFalse();
      expect(component.errorMessage).toBe('Incorrect email or password.');
      expect(router.navigateByUrl).not.toHaveBeenCalled();
    });

    it('logs the user out when no userId is in the token payload', () => {
      component.email = 'user@example.com';
      component.password = 'pw';
      authService.login.and.returnValue(of('success' as const));
      authService.getTokenPayload.and.returnValue(null);

      component.onSubmit();

      expect(authService.logout).toHaveBeenCalled();
      expect(component.isLoading).toBeFalse();
      expect(component.errorMessage).toBe('Incorrect email or password.');
      httpMock.expectNone(`${environment.apiUrl}/users/anything`);
    });

    it('logs the user out when the profile fetch fails', () => {
      primeSuccessfulLogin('u-4');

      component.onSubmit();

      const req = httpMock.expectOne(`${environment.apiUrl}/users/u-4`);
      req.flush({ message: 'boom' }, { status: 500, statusText: 'Server Error' });

      expect(authService.logout).toHaveBeenCalled();
      expect(component.isLoading).toBeFalse();
      expect(component.errorMessage).toBe('Incorrect email or password.');
    });
  });

  describe('friendly error mapping (login failure)', () => {
    const cases: { name: string; message: string; expected: string }[] = [
      { name: 'NotAuthorizedException', message: 'bad creds', expected: 'Incorrect email or password.' },
      { name: 'UserNotFoundException', message: '', expected: 'No account found with that email address.' },
      { name: 'UserNotConfirmedException', message: '', expected: 'Your account is not confirmed. Please contact your administrator.' },
      { name: 'PasswordResetRequiredException', message: '', expected: 'A password reset is required. Please contact your administrator.' },
      { name: 'TooManyRequestsException', message: '', expected: 'Too many attempts. Please wait a moment and try again.' },
      { name: 'Error', message: 'NetworkError: failed', expected: 'Unable to connect. Please check your internet connection.' },
      { name: 'OddException', message: 'unknown', expected: 'An unexpected error occurred. Please try again.' },
    ];

    cases.forEach(({ name, message, expected }) => {
      it(`maps ${name} -> "${expected}"`, () => {
        component.email = 'a@b.com';
        component.password = 'pw';
        const err = new Error(message);
        err.name = name;
        authService.login.and.returnValue(throwError(() => err));

        component.onSubmit();

        expect(component.isLoading).toBeFalse();
        expect(component.errorMessage).toBe(expected);
      });
    });
  });

  describe('onInputChange', () => {
    it('clears any existing errorMessage', () => {
      component.errorMessage = 'something failed';
      component.onInputChange();
      expect(component.errorMessage).toBe('');
    });
  });
});
