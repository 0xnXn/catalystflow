import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { LoginComponent } from '../../features/auth/login.component';
import { AuthService } from './auth.service';
import { authInterceptor } from '../interceptors/auth.interceptor';
import { environment } from '../../../environment/environment';

describe('Sign-in HTTP integration', () => {
  let auth: jasmine.SpyObj<AuthService>;
  let http: HttpTestingController;
  let router: Router;
  beforeEach(async () => {
    auth = jasmine.createSpyObj<AuthService>('AuthService', ['login', 'getTokenPayload', 'getIdToken', 'cacheUserRole', 'cacheUserName', 'logout']);
    auth.login.and.returnValue(of('success'));
    auth.getTokenPayload.and.returnValue({sub: 'demo-sub', email: 'demo@example.com', 'cognito:groups': ['MANAGER']});
    auth.getIdToken.and.returnValue('test-id-token');
    await TestBed.configureTestingModule({imports: [LoginComponent], providers: [
      provideRouter([]), provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting(),
      {provide: AuthService, useValue: auth},
      {provide: ActivatedRoute, useValue: {snapshot: {queryParamMap: {get: () => null}}}},
    ]}).compileComponents();
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    spyOn(router, 'navigateByUrl').and.resolveTo(true);
  });
  afterEach(() => http.verify());
  function signIn() {
    const component = TestBed.createComponent(LoginComponent).componentInstance;
    component.email = 'demo@example.com'; component.password = 'synthetic-password';
    component.onSubmit(); return component;
  }
  it('passes the authenticated token through the interceptor and caches the server role', () => {
    signIn();
    const request = http.expectOne(environment.apiUrl + '/users/demo-sub');
    expect(request.request.headers.get('Authorization')).toBe('Bearer test-id-token');
    request.flush({status: 'Active', role: 'MANAGER', firstName: 'Demo', lastName: 'Manager'});
    expect(auth.cacheUserRole).toHaveBeenCalledWith('MANAGER');
    expect(auth.cacheUserName).toHaveBeenCalledWith('Demo', 'Manager');
    expect(router.navigateByUrl).toHaveBeenCalledWith('/dashboard');
  });
  it('ends the session and prevents navigation when the server profile is archived', () => {
    const component = signIn();
    http.expectOne(environment.apiUrl + '/users/demo-sub').flush({status: 'Archived', role: 'MANAGER'});
    expect(auth.logout).toHaveBeenCalled();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
    expect(component.errorMessage).toBeTruthy();
  });
});
