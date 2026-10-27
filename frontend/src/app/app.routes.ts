import { Routes } from '@angular/router';
import { LoginComponent } from './features/auth/login.component';
import { ForgotPasswordComponent } from './features/auth/forgot-password.component';
import { NewPasswordComponent } from './features/password_management/new-password.component';
import { MainLayoutComponent } from './layout/main-layout/main-layout.component';
import { AllEmployeesComponent } from './features/all-employees/all-employees.component';
import { PasswordManagementComponent } from './features/password_management/password-management.component';
import { RoleManagementComponent } from './features/role_management/role-management.component';
import { ComingSoonComponent } from './features/coming-soon/coming-soon.component';
import { ReviewCyclesComponent } from './features/review-cycles/review-cycles.component';
import { MyReviewComponent } from './features/my-review/my-review.component';
import { ReviewFormComponent } from './features/my-review/review-form.component';
import { MyTeamComponent } from './features/my-team/my-team.component';
import { ManagerReviewFormComponent } from './features/my-team/manager-review-form.component';
import { authGuard, adminGuard, managerGuard, challengeGuard } from './core/auth/auth.guard';
import { CycleSummaryComponent } from './features/cycle-summary/cycle-summary.component';

export const routes: Routes = [
  // ── Public ─────────────────────────────────────────────────────────────────
  { path: 'login',           component: LoginComponent },
  { path: 'forgot-password', component: ForgotPasswordComponent },

  // ── First-login forced password change ─────────────────────────────────────
  {
    path: 'password_management/new-password',
    component: NewPasswordComponent,
    canActivate: [challengeGuard],
  },

  // ── Protected app ───────────────────────────────────────────────────────────
  // authGuard redirects to /login when there is no valid Cognito session.
  // The ** child catches any sidebar route that doesn't have a page built yet
  // — instead of falling through to the outer ** and redirecting to /login,
  // authenticated users stay in the layout and see a "Coming Soon" placeholder.
  {
    path: '',
    component: MainLayoutComponent,
    canActivate: [authGuard],
    children: [
      { path: '',                     redirectTo: 'dashboard', pathMatch: 'full' },
      // Everyone
      { path: 'dashboard',            loadComponent: () => import('./features/dashboard/dashboard.component').then(m => m.DashboardComponent) },
      { path: 'my-review',            component: MyReviewComponent },
      { path: 'my-review/fill/:type', component: ReviewFormComponent },
      { path: 'settings/preferences', component: PasswordManagementComponent },
      // Manager & Admin
      { path: 'my-team',              component: MyTeamComponent,             canActivate: [managerGuard] },
      { path: 'my-team/review',      component: ManagerReviewFormComponent,  canActivate: [managerGuard] },
      {
        path: 'my-team/:employeeId/cycle/:cycleId/peer360',
        loadComponent: () => import('./features/my-team/peer360-assignments/peer360-assignments.component').then(m => m.Peer360AssignmentsComponent),
        canActivate: [managerGuard],
      },
      // Admin only — neutral review routes (no "My Review" or "My Team" sidebar highlight)
      { path: 'review/view',          component: ReviewFormComponent,        canActivate: [adminGuard] },
      { path: 'review/view/manager',  component: ManagerReviewFormComponent, canActivate: [adminGuard] },
      { path: 'review/start/manager', component: ManagerReviewFormComponent, canActivate: [adminGuard] },
      { path: 'ai-summary',           component: CycleSummaryComponent, canActivate: [adminGuard] },
      { path: 'employees',            component: AllEmployeesComponent, canActivate: [adminGuard] },
      { path: 'cycles',               component: ReviewCyclesComponent, canActivate: [adminGuard] },
      { path: 'templates',            loadComponent: () => import('./features/templates/templates.component').then(m => m.TemplatesComponent), canActivate: [adminGuard] },
      { path: 'performance',          component: ComingSoonComponent, canActivate: [adminGuard] },
      { path: 'insights',             loadComponent: () => import('./features/insights/insights.component').then(m => m.InsightsComponent), canActivate: [adminGuard] },
      { path: 'roles',                component: RoleManagementComponent, canActivate: [adminGuard] },
      { path: '**',                   component: ComingSoonComponent },
    ],
  },
];
