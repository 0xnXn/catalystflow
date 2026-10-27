import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { EmployeeService } from '../../services/employees.services';
import { AuthService } from '../../core/auth/auth.service';
import { User, UserRole, EmployeeStatus } from '../../models/employee.model';

interface DisplayUser {
  id: number;
  userId: string;
  name: string;
  email: string;
  role: 'Admin' | 'Manager' | 'Employee';
  dbRole: UserRole;
  position: string;
  status: string;
  dateCreated: string;   // hireDate from API
  dateUpdated: string;   // updatedAt from API (server-set on every change)
}

@Component({
  selector: 'app-role-management',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './role-management.component.html',
  styleUrls: ['./role-management.component.css']
})
export class RoleManagementComponent implements OnInit {

  searchTerm = '';
  selectedFilter = 'all';
  isLoading = false;
  errorMessage = '';

  editingUserId: string | null = null;
  editingRole: string = '';
  confirmingUser: DisplayUser | null = null;

  users: DisplayUser[] = [];
  private rawUsers: User[] = [];

  notification: { show: boolean; message: string; type: 'success' | 'error' } = {
    show: false, message: '', type: 'success'
  };

  showNotification(message: string, type: 'success' | 'error' = 'success') {
    this.notification = { show: true, message, type };
    setTimeout(() => { this.notification.show = false; }, 4000);
  }

  constructor(private employeeService: EmployeeService, private authService: AuthService) {}

  ngOnInit(): void {
    this.loadUsers();
  }

  loadUsers(): void {
    this.isLoading = true;
    this.employeeService.getEmployees().subscribe({
      next: (users: User[]) => {
        this.rawUsers = users;
        this.users = users.filter(u => u.status !== 'Archived').map(u => ({
          id: u.id ?? 0,
          userId: u.userId ?? '',
          name: `${u.firstName} ${u.lastName}`,
          email: u.email,
          role: this.toDisplayRole(u.role),
          dbRole: u.role,
          position: u.position,
          status: u.status,
          dateCreated: u.hireDate ? this.formatDate(u.hireDate) : '—',
          dateUpdated: u.updatedAt ? this.formatDateTime(u.updatedAt) : '—',
        }));
        this.isLoading = false;
      },
      error: (err) => {
        this.errorMessage = 'Failed to load users. Please try again.';
        this.isLoading = false;
        console.error(err);
      }
    });
  }

  private toDisplayRole(role: UserRole): 'Admin' | 'Manager' | 'Employee' {
    if (role === 'ADMIN')   return 'Admin';
    if (role === 'MANAGER') return 'Manager';
    return 'Employee';
  }

  private toDbRole(displayRole: string): UserRole {
    if (displayRole === 'Admin')   return 'ADMIN';
    if (displayRole === 'Manager') return 'MANAGER';
    return 'EMPLOYEE';
  }

  private formatDate(dateStr: string): string {
    const d = new Date(dateStr);
    if (isNaN(d.getTime())) return dateStr;
    return d.toLocaleDateString('en-GB');
  }

  /** Date + time for the "Last Modified" column — a role change twice in one
   *  day should still read as two distinct edits. */
  private formatDateTime(dateStr: string): string {
    const d = new Date(dateStr);
    if (isNaN(d.getTime())) return dateStr;
    return d.toLocaleString('en-GB', {
      day: '2-digit', month: '2-digit', year: 'numeric',
      hour: '2-digit', minute: '2-digit',
    });
  }

  get filteredUsers(): DisplayUser[] {
    return this.users.filter(u => {
      const matchSearch = !this.searchTerm ||
        u.name.toLowerCase().includes(this.searchTerm.toLowerCase()) ||
        u.email.toLowerCase().includes(this.searchTerm.toLowerCase());
      const matchFilter = this.selectedFilter === 'all' ||
        (this.selectedFilter === 'admin'    && u.role === 'Admin') ||
        (this.selectedFilter === 'manager'  && u.role === 'Manager') ||
        (this.selectedFilter === 'employee' && u.role === 'Employee');
      return matchSearch && matchFilter;
    });
  }

  get totalUsers()   { return this.users.length; }
  get adminCount()   { return this.users.filter(u => u.role === 'Admin').length; }
  get managerCount() { return this.users.filter(u => u.role === 'Manager').length; }
  get employeeCount(){ return this.users.filter(u => u.role === 'Employee').length; }

  startEdit(user: DisplayUser): void {
    this.editingUserId = user.userId;
    this.editingRole = user.role;
  }

  cancelEdit(): void {
    this.editingUserId = null;
    this.editingRole = '';
    this.confirmingUser = null;
  }

  requestConfirm(user: DisplayUser): void {
    if (this.editingRole === user.role) {
      this.cancelEdit();
      return;
    }

    // 1. Prevent self-role change
    const currentSub = this.authService.getTokenPayload()?.sub;
    if (currentSub && currentSub === user.userId) {
      this.showNotification('You cannot change your own role.', 'error');
      this.cancelEdit();
      return;
    }

    // 2. Last Admin guard
    if (user.role === 'Admin' && this.editingRole !== 'Admin' && this.adminCount === 1) {
      this.showNotification('Cannot downgrade the last Admin. Assign another Admin first.', 'error');
      this.cancelEdit();
      return;
    }

    // 3. Block downgrade if active direct reports exist
    const isDowngrade =
      (user.role === 'Admin' && this.editingRole !== 'Admin') ||
      (user.role === 'Manager' && this.editingRole === 'Employee');

    if (isDowngrade) {
      const activeReports = this.rawUsers.filter(u =>
        u.reportingTo === user.userId && u.status === 'Active'
      );
      if (activeReports.length > 0) {
        this.showNotification(
          `Cannot downgrade ${user.name} — they have ${activeReports.length} active direct report${activeReports.length > 1 ? 's' : ''}. Reassign their team first.`,
          'error'
        );
        this.cancelEdit();
        return;
      }
    }

    this.confirmingUser = user;
    this.editingUserId = null;
  }

  get confirmWarningMessage(): string {
    if (!this.confirmingUser) return '';
    const from = this.confirmingUser.role;
    const to   = this.editingRole;

    if (from === 'Manager' && to === 'Admin') {
      const activeReports = this.rawUsers.filter(u =>
        u.reportingTo === this.confirmingUser!.userId && u.status === 'Active'
      );
      return activeReports.length > 0
        ? `Upgrading to Admin will remove ${this.confirmingUser.name} as manager of ${activeReports.length} employee${activeReports.length > 1 ? 's' : ''}. They will need a new manager.`
        : `Upgrading to Admin will give ${this.confirmingUser.name} full system access.`;
    }

    if ((from === 'Admin' && to !== 'Admin') || (from === 'Manager' && to === 'Employee')) {
      return `Downgrading from ${from} to ${to} will reduce this user's permissions and remove their management capabilities.`;
    }

    return `Changing this user's role will immediately update their access permissions and available features.`;
  }

  confirmSave(): void {
    const user = this.confirmingUser;
    if (!user) { this.cancelEdit(); return; }

    const dbRole = this.toDbRole(this.editingRole);
    const raw = this.rawUsers.find(u => u.userId === user.userId);
    if (!raw) { this.cancelEdit(); return; }

    const userToSave: User = { ...raw, role: dbRole };
    const userName = user.name;

    this.cancelEdit();
    this.showNotification(`Updating ${userName}'s role...`); // immediate feedback

    this.employeeService.updateEmployee(userToSave).subscribe({
      next: () => {
        this.showNotification(`${userName}'s role has been updated to ${this.toDisplayRole(dbRole)}.`);
        this.loadUsers();
      },
      error: (err) => {
        console.error('Failed to update role', err);
        this.showNotification('Failed to update role. Please try again.', 'error');
        this.loadUsers();
      }
    });
  }

  getRoleBadgeClass(role: string): string {
    if (role === 'Admin')    return 'badge-admin';
    if (role === 'Manager')  return 'badge-manager';
    return 'badge-employee';
  }
}
