import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { PasswordChecks, evaluatePasswordStrength } from '../../core/utils/password-strength';

@Component({
  selector: 'app-password-management',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule],
  templateUrl: './password-management.component.html',
  styleUrls: ['./password-management.component.css']
})
export class PasswordManagementComponent {

  currentPassword = '';
  newPassword     = '';
  confirmPassword = '';

  showCurrent = false;
  showNew     = false;
  showConfirm = false;

  submitted    = false;
  isLoading    = false;
  successMessage   = false;
  errorMessage     = '';
  newPasswordError     = '';
  confirmPasswordError = '';

  constructor(private authService: AuthService) {}

  checks: PasswordChecks = {
    minLength: false, hasUpper: false, hasLower: false, hasNumber: false, hasSpecial: false,
  };

  strengthLabel = '';
  strengthClass = '';
  strengthWidth = '0%';

  onNewPasswordChange(): void {
    const { checks, label, cssClass, widthPercent } = evaluatePasswordStrength(this.newPassword);
    this.checks        = checks;
    this.strengthLabel = label;
    this.strengthClass = cssClass;
    this.strengthWidth = widthPercent;

    if (this.confirmPassword) this.onConfirmPasswordChange();
    if (this.submitted)       this.validateNewPassword();
  }

  onConfirmPasswordChange(): void {
    if (this.submitted) {
      this.validateConfirmPassword();
    }
  }

  validateNewPassword(): void {
    if (!this.newPassword) {
      this.newPasswordError = 'New password is required.';
    } else if (!this.checks.minLength) {
      this.newPasswordError = 'Password must be at least 8 characters.';
    } else if (!this.checks.hasUpper || !this.checks.hasLower) {
      this.newPasswordError = 'Password must include uppercase and lowercase letters.';
    } else if (!this.checks.hasNumber) {
      this.newPasswordError = 'Password must include at least one number.';
    } else if (!this.checks.hasSpecial) {
      this.newPasswordError = 'Password must include at least one special character.';
    } else {
      this.newPasswordError = '';
    }
  }

  validateConfirmPassword(): void {
    if (!this.confirmPassword) {
      this.confirmPasswordError = 'Please confirm your new password.';
    } else if (this.confirmPassword !== this.newPassword) {
      this.confirmPasswordError = 'Passwords do not match.';
    } else {
      this.confirmPasswordError = '';
    }
  }

  onSubmit(): void {
    this.submitted     = true;
    this.errorMessage  = '';
    this.validateNewPassword();
    this.validateConfirmPassword();

    if (!this.currentPassword || this.newPasswordError || this.confirmPasswordError) {
      return;
    }

    this.isLoading = true;

    this.authService.changePassword(this.currentPassword, this.newPassword).subscribe({
      next: () => {
        this.isLoading      = false;
        this.successMessage = true;
        setTimeout(() => { this.successMessage = false; }, 4000);
        this.resetForm();
      },
      error: (err: Error) => {
        this.isLoading    = false;
        this.errorMessage = this.friendlyError(err.name, err.message);
      },
    });
  }

  private resetForm(): void {
    this.currentPassword = '';
    this.newPassword     = '';
    this.confirmPassword = '';
    this.submitted       = false;
    this.checks = { minLength: false, hasUpper: false, hasLower: false, hasNumber: false, hasSpecial: false } satisfies PasswordChecks;
    this.strengthLabel = '';
    this.strengthClass = '';
    this.strengthWidth = '0%';
  }

  private friendlyError(name: string, message: string): string {
    const key = name + ' ' + message;
    if (key.includes('NotAuthorizedException'))   return 'Current password is incorrect.';
    if (key.includes('InvalidPasswordException')) return 'New password does not meet the security requirements.';
    if (key.includes('LimitExceededException'))   return 'Too many attempts. Please wait a moment and try again.';
    return 'Failed to update password. Please try again.';
  }

}