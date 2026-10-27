import { Component, EventEmitter, Output, inject, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

@Component({
  selector: 'app-sidebar',
  standalone: true,
  imports: [CommonModule, RouterModule],
  templateUrl: './sidebar.component.html',
  styleUrls: ['./sidebar.component.css'],
})
export class SidebarComponent {
  private router      = inject(Router);
  private authService = inject(AuthService);

  @Output() linkClick        = new EventEmitter<void>();
  @Output() collapsedChange  = new EventEmitter<boolean>();

  isCollapsed  = false;
  settingsOpen = false;

  toggleCollapse(): void {
    this.isCollapsed = !this.isCollapsed;
    if (this.isCollapsed) this.settingsOpen = false;
    this.collapsedChange.emit(this.isCollapsed);
  }

  /** Email of the signed-in user, read directly from the Cognito ID token. */
  get userEmail(): string {
    return this.authService.getUserEmail() ?? '';
  }

  /** Display name: full name from backend, falls back to email prefix. */
  get displayName(): string {
    return this.authService.getUserDisplayName() ?? this.userEmail.split('@')[0] ?? '';
  }

  /** Role label shown under the name. */
  get userRole(): string {
    const role = this.authService.getUserEmployeeType();
    if (role === 'ADMIN')   return 'Administrator';
    if (role === 'MANAGER') return 'Manager';
    return 'Employee';
  }

  /** True when the signed-in user belongs to the ADMIN Cognito group. */
  get isAdmin(): boolean {
    return this.authService.isAdmin();
  }

  /** True when the signed-in user is a Manager or Admin. */
  get isManagerOrAdmin(): boolean {
    return this.authService.isManagerOrAdmin();
  }

  /** Avatar URL — generated from the display name so initials match the user. */
  get avatarUrl(): string {
    const name = encodeURIComponent(this.displayName);
    return `https://ui-avatars.com/api/?name=${name}&background=2E2E2E&color=fff`;
  }

  onLinkClick(): void {
    this.linkClick.emit();
  }

  toggleSettings(): void {
    this.settingsOpen = !this.settingsOpen;
  }

  logout(): void {
    // Calls Cognito signOut() which invalidates the refresh token server-side
    // and wipes all Cognito keys from sessionStorage.
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}
