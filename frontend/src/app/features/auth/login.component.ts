import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { Router, ActivatedRoute } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { environment } from '../../../environment/environment';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './login.component.html',
  styleUrls: ['./login.component.css'],
})
export class LoginComponent {
  readonly demoAccountsEnabled = environment.demoAccountsEnabled;
  readonly demoPassword = 'CatalystDemo@123';
  readonly demoAccounts = [
    { role: 'Admin', email: 'demo.admin@catalystflow.example.com', description: 'Explore workspace administration' },
    { role: 'Manager', email: 'demo.manager@catalystflow.example.com', description: 'Explore team reviews' },
    { role: 'Employee', email: 'demo.employee@catalystflow.example.com', description: 'Explore your review workspace' },
  ];

  useDemoAccount(email: string): void {
    if (this.isLoading) return;
    this.email = email;
    this.password = this.demoPassword;
    this.errorMessage = '';
  }

  email    = '';
  password = '';

  showPassword = false;
  isLoading    = false;
  errorMessage = '';

  constructor(
    private authService: AuthService,
    private router: Router,
    private route: ActivatedRoute,
    private http: HttpClient,
  ) {}

  onInputChange(): void {
    this.errorMessage = '';
  }

  onSubmit(): void {
    if (!this.email || !this.password) return;

    this.isLoading    = true;
    this.errorMessage = '';

    this.authService.login(this.email, this.password).subscribe({
      next: result => {
        if (result === 'newPasswordRequired') {
          this.router.navigate(['/password_management/new-password']);
          return;
        }

        // Fetch user profile to check archived status and cache role.
        const userId = this.authService.getTokenPayload()?.sub;
        if (!userId) {
          this.authService.logout();
          this.isLoading = false;
          this.errorMessage = 'Incorrect email or password.';
          return;
        }

        this.http.get<{ status: string; role: string; firstName: string; lastName: string }>(environment.apiUrl + '/users/' + userId).subscribe({
          next: user => {
            if (user.status === 'Archived') {
              this.authService.logout();
              this.isLoading = false;
              this.errorMessage = 'Incorrect email or password.';
            } else {
              this.authService.cacheUserRole(user.role);
              this.authService.cacheUserName(user.firstName, user.lastName);
              const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl') ?? '/dashboard';
              this.router.navigateByUrl(returnUrl);
            }
          },
          error: () => {
            this.authService.logout();
            this.isLoading = false;
            this.errorMessage = 'Incorrect email or password.';
          },
        });
      },
      error: (err: Error) => {
        this.isLoading    = false;
        this.errorMessage = this.friendlyError(err.name, err.message);
      },
    });
  }

  private friendlyError(name: string, message: string): string {
    const key = name + ' ' + message;
    if (key.includes('NotAuthorizedException'))        return 'Incorrect email or password.';
    if (key.includes('UserNotFoundException'))          return 'No account found with that email address.';
    if (key.includes('UserNotConfirmedException'))      return 'Your account is not confirmed. Please contact your administrator.';
    if (key.includes('PasswordResetRequiredException')) return 'A password reset is required. Please contact your administrator.';
    if (key.includes('TooManyRequestsException'))       return 'Too many attempts. Please wait a moment and try again.';
    if (key.includes('NetworkError') || key.includes('Failed to fetch')) {
      return 'Unable to connect. Please check your internet connection.';
    }
    return 'An unexpected error occurred. Please try again.';
  }
}
