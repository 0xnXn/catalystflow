import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { PasswordChecks, evaluatePasswordStrength } from '../../core/utils/password-strength';

type Step = 'request' | 'confirm' | 'done';

@Component({
  selector: 'app-forgot-password',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './forgot-password.component.html',
  styleUrls: ['./forgot-password.component.css'],
})
export class ForgotPasswordComponent {

  step: Step = 'request';

  // Step 1
  email        = '';
  emailError   = '';
  emailSubmitted = false;

  // Step 2
  code            = '';
  newPassword     = '';
  confirmPassword = '';
  codeError            = '';
  newPasswordError     = '';
  confirmPasswordError = '';
  confirmSubmitted     = false;

  showNew     = false;
  showConfirm = false;
  showRequirements = false;

  isLoading    = false;
  errorMessage = '';

  checks: PasswordChecks = {
    minLength: false, hasUpper: false, hasLower: false, hasNumber: false, hasSpecial: false,
  };
  strengthLabel = '';
  strengthClass = '';
  strengthWidth = '0%';

  constructor(
    private authService: AuthService,
    private router: Router,
  ) {}

  // ── Step 1 ────────────────────────────────────────────────────────────────

  onEmailChange(): void {
    this.errorMessage = '';
    if (this.emailSubmitted) this.validateEmail();
  }

  validateEmail(): void {
    const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    if (!this.email)              { this.emailError = 'Email address is required.'; return; }
    if (!EMAIL_RE.test(this.email)) { this.emailError = 'Please enter a valid email address.'; return; }
    this.emailError = '';
  }

  onRequestCode(): void {
    this.emailSubmitted = true;
    this.validateEmail();
    if (this.emailError) return;

    this.isLoading    = true;
    this.errorMessage = '';

    this.authService.forgotPassword(this.email).subscribe({
      next: () => {
        this.isLoading = false;
        this.step      = 'confirm';
      },
      error: (err: Error) => {
        this.isLoading    = false;
        this.errorMessage = this.friendlyRequestError(err.name, err.message);
      },
    });
  }

  // ── Step 2 ────────────────────────────────────────────────────────────────

  onNewPasswordChange(): void {
    const { checks, label, cssClass, widthPercent } = evaluatePasswordStrength(this.newPassword);
    this.checks        = checks;
    this.strengthLabel = label;
    this.strengthClass = cssClass;
    this.strengthWidth = widthPercent;

    if (this.confirmPassword)    this.validateConfirmPassword();
    if (this.confirmSubmitted)   this.validateNewPassword();
  }

  onConfirmPasswordChange(): void {
    if (this.confirmSubmitted) this.validateConfirmPassword();
  }

  validateCode(): void {
    if (!this.code) { this.codeError = 'Verification code is required.'; return; }
    this.codeError = '';
  }

  validateNewPassword(): void {
    if (!this.newPassword)      { this.newPasswordError = 'New password is required.'; return; }
    if (!this.checks.minLength) { this.newPasswordError = 'Password must be at least 8 characters.'; return; }
    if (!this.checks.hasUpper || !this.checks.hasLower) {
      this.newPasswordError = 'Password must include uppercase and lowercase letters.'; return;
    }
    if (!this.checks.hasNumber)  { this.newPasswordError = 'Password must include at least one number.'; return; }
    if (!this.checks.hasSpecial) { this.newPasswordError = 'Password must include at least one special character.'; return; }
    this.newPasswordError = '';
  }

  validateConfirmPassword(): void {
    if (!this.confirmPassword)                    { this.confirmPasswordError = 'Please confirm your new password.'; return; }
    if (this.confirmPassword !== this.newPassword) { this.confirmPasswordError = 'Passwords do not match.'; return; }
    this.confirmPasswordError = '';
  }

  onConfirmReset(): void {
    this.confirmSubmitted = true;
    this.validateCode();
    this.validateNewPassword();
    this.validateConfirmPassword();
    if (this.codeError || this.newPasswordError || this.confirmPasswordError) return;

    this.isLoading    = true;
    this.errorMessage = '';

    this.authService.isSameAsCurrentPassword(this.email, this.newPassword).subscribe({
      next: (isSame) => {
        if (isSame) {
          this.isLoading        = false;
          this.newPasswordError = 'New password cannot be the same as your current password.';
          return;
        }
        this.doConfirmReset();
      },
      error: () => {
        // Check failed (e.g. network) — proceed anyway and let Cognito decide
        this.doConfirmReset();
      },
    });
  }

  private doConfirmReset(): void {
    this.authService.confirmForgotPassword(this.email, this.code.trim(), this.newPassword).subscribe({
      next: () => {
        this.isLoading = false;
        this.step      = 'done';
      },
      error: (err: Error) => {
        this.isLoading    = false;
        this.errorMessage = this.friendlyConfirmError(err.name, err.message);
      },
    });
  }

  // ── Navigation ────────────────────────────────────────────────────────────

  goToLogin(): void {
    this.router.navigate(['/login']);
  }

  resendCode(): void {
    this.errorMessage = '';
    this.code         = '';
    this.step         = 'request';
    // Keep email prefilled so the user just clicks Send again.
    this.emailSubmitted = false;
  }

  // ── Error helpers ─────────────────────────────────────────────────────────

  private friendlyRequestError(name: string, message: string): string {
    const key = name + ' ' + message;
    if (key.includes('UserNotFoundException'))     return 'No account found with that email address.';
    if (key.includes('LimitExceededException'))    return 'Too many attempts. Please wait a moment and try again.';
    if (key.includes('InvalidParameterException')) return 'This account is not verified. Please contact your administrator.';
    if (key.includes('NetworkError') || key.includes('Failed to fetch')) {
      return 'Unable to connect. Please check your internet connection.';
    }
    return 'Something went wrong. Please try again.';
  }

  private friendlyConfirmError(name: string, message: string): string {
    const key = name + ' ' + message;
    if (key.includes('CodeMismatchException'))     return 'The code you entered is incorrect. Please try again.';
    if (key.includes('ExpiredCodeException'))      return 'This code has expired. Please request a new one.';
    if (key.includes('LimitExceededException'))    return 'Too many attempts. Please wait a moment and try again.';
    if (key.includes('InvalidPasswordException') || key.includes('Password does not conform')) {
      return 'Password does not meet the security requirements. Please check the requirements below.';
    }
    if (key.includes('NetworkError') || key.includes('Failed to fetch')) {
      return 'Unable to connect. Please check your internet connection.';
    }
    return 'Something went wrong. Please try again.';
  }
}
