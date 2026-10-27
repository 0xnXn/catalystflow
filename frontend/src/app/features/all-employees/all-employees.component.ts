import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { forkJoin, of } from 'rxjs';
import { switchMap } from 'rxjs/operators';
import { User, UserRole, Team, EmployeeStatus } from '../../models/employee.model';
import { EmployeeService } from '../../services/employees.services';
import { DisplayEmployee } from './display-employee.model';
import { AddEmployeeModalComponent, AddEmployeeData } from './add-employee-modal/add-employee-modal.component';
import { EditEmployeeModalComponent, EditEmployeeSubmitPayload } from './edit-employee-modal/edit-employee-modal.component';
import { LoadingSpinnerComponent } from '../../shared/loading-spinner/loading-spinner.component';
import { SkeletonComponent } from '../../shared/skeleton/skeleton.component';

@Component({
  selector: 'app-all-employees',
  standalone: true,
  imports: [CommonModule, FormsModule, AddEmployeeModalComponent, EditEmployeeModalComponent, LoadingSpinnerComponent, SkeletonComponent],
  templateUrl: './all-employees.component.html',
  styleUrls: ['./all-employees.component.css']
})
export class AllEmployeesComponent implements OnInit {
  searchTerm: string = '';
  activeTab: string = 'employees';
  isModalOpen: boolean = false;
  isEditModalOpen: boolean = false;
  statusFilter: string = 'Active';
  typeFilter: string = '';
  isLoading: boolean = false;
  errorMessage: string = '';
  expandedTeam: string | null = null;

  teamHint: string = '';
  selectedEmployee: DisplayEmployee | null = null;

  // ── Archive block popup ───────────────────────────────────────
  archiveBlockModal: { open: boolean; name: string; count: number; managerId: string } = {
    open: false, name: '', count: 0, managerId: ''
  };
  today: string = new Date().toISOString().split('T')[0];

  notification: { show: boolean; message: string; type: 'success' | 'error' } = {
    show: false, message: '', type: 'success'
  };

  confirmModal: {
    open: boolean;
    message: string;
    subMessage: string;
    type: 'delete' | 'update';
    onConfirm: () => void;
  } = { open: false, message: '', subMessage: '', type: 'delete', onConfirm: () => {} };

  /** Raw users from API — needed for resolveReportingToId */
  private rawUsers: User[] = [];

  employees: DisplayEmployee[] = [];
  teamStructure: Team[] = [];
  filteredEmployees: DisplayEmployee[] = [];

  constructor(private employeeService: EmployeeService) {}

  ngOnInit(): void {
    this.expandedTeam = null;
    this.loadEmployees();
  }

  // ── LOAD ──────────────────────────────────────────────────────
  loadEmployees(): void {
    this.isLoading = true;
    this.employeeService.getEmployees().subscribe({
      next: (data: User[]) => {
        this.rawUsers = data;
        this.employees = data.map(u => this.toDisplay(u, data));
        this.filterEmployees();
        this.buildTeamStructure();
        this.isLoading = false;
      },
      error: (err) => {
        this.errorMessage = 'Failed to load employees. Please try again.';
        this.isLoading = false;
        console.error(err);
      }
    });
  }

  private silentSync(): void {
    this.employeeService.getEmployees().subscribe({
      next: (data: User[]) => {
        this.rawUsers = data;
        this.employees = data.map(u => this.toDisplay(u, data));
        this.filterEmployees();
        this.buildTeamStructure();
      },
      error: () => {}
    });
  }

  private buildTeamStructure(): void {
    const managers = this.rawUsers.filter(u =>
      (u.role === 'MANAGER' || u.role === 'ADMIN') && u.status === 'Active'
    );
    this.teamStructure = managers.map(mgr => ({
      managerId: mgr.userId,
      managerName: `${mgr.firstName} ${mgr.lastName}`,
      managerTitle: mgr.position,
      members: this.rawUsers
        .filter(u => u.reportingTo === mgr.userId && u.status === 'Active')
        .map(u => ({ userId: u.userId ?? '', name: `${u.firstName} ${u.lastName}`, role: u.position, email: u.email }))
    }));
  }

  // ── USER ↔ DISPLAY MAPPING ────────────────────────────────────
  private toDisplay(u: User, allUsers: User[]): DisplayEmployee {
    const manager = allUsers.find(m => m.userId === u.reportingTo);
    const teamMembers = allUsers
      .filter(m => m.reportingTo === u.userId && m.status === 'Active')
      .map(m => ({ userId: m.userId ?? '', name: `${m.firstName} ${m.lastName}` }));
    // Default every string field to '' so downstream filters / grids never hit
    // .toLowerCase() / .includes() on undefined. Some legacy records (early
    // admin seeds, in-flight migrations) ship without position/email/etc.
    return {
      id: u.id ?? 0,
      userId: u.userId ?? '',
      firstName: u.firstName ?? '',
      lastName: u.lastName ?? '',
      name: `${u.firstName ?? ''} ${u.lastName ?? ''}`.trim(),
      email: u.email ?? '',
      role: u.position ?? '',
      employeeType: this.toDisplayRole(u.role),
      dbRole: u.role,
      reportsTo: manager ? `${manager.firstName ?? ''} ${manager.lastName ?? ''}`.trim() : '',
      reportingToId: u.reportingTo || null,
      status: u.status,
      hireDate: u.hireDate ?? '',
      seniority: u.seniority ?? '',
      discipline: u.discipline ?? '',
      teamMembers
    };
  }

  private toDisplayRole(role: UserRole): string {
    if (role === 'ADMIN')   return 'Admin';
    if (role === 'MANAGER') return 'Manager';
    return 'Employee';
  }

  private toDbRole(displayRole: string): UserRole {
    if (displayRole === 'Admin')   return 'ADMIN';
    if (displayRole === 'Manager') return 'MANAGER';
    return 'EMPLOYEE';
  }

  private resolveReportingToId(managerName: string): string | null {
    const match = this.rawUsers.find(u => `${u.firstName} ${u.lastName}` === managerName);
    return match?.userId ?? null;
  }

  // ── COUNTS ────────────────────────────────────────────────────
  get activeCount()   { return this.employees.filter(e => e.status === 'Active').length; }
  get archivedCount() { return this.employees.filter(e => e.status === 'Archived').length; }
  get managerCount()  { return this.employees.filter(e => e.employeeType === 'Manager').length; }
  get employeeCount() { return this.employees.filter(e => e.employeeType === 'Employee').length; }

  // BUG-004: Team Structure tab must honour the search box. Previously it bound the
  // *ngFor straight to teamStructure (unfiltered) while the header count tracked the
  // All-Employees list — so typing changed the number but not the visible teams. Include
  // a team when the manager (name/title) or any member (name/role/email) matches.
  // Scope note: status/type filters remain an All-Employees concern; teamStructure is
  // already Active-only by construction.
  get filteredTeamStructure(): Team[] {
    const term = this.searchTerm?.toLowerCase().trim() || '';
    if (!term) return this.teamStructure;
    return this.teamStructure.filter(t =>
      (t.managerName  ?? '').toLowerCase().includes(term) ||
      (t.managerTitle ?? '').toLowerCase().includes(term) ||
      (t.members ?? []).some(m =>
        (m.name  ?? '').toLowerCase().includes(term) ||
        (m.role  ?? '').toLowerCase().includes(term) ||
        (m.email ?? '').toLowerCase().includes(term))
    );
  }

  // ── SEARCH / FILTER ───────────────────────────────────────────
  // searchTerm is a plain field above; the template wires (ngModelChange) so
  // every keystroke calls filterEmployees(). The previous setter-side-effect
  // pattern was inconsistent with the status/type filters and got swallowed
  // by Angular change detection in some cases — this is the canonical wiring.

  filterBy(value: string) {
    if (value === 'Manager' || value === 'Employee') {
      this.typeFilter = this.typeFilter === value ? '' : value;
    } else {
      this.statusFilter = this.statusFilter === value ? '' : value;
    }
    this.filterEmployees();
  }

  filterEmployees() {
    const term = this.searchTerm?.toLowerCase() || '';
    this.filteredEmployees = this.employees.filter(emp => {
      // Defensive lowercasing — admin / legacy users may be missing position
      // (mapped to emp.role) so calling toLowerCase() unguarded crashes the
      // whole filter and silently breaks the edit-employee flow.
      const matchesSearch = !term ||
        (emp.name  ?? '').toLowerCase().includes(term) ||
        (emp.email ?? '').toLowerCase().includes(term) ||
        (emp.role  ?? '').toLowerCase().includes(term);
      const matchesStatus = !this.statusFilter || emp.status === this.statusFilter;
      const matchesType   = !this.typeFilter   || emp.employeeType === this.typeFilter;
      return matchesSearch && matchesStatus && matchesType;
    });
  }

  // ── NOTIFICATION ──────────────────────────────────────────────
  showNotification(message: string, type: 'success' | 'error' = 'success') {
    this.notification = { show: true, message, type };
    setTimeout(() => { this.notification.show = false; }, 4000);
  }

  /** Pull the API's own message out of an HttpErrorResponse (the backend sends
   *  { message: "..." } on 4xx) so the user sees the real reason instead of a
   *  generic "please try again". Falls back when no usable message is present. */
  private apiErrorMessage(err: any, fallback: string): string {
    const msg = err?.error?.message ?? err?.error?.error ?? (typeof err?.error === 'string' ? err.error : null);
    return typeof msg === 'string' && msg.trim() ? msg : fallback;
  }

  // ── ADD MODAL ─────────────────────────────────────────────────
  openModal()  { this.isModalOpen = true; }
  closeModal() { this.isModalOpen = false; }

  onAddSubmitted(data: AddEmployeeData): void {
    const user: Omit<User, 'id' | 'userId'> = {
      email: data.email.trim(),
      firstName: data.firstName.trim(),
      lastName: data.lastName.trim(),
      role: this.toDbRole(data.employeeType),
      position: data.role.trim(),
      status: 'Active',
      hireDate: data.hireDate || undefined,
      seniority: data.seniority || undefined,
      discipline: data.discipline || undefined,
      reportingTo: this.resolveReportingToId(data.reportsTo)
    };

    this.employeeService.addEmployee(user as User).subscribe({
      next: (response: any) => {
        const newUserId = response?.userId;
        const memberOps = (newUserId && data.teamMembers?.length)
          ? data.teamMembers.map(m => {
              const raw = this.rawUsers.find(u => u.userId === m.userId);
              return raw ? this.employeeService.updateEmployee({ ...raw, reportingTo: newUserId }) : of(null);
            })
          : [of(null)];

        forkJoin(memberOps).subscribe({
          next: () => {
            this.isModalOpen = false;
            this.showNotification(`${data.firstName} ${data.lastName} has been added successfully. An invitation email has been sent.`);
            this.loadEmployees();
          },
          error: () => {
            this.isModalOpen = false;
            this.showNotification(`${data.firstName} ${data.lastName} was added but some team assignments failed. Please check the team structure.`, 'error');
            this.loadEmployees();
          }
        });
      },
      error: (err) => {
        console.error(err);
        this.showNotification(this.apiErrorMessage(err, 'Failed to add employee. Please try again.'), 'error');
      }
    });
  }

  // ── EDIT MODAL ────────────────────────────────────────────────
  openEditModal(emp: DisplayEmployee | undefined) {
    if (!emp) return;
    this.selectedEmployee = emp;
    this.isEditModalOpen = true;
  }

  closeEditModal() {
    this.isEditModalOpen = false;
    this.selectedEmployee = null;
  }

  navigateToTeam(managerId: string, hint: string) {
    this.isEditModalOpen = false;
    this.selectedEmployee = null;
    this.activeTab = 'structure';
    this.expandedTeam = managerId;
    this.teamHint = hint;

    setTimeout(() => {
      const el = document.getElementById(`team-${managerId}`);
      if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }, 100);
  }

  onViewTeam(managerId: string) {
    const mgr = this.employees.find(e => e.userId === managerId);
    this.navigateToTeam(managerId, mgr ? `Unlink all active employees from ${mgr.firstName} ${mgr.lastName} before downgrading their role.` : '');
  }

  onArchiveBlockViewTeam() {
    const { managerId, name } = this.archiveBlockModal;
    this.archiveBlockModal = { open: false, name: '', count: 0, managerId: '' };
    this.navigateToTeam(managerId, `Unlink all active employees from ${name} before archiving them.`);
  }

  closeArchiveBlock() {
    this.archiveBlockModal = { open: false, name: '', count: 0, managerId: '' };
  }

  onEditSubmitted(payload: EditEmployeeSubmitPayload): void {
  const { employee: updated, pendingUnlinkedMemberIds } = payload;

  // Apply draft unlinks to current active reports before validation
  const effectiveActiveReports = this.employees.filter(e =>
    e.reportingToId === updated.userId &&
    e.status === 'Active' &&
    !pendingUnlinkedMemberIds.includes(e.userId)
  );

  // Final archive guard after considering draft unlinks
  if (
    updated.status === 'Archived' &&
    (updated.employeeType === 'Manager' || updated.employeeType === 'Admin')
  ) {
    if (effectiveActiveReports.length > 0) {
      this.closeEditModal();
      this.archiveBlockModal = {
        open: true,
        name: `${updated.firstName} ${updated.lastName}`,
        count: effectiveActiveReports.length,
        managerId: updated.userId!
      };
      return;
    }
  }

  // Preserve the existing position if the modal cleared it — wiping a valid
  // position to '' would crash the next filterEmployees() call (see the role
  // toLowerCase guard in filterEmployees).
  const previousRaw = this.rawUsers.find(u => u.userId === updated.userId);
  const userToSave: User = {
    id: updated.id,
    userId: updated.userId,
    email: updated.email,
    firstName: updated.firstName,
    lastName: updated.lastName,
    role: this.toDbRole(updated.employeeType),
    position: updated.role || previousRaw?.position || '',
    status: updated.status,
    hireDate: updated.hireDate,
    seniority: updated.seniority || undefined,
    discipline: updated.discipline || undefined,
    reportingTo: updated.reportingToId || ''
  };

  const original = this.employees.find(e => e.userId === updated.userId);
  if (!original) {
    this.showNotification('Employee data out of sync. Please refresh and try again.', 'error');
    this.closeEditModal();
    return;
  }
  const originalMembers = original.teamMembers ?? [];

  // Keep only members that are still linked after draft unlink actions
  const updatedMembers = (updated.teamMembers ?? []).filter(
    m => !pendingUnlinkedMemberIds.includes(m.userId)
  );

  const added = updatedMembers.filter(
    m => !originalMembers.some(o => o.userId === m.userId)
  );

  const removed = originalMembers.filter(
    o =>
      pendingUnlinkedMemberIds.includes(o.userId) ||
      !updatedMembers.some(m => m.userId === o.userId)
  );

  const memberOps = [
    ...added.map(m => {
      const raw = this.rawUsers.find(u => u.userId === m.userId);
      return raw
        ? this.employeeService.updateEmployee({ ...raw, reportingTo: updated.userId })
        : of(null);
    }),

    ...removed.map(m => {
      const raw = this.rawUsers.find(u => u.userId === m.userId);
      return raw
        ? this.employeeService.updateEmployee({ ...raw, reportingTo: '' })
        : of(null);
    })
  ];

  // Snapshot for rollback on error
  const prevRawUsers = [...this.rawUsers];
  const prevEmployees = [...this.employees];
  const prevFiltered = [...this.filteredEmployees];
  const prevTeamStructure = [...this.teamStructure];

  // Optimistic update: patch local state immediately
  this.rawUsers = this.rawUsers.map(u => {
    if (u.userId === userToSave.userId) return { ...u, ...userToSave };
    if (added.some(m => m.userId === u.userId)) return { ...u, reportingTo: updated.userId ?? '' };
    if (removed.some(m => m.userId === u.userId)) return { ...u, reportingTo: '' };
    return u;
  });
  this.employees = this.rawUsers.map(u => this.toDisplay(u, this.rawUsers));
  this.filterEmployees();
  this.buildTeamStructure();

  this.closeEditModal();

  forkJoin([
    this.employeeService.updateEmployee(userToSave),
    ...(memberOps.length ? memberOps : [of(null)])
  ]).subscribe({
    next: () => {
      this.showNotification(
        `${updated.firstName} ${updated.lastName} has been updated successfully.`,
        'success'
      );
      this.silentSync();
    },
    error: (err) => {
      // Revert optimistic update
      this.rawUsers = prevRawUsers;
      this.employees = prevEmployees;
      this.filteredEmployees = prevFiltered;
      this.teamStructure = prevTeamStructure;
      console.error('Edit update failed:', err);
      this.showNotification(this.apiErrorMessage(err, 'Failed to update employee. Please try again.'), 'error');
    }
  });
}

  // ── ARCHIVE (soft delete) ─────────────────────────────────────
  deleteEmployee(emp: DisplayEmployee) {
    if (emp.status === 'Archived') {
      this.showNotification(`${emp.name} is already archived.`, 'error');
      return;
    }

    if (emp.employeeType === 'Manager' || emp.employeeType === 'Admin') {
      const activeReports = this.employees.filter(e =>
        e.reportingToId === emp.userId && e.status === 'Active'
      );
      if (activeReports.length > 0) {
        this.archiveBlockModal = {
          open: true,
          name: emp.name,
          count: activeReports.length,
          managerId: emp.userId!
        };
        return;
      }
    }

    this.openConfirm(
      `Archive ${emp.name}?`,
      'The employee will be marked as Archived and will no longer appear as active. No data is deleted.',
      'delete',
      () => {
        const rawUser = this.rawUsers.find(u => u.userId === emp.userId);
        if (!rawUser) return;
        const archived: User = { ...rawUser, status: 'Archived' };
        this.employeeService.updateEmployee(archived).subscribe({
          next: () => {
            const rawIdx = this.rawUsers.findIndex(u => u.userId === emp.userId);
            if (rawIdx > -1) this.rawUsers[rawIdx] = archived;
            const idx = this.employees.findIndex(e => e.userId === emp.userId);
            if (idx > -1) this.employees[idx] = { ...this.employees[idx], status: 'Archived' };
            this.filterEmployees();
            this.buildTeamStructure();
            this.showNotification(`${emp.name} has been archived.`);
          },
          error: (err) => {
            console.error(err);
            this.showNotification('Failed to archive employee. Please try again.', 'error');
          }
        });
      }
    );
  }

  // ── CONFIRMATION MODAL ────────────────────────────────────────
  openConfirm(message: string, subMessage: string, type: 'delete' | 'update', onConfirm: () => void) {
    this.confirmModal = { open: true, message, subMessage, type, onConfirm };
  }
  closeConfirm() { this.confirmModal = { ...this.confirmModal, open: false }; }
  handleConfirm() { this.confirmModal.onConfirm(); this.closeConfirm(); }

  // ── TEAM STRUCTURE ────────────────────────────────────────────
  toggleTeam(team: any): void {
    this.expandedTeam = this.expandedTeam === team.managerId ? null : team.managerId;
  }

  getManagerAsEmployee(team: Team): DisplayEmployee {
    return this.employees.find(e => e.userId === team.managerId) ?? {
      id: 0, userId: '', firstName: team.managerName.split(' ')[0],
      lastName: team.managerName.split(' ').slice(1).join(' '),
      name: team.managerName, email: '', role: team.managerTitle,
      employeeType: 'Manager', dbRole: 'MANAGER',
      reportsTo: '', reportingToId: null, status: 'Active', hireDate: ''
    };
  }

  getEmployeeByEmail(email: string): DisplayEmployee | undefined {
    return this.employees.find(e => e.email === email);
  }

  unlinkEmployee(member: { userId: string; name: string; role: string; email: string }, team: Team) {
    this.openConfirm(
      `Unlink ${member.name}?`,
      'This employee will be removed from the team.',
      'delete',
      () => {
        const rawUser = this.rawUsers.find(u => u.userId === member.userId);
        if (!rawUser) return;
        const updated = { ...rawUser, reportingTo: '' };

        // Optimistic update
        this.rawUsers = this.rawUsers.map(u => u.userId === member.userId ? updated : u);
        this.employees = this.rawUsers.map(u => this.toDisplay(u, this.rawUsers));
        this.filterEmployees();
        this.buildTeamStructure();

        this.employeeService.updateEmployee(updated).subscribe({
          next: () => {
            this.showNotification(`${member.name} has been unlinked from the team.`);
            this.silentSync();
          },
          error: (err) => {
            console.error(err);
            this.showNotification('Failed to unlink employee. Please try again.', 'error');
            this.loadEmployees();
          }
        });
      }
    );
  }
}
