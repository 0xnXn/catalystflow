import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { PasswordChecks, evaluatePasswordStrength } from '../../core/utils/password-strength';

/**
 * NewPasswordComponent — first-login forced password change.
 *
 * Flow:
 *  1. User logs in for the first time with the temporary password the admin set.
 *  2. Cognito returns a NEW_PASSWORD_REQUIRED challenge.
 *  3. LoginComponent detects this and navigates here.
 *  4. User sets a permanent password that meets Cognito's policy.
 *  5. completeNewPasswordChallenge() is called → tokens are issued → /dashboard.
 *
 * This route is protected by ChallengeGuard — direct navigation without
 * going through the login flow first is rejected.
 */
@Component({
  selector: 'app-new-password',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './new-password.component.html',
  styleUrls: ['./new-password.component.css'],
})
export class NewPasswordComponent {
  newPassword     = '';
  confirmPassword = '';

  showNew     = false;
  showConfirm = false;

  isLoading  = false;
  errorMessage = '';

  checks: PasswordChecks = {
    minLength: false, hasUpper: false, hasLower: false, hasNumber: false, hasSpecial: false,
  };

  strengthLabel = '';
  strengthClass = '';
  strengthWidth = '0%';

  submitted = false;
  newPasswordError     = '';
  confirmPasswordError = '';

  constructor(
    private authService: AuthService,
    private router: Router,
  ) {}

  // ── Password strength ────────────────────────────────────────────────────

  onNewPasswordChange(): void {
    const { checks, label, cssClass, widthPercent } = evaluatePasswordStrength(this.newPassword);
    this.checks        = checks;
    this.strengthLabel = label;
    this.strengthClass = cssClass;
    this.strengthWidth = widthPercent;

    if (this.confirmPassword) this.validateConfirmPassword();
    if (this.submitted)       this.validateNewPassword();
  }

  onConfirmPasswordChange(): void {
    if (this.submitted) this.validateConfirmPassword();
  }

  // ── Validation ────────────────────────────────────────────────────────────

  validateNewPassword(): void {
    if (!this.newPassword)              { this.newPasswordError = 'New password is required.'; return; }
    if (!this.checks.minLength)         { this.newPasswordError = 'Password must be at least 8 characters.'; return; }
    if (!this.checks.hasUpper || !this.checks.hasLower) { this.newPasswordError = 'Password must include uppercase and lowercase letters.'; return; }
    if (!this.checks.hasNumber)         { this.newPasswordError = 'Password must include at least one number.'; return; }
    if (!this.checks.hasSpecial)        { this.newPasswordError = 'Password must include at least one special character.'; return; }
    this.newPasswordError = '';
  }

  validateConfirmPassword(): void {
    if (!this.confirmPassword)                    { this.confirmPasswordError = 'Please confirm your new password.'; return; }
    if (this.confirmPassword !== this.newPassword) { this.confirmPasswordError = 'Passwords do not match.'; return; }
    this.confirmPasswordError = '';
  }

  // ── Submit ────────────────────────────────────────────────────────────────

  onSubmit(): void {
    this.submitted = true;
    this.validateNewPassword();
    this.validateConfirmPassword();

    if (this.newPasswordError || this.confirmPasswordError) return;

    this.isLoading    = true;
    this.errorMessage = '';

    this.authService.completeNewPassword(this.newPassword).subscribe({
      next: () => {
        // Challenge complete — tokens are now stored; go to the app.
        this.router.navigate(['/dashboard']);
      },
      error: (err: Error) => {
        this.isLoading    = false;
        this.errorMessage = this.friendlyError(err.message);
      },
    });
  }

  // ── Error messages ────────────────────────────────────────────────────────

  private friendlyError(message: string): string {
    if (message.includes('Password does not conform to policy')) {
      return 'Password does not meet the security requirements. Please check the requirements below.';
    }
    return message;
  }
}
