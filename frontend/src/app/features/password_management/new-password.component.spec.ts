import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { NewPasswordComponent } from './new-password.component';
import { AuthService } from '../../core/auth/auth.service';

describe('NewPasswordComponent', () => {
  let component: NewPasswordComponent;
  let fixture: ComponentFixture<NewPasswordComponent>;
  let authService: jasmine.SpyObj<AuthService>;
  let router: jasmine.SpyObj<Router>;

  beforeEach(async () => {
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['completeNewPassword']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);

    await TestBed.configureTestingModule({
      imports: [NewPasswordComponent],
      providers: [
        { provide: AuthService, useValue: authService },
        { provide: Router, useValue: router },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(NewPasswordComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should be created', () => {
    expect(component).toBeTruthy();
  });

  describe('password strength evaluation', () => {
    it('updates strength checks when newPassword changes', () => {
      component.newPassword = 'Str0ng!Pass';
      component.onNewPasswordChange();

      expect(component.checks.minLength).toBeTrue();
      expect(component.checks.hasUpper).toBeTrue();
      expect(component.checks.hasLower).toBeTrue();
      expect(component.checks.hasNumber).toBeTrue();
      expect(component.checks.hasSpecial).toBeTrue();
    });

    it('flags weak password as not satisfying all checks', () => {
      component.newPassword = 'short';
      component.onNewPasswordChange();

      expect(component.checks.minLength).toBeFalse();
      expect(component.checks.hasUpper).toBeFalse();
      expect(component.checks.hasNumber).toBeFalse();
      expect(component.checks.hasSpecial).toBeFalse();
    });

    it('re-validates confirm password when new password changes after submit', () => {
      component.newPassword = 'Str0ng!Pass';
      component.confirmPassword = 'Different!1';
      component.submitted = true;

      component.onNewPasswordChange();

      expect(component.confirmPasswordError).toBe('Passwords do not match.');
    });
  });

  describe('validation', () => {
    it('rejects empty new password', () => {
      component.newPassword = '';
      component.validateNewPassword();
      expect(component.newPasswordError).toBe('New password is required.');
    });

    it('rejects password shorter than 8 characters', () => {
      component.newPassword = 'Sh0rt!';
      component.onNewPasswordChange();
      component.validateNewPassword();
      expect(component.newPasswordError).toContain('at least 8 characters');
    });

    it('rejects password without uppercase or lowercase', () => {
      component.newPassword = 'alllower1!';
      component.onNewPasswordChange();
      component.validateNewPassword();
      expect(component.newPasswordError).toContain('uppercase and lowercase');
    });

    it('rejects password without a number', () => {
      component.newPassword = 'NoNumbers!';
      component.onNewPasswordChange();
      component.validateNewPassword();
      expect(component.newPasswordError).toContain('at least one number');
    });

    it('rejects password without a special character', () => {
      component.newPassword = 'NoSpecial1';
      component.onNewPasswordChange();
      component.validateNewPassword();
      expect(component.newPasswordError).toContain('special character');
    });

    it('accepts a strong password', () => {
      component.newPassword = 'Str0ng!Pass';
      component.onNewPasswordChange();
      component.validateNewPassword();
      expect(component.newPasswordError).toBe('');
    });

    it('rejects empty confirm password', () => {
      component.confirmPassword = '';
      component.validateConfirmPassword();
      expect(component.confirmPasswordError).toContain('confirm');
    });

    it('rejects confirm password that does not match', () => {
      component.newPassword = 'Str0ng!Pass';
      component.confirmPassword = 'Different!1';
      component.validateConfirmPassword();
      expect(component.confirmPasswordError).toBe('Passwords do not match.');
    });

    it('accepts matching confirm password', () => {
      component.newPassword = 'Str0ng!Pass';
      component.confirmPassword = 'Str0ng!Pass';
      component.validateConfirmPassword();
      expect(component.confirmPasswordError).toBe('');
    });
  });

  describe('onSubmit', () => {
    function setStrongPassword() {
      component.newPassword = 'Str0ng!Pass';
      component.confirmPassword = 'Str0ng!Pass';
      component.onNewPasswordChange();
    }

    it('does not call completeNewPassword when validation fails', () => {
      component.newPassword = 'weak';
      component.confirmPassword = 'weak';

      component.onSubmit();

      expect(authService.completeNewPassword).not.toHaveBeenCalled();
      expect(component.isLoading).toBeFalse();
    });

    it('navigates to /dashboard on successful completion', () => {
      setStrongPassword();
      authService.completeNewPassword.and.returnValue(of(undefined));

      component.onSubmit();

      expect(authService.completeNewPassword).toHaveBeenCalledOnceWith('Str0ng!Pass');
      expect(router.navigate).toHaveBeenCalledOnceWith(['/dashboard']);
    });

    it('surfaces friendly error when Cognito rejects the password policy', () => {
      setStrongPassword();
      authService.completeNewPassword.and.returnValue(
        throwError(() => new Error('Password does not conform to policy: too short'))
      );

      component.onSubmit();

      expect(component.isLoading).toBeFalse();
      expect(component.errorMessage).toContain('security requirements');
      expect(router.navigate).not.toHaveBeenCalled();
    });

    it('surfaces raw error message for unknown failures', () => {
      setStrongPassword();
      authService.completeNewPassword.and.returnValue(
        throwError(() => new Error('Unexpected backend failure'))
      );

      component.onSubmit();

      expect(component.errorMessage).toBe('Unexpected backend failure');
    });

    it('sets isLoading=true during the request', () => {
      setStrongPassword();
      authService.completeNewPassword.and.callFake(() => ({
        subscribe: (observer: any) => {
          expect(component.isLoading).toBeTrue();
          observer.next(undefined);
          return { unsubscribe: () => {} };
        },
      } as any));

      component.onSubmit();
    });
  });
});
