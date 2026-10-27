import { Component, EventEmitter, Input, OnInit, Output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, NgForm } from '@angular/forms';
import { CursorEndDirective } from '../../../shared/cursor-end.directive';
import { DisplayEmployee } from '../display-employee.model';
import { AiReviewService, DisciplineInfo, DisciplineSeniorityLevel } from '../../../services/ai-review.service';

export interface EditEmployeeSubmitPayload {
  employee: DisplayEmployee;
  pendingUnlinkedMemberIds: string[];
}

@Component({
  selector: 'app-edit-employee-modal',
  standalone: true,
  imports: [CommonModule, FormsModule, CursorEndDirective],
  templateUrl: './edit-employee-modal.component.html',
  styleUrls: ['../modal-shared.css']
})
export class EditEmployeeModalComponent implements OnInit {
  private aiReviewService = inject(AiReviewService);

  @Input() employee!: DisplayEmployee;
  @Input() employees: DisplayEmployee[] = [];
  @Input() today: string = '';

  @Output() closed = new EventEmitter<void>();
  @Output() submitted = new EventEmitter<EditEmployeeSubmitPayload>();
  @Output() notify = new EventEmitter<{ message: string; type: 'success' | 'error' }>();
  @Output() viewTeam = new EventEmitter<string>(); // keep this so parent binding does not break

  editStep = 1;
  editEmployee!: DisplayEmployee;
  private originalEmployee!: DisplayEmployee;
  disciplines: DisciplineInfo[] = [];

  confirmPending = false;
  confirmMessage = '';
  confirmSubMessage = '';

  downgradeError = '';
  archiveError = '';

  downgradeBlockOpen = false;
  downgradeActiveCount = 0;

  archiveBlockOpen = false;
  archiveActiveCount = 0;

  unlinkConfirmOpen = false;
  unlinkConfirmEmployee: DisplayEmployee | null = null;

  mutualReportOpen = false;
  mutualReportName = '';

  selfReportOpen = false;
  selfReportField: 'reportsTo' | 'manages' | null = null;

  // Draft-only unlink state
  pendingUnlinkedMemberIds: string[] = [];

  // Manages autocomplete
  editMemberSearch = '';
  editMemberOpen = false;
  editMemberMatches: DisplayEmployee[] = [];
  editMemberHighlight = -1;

  // Reports To autocomplete
  editReportsToOpen = false;
  editReportsToMatches: DisplayEmployee[] = [];
  editReportsToHighlight = -1;

  ngOnInit(): void {
    this.editEmployee = {
      ...this.employee,
      teamMembers: this.employee.teamMembers ? [...this.employee.teamMembers] : []
    };

    this.originalEmployee = {
      ...this.employee,
      teamMembers: this.employee.teamMembers ? [...this.employee.teamMembers] : []
    };

    this.editEmployee.reportingToId = this.employee.reportingToId ?? null;

    this.aiReviewService.getDisciplines().subscribe({
      next: disciplines => {
        this.disciplines = disciplines;
      },
      error: () => {
        this.disciplines = [];
      }
    });
  }

  get seniorityLevels(): DisciplineSeniorityLevel[] {
    return this.disciplines.find(d => d.discipline === this.editEmployee?.discipline)?.levels ?? [];
  }

  onDisciplineChange(): void {
    if (!this.seniorityLevels.some(level => level.key === this.editEmployee.seniority)) {
      this.editEmployee.seniority = '';
    }
  }

  // Full name helper
  getFullName(e: DisplayEmployee): string {
    return `${e.firstName} ${e.lastName}`.trim();
  }

  // Managers allowed in Reports To
  get reportingManagers(): DisplayEmployee[] {
    return this.employees.filter(e =>
      e.status !== 'Archived' &&
      e.userId !== this.editEmployee.userId &&
      (e.employeeType === 'Admin' || e.employeeType === 'Manager')
    );
  }

  // Employees available in Manages search
  getAvailableEmployees(): DisplayEmployee[] {
    return this.employees.filter(e =>
      e.status !== 'Archived' &&
      e.userId !== this.editEmployee.userId &&
      !this.draftTeamMembers.some(m => m.userId === e.userId)
    );
  }

  // Reports To validation for chip display
  get isValidEditReportsTo(): boolean {
    return this.reportingManagers.some(m => m.name === this.editEmployee.reportsTo);
  }

  // Active direct reports after applying draft unlinks
  get activeDirectReports(): DisplayEmployee[] {
    return this.employees.filter(e =>
      e.reportingToId === this.editEmployee.userId &&
      e.status === 'Active' &&
      !this.pendingUnlinkedMemberIds.includes(e.userId)
    );
  }

  // Draft team members = existing linked active reports + newly added members in Step 2
  get draftTeamMembers(): { userId: string; name: string }[] {
    const existing = this.activeDirectReports.map(e => ({
      userId: e.userId,
      name: e.name
    }));

    const added = (this.editEmployee.teamMembers ?? []).filter(
      m => !existing.some(e => e.userId === m.userId)
    );

    return [...existing, ...added];
  }

  // Draft employee used for hasChanges + final emit
  private getDraftEmployee(): DisplayEmployee {
    return {
      ...this.editEmployee,
      teamMembers: this.draftTeamMembers
    };
  }

  get hasChanges(): boolean {
    return (
      JSON.stringify(this.getDraftEmployee()) !== JSON.stringify(this.originalEmployee) ||
      this.pendingUnlinkedMemberIds.length > 0
    );
  }

  sanitizeNameField(event: Event, model: any, field: string) {
    const input = event.target as HTMLInputElement;
    // Allow letters (incl. accented), spaces, hyphens, apostrophes and periods
    // — matches the backend NAME_PATTERN so valid surnames aren't mangled.
    const cleaned = input.value.replace(/[^A-Za-zÀ-ɏ\s.'\-]/g, '').slice(0, 50);

    if (input.value !== cleaned) {
      const pos = Math.min(input.selectionStart ?? cleaned.length, cleaned.length);
      input.value = cleaned;
      input.setSelectionRange(pos, pos);
    }

    model[field] = cleaned;
  }

  preventDateTyping(event: KeyboardEvent) {
    const allowed = [
      'Tab', 'Shift', 'Control', 'Alt', 'Escape',
      'F1', 'F2', 'F3', 'F4', 'F5', 'F6',
      'F7', 'F8', 'F9', 'F10', 'F11', 'F12'
    ];

    if (!allowed.includes(event.key)) {
      event.preventDefault();
    }
  }

  highlightMatch(name: string, search: string): string {
    if (!search) return name;
    const regex = new RegExp(`(${search})`, 'gi');
    return name.replace(regex, '<strong>$1</strong>');
  }

  close() {
    this.closed.emit();
  }

  // Role type change validation
  onRoleTypeChange(newType: string) {
    this.archiveError = '';
    this.downgradeError = '';

    const isDowngrade =
      (this.originalEmployee.employeeType === 'Manager' || this.originalEmployee.employeeType === 'Admin') &&
      newType === 'Employee';

    if (isDowngrade) {
      const activeReports = this.activeDirectReports;

      if (activeReports.length > 0) {
        this.downgradeActiveCount = activeReports.length;
        this.downgradeBlockOpen = true;
        this.editEmployee.employeeType = this.originalEmployee.employeeType;
        return;
      }
    }
  }

  // Status change validation
  onStatusChange(newStatus: string) {
    this.archiveError = '';

    const isArchiving =
      this.originalEmployee.status !== 'Archived' &&
      newStatus === 'Archived';

    const isManagerLike =
      this.editEmployee.employeeType === 'Manager' ||
      this.editEmployee.employeeType === 'Admin' ||
      this.originalEmployee.employeeType === 'Manager' ||
      this.originalEmployee.employeeType === 'Admin';

    if (isArchiving && isManagerLike) {
      const activeReports = this.activeDirectReports;

      if (activeReports.length > 0) {
        this.archiveActiveCount = activeReports.length;
        this.archiveBlockOpen = true;
        this.archiveError =
          `Can't archive while managing ${activeReports.length} active employee${activeReports.length > 1 ? 's' : ''}. Unlink them first.`;

        setTimeout(() => {
          this.editEmployee.status = this.originalEmployee.status;
        });

        return;
      }
    }
  }

  // Used by archive/downgrade popup
  goToTeam() {
    this.downgradeBlockOpen = false;
    this.archiveBlockOpen = false;
    this.viewTeam.emit(this.editEmployee.userId!);
  }

  cancelDowngradeBlock() {
    this.downgradeBlockOpen = false;
    this.downgradeError =
      `Can't downgrade while managing ${this.downgradeActiveCount} active employee${this.downgradeActiveCount > 1 ? 's' : ''}. Unlink them first.`;
  }

  cancelArchiveBlock() {
    this.archiveBlockOpen = false;
    this.archiveError =
      `Can't archive while managing ${this.archiveActiveCount} active employee${this.archiveActiveCount > 1 ? 's' : ''}. Unlink them first.`;

    setTimeout(() => {
      this.editEmployee.status = this.originalEmployee.status;
    });
  }

  nextEditStep(form: NgForm) {
    const fields = ['firstName', 'lastName', 'employeeType', 'role', 'discipline', 'seniority'];
    fields.forEach(f => {
      if (form.controls[f]) form.controls[f].markAsTouched();
    });

    if (fields.some(f => form.controls[f]?.invalid)) return;

    const isDowngrade =
      (this.originalEmployee.employeeType === 'Manager' || this.originalEmployee.employeeType === 'Admin') &&
      this.editEmployee.employeeType === 'Employee';

    if (isDowngrade) {
      const activeReports = this.activeDirectReports;

      if (activeReports.length > 0) {
        this.downgradeError =
          `Can't downgrade while managing ${activeReports.length} active employee${activeReports.length > 1 ? 's' : ''}. Unlink them first.`;
        return;
      }
    }

    this.downgradeError = '';
    this.editStep = 2;
  }

  onEditSubmit(form: NgForm) {
    if (form.invalid || !this.editEmployee) return;

    const emailTaken = this.employees.some(e =>
      e.email.toLowerCase() === this.editEmployee.email.toLowerCase() &&
      e.userId !== this.editEmployee.userId
    );

    if (emailTaken) {
      this.notify.emit({
        message: 'This email is already registered to another employee.',
        type: 'error'
      });
      return;
    }

    if (this.editEmployee.reportingToId && this.editEmployee.reportingToId === this.editEmployee.userId) {
      this.notify.emit({
        message: 'An employee cannot report to themselves.',
        type: 'error'
      });
      return;
    }

    // If text is typed but no manager was selected from the dropdown, clear the stale text
    // to avoid saving a mismatched or null reportingTo
    if (this.editEmployee.reportsTo && !this.isValidEditReportsTo) {
      this.editEmployee.reportsTo = '';
      this.editEmployee.reportingToId = null;
    }

    const isArchiving =
      this.originalEmployee.status !== 'Archived' &&
      this.editEmployee.status === 'Archived';

    const isManagerLike =
      this.editEmployee.employeeType === 'Manager' ||
      this.editEmployee.employeeType === 'Admin' ||
      this.originalEmployee.employeeType === 'Manager' ||
      this.originalEmployee.employeeType === 'Admin';

    if (isArchiving && isManagerLike) {
      const activeReports = this.activeDirectReports;

      if (activeReports.length > 0) {
        this.archiveActiveCount = activeReports.length;
        this.archiveBlockOpen = true;
        this.archiveError =
          `Can't archive while managing ${activeReports.length} active employee${activeReports.length > 1 ? 's' : ''}. Unlink them first.`;

        setTimeout(() => {
          this.editEmployee.status = this.originalEmployee.status;
        });

        return;
      }
    }

    const position = this.editEmployee.role.trim();
    if (!position) {
      this.notify.emit({
        message: 'Position cannot be blank or spaces only.',
        type: 'error'
      });
      return;
    }

    this.editEmployee.role = position;

    const draftEmployee = this.getDraftEmployee();

    this.confirmMessage = `Update ${draftEmployee.firstName} ${draftEmployee.lastName}?`;
    this.confirmSubMessage = 'Your changes will be saved and reflected across the organization.';
    this.confirmPending = true;
  }

  confirmSave() {
    this.confirmPending = false;

    const draftEmployee = this.getDraftEmployee();

    this.submitted.emit({
      employee: { ...draftEmployee },
      pendingUnlinkedMemberIds: [...this.pendingUnlinkedMemberIds]
    });
  }

  cancelConfirm() {
    this.confirmPending = false;
  }

  // Step 1 inline draft unlink
  unlinkDirectReport(member: DisplayEmployee) {
    if (!this.pendingUnlinkedMemberIds.includes(member.userId)) {
      this.pendingUnlinkedMemberIds.push(member.userId);
    }

    // Keep draft team members in sync
    this.editEmployee.teamMembers = this.draftTeamMembers;

    if (this.activeDirectReports.length === 0) {
      this.archiveError = '';
      this.downgradeError = '';
      this.archiveBlockOpen = false;
      this.downgradeBlockOpen = false;
    }
  }

  // Remove member that was newly added in Step 2, or draft-unlink an existing direct report
  removeTeamMember(member: { userId: string; name: string }) {
    const isExisting = this.activeDirectReports.some(r => r.userId === member.userId);
    if (isExisting && !this.pendingUnlinkedMemberIds.includes(member.userId)) {
      this.pendingUnlinkedMemberIds.push(member.userId);
    }
    this.editEmployee.teamMembers = (this.editEmployee.teamMembers ?? []).filter(
      m => m.userId !== member.userId
    );
  }

  // Manages autocomplete
  onEditMemberInput() {
    const s = this.editMemberSearch.toLowerCase();
    this.editMemberMatches = s
      ? this.getAvailableEmployees().filter(e => this.getFullName(e).toLowerCase().includes(s))
      : [];
    this.editMemberHighlight = -1;

    const ownName = this.getFullName(this.editEmployee).toLowerCase();
    const isSelfSearch = s.length >= 2 && ownName.includes(s) &&
      (this.editMemberMatches.length === 0 || s === ownName);
    if (isSelfSearch) {
      this.selfReportOpen = true;
      this.selfReportField = 'manages';
      this.editMemberSearch = '';
      this.editMemberMatches = [];
      this.editMemberOpen = false;
    }
  }

  onEditMemberFocus() {
    this.editMemberOpen = true;
    this.editMemberMatches = this.editMemberSearch
      ? this.getAvailableEmployees().filter(
          e => this.getFullName(e).toLowerCase().includes(this.editMemberSearch.toLowerCase())
        )
      : this.getAvailableEmployees();
  }

  onEditMemberBlur() {
    setTimeout(() => {
      this.editMemberOpen = false;
    }, 200);
  }

  onEditMemberKeyDown(event: KeyboardEvent) {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.editMemberHighlight = Math.min(
        this.editMemberHighlight + 1,
        this.editMemberMatches.length - 1
      );
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.editMemberHighlight = Math.max(this.editMemberHighlight - 1, -1);
    } else if (event.key === 'Enter') {
      event.preventDefault();
      if (this.editMemberHighlight >= 0) {
        this.selectEditMember(this.editMemberMatches[this.editMemberHighlight]);
      }
    }
  }

  selectEditMember(emp: DisplayEmployee) {
    if (emp.reportingToId && emp.reportingToId !== this.editEmployee.userId) {
      this.unlinkConfirmEmployee = emp;
      this.unlinkConfirmOpen = true;
      this.editMemberSearch = '';
      this.editMemberMatches = [];
      this.editMemberOpen = false;
      return;
    }

    this.addToManages(emp);
  }

  confirmUnlink() {
    if (this.unlinkConfirmEmployee) {
      this.addToManages(this.unlinkConfirmEmployee);
    }
    this.unlinkConfirmOpen = false;
    this.unlinkConfirmEmployee = null;
  }

  cancelUnlink() {
    this.unlinkConfirmOpen = false;
    this.unlinkConfirmEmployee = null;
  }

  private addToManages(emp: DisplayEmployee) {
    const members = this.editEmployee.teamMembers ??= [];
    if (!members.some(m => m.userId === emp.userId)) {
      members.push({ userId: emp.userId, name: emp.name });
    }

    // If this employee was previously marked as draft-unlinked, undo that
    this.pendingUnlinkedMemberIds = this.pendingUnlinkedMemberIds.filter(id => id !== emp.userId);

    this.editMemberSearch = '';
    this.editMemberMatches = [];
    this.editMemberOpen = false;
    this.editMemberHighlight = -1;
  }

  // Reports To autocomplete
  onEditReportsToInput() {
    const s = this.editEmployee?.reportsTo?.toLowerCase() || '';
    this.editReportsToMatches = s
      ? this.reportingManagers.filter(e => this.getFullName(e).toLowerCase().includes(s))
      : [];
    this.editReportsToHighlight = -1;

    const ownName = this.getFullName(this.editEmployee).toLowerCase();
    const isSelfSearch = s.length >= 2 && ownName.includes(s) &&
      (this.editReportsToMatches.length === 0 || s === ownName);
    if (isSelfSearch) {
      this.selfReportOpen = true;
      this.selfReportField = 'reportsTo';
      this.editEmployee.reportsTo = '';
      this.editEmployee.reportingToId = null;
      this.editReportsToMatches = [];
    }
  }

  onEditReportsToFocus() {
    this.editReportsToOpen = true;
    const s = this.editEmployee?.reportsTo?.toLowerCase() || '';
    this.editReportsToMatches = s
      ? this.reportingManagers.filter(e => this.getFullName(e).toLowerCase().includes(s))
      : this.reportingManagers;
  }

  onEditReportsToBlur() {
    setTimeout(() => {
      this.editReportsToOpen = false;
    }, 200);
  }

  onEditReportsToKeyDown(event: KeyboardEvent) {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.editReportsToHighlight = Math.min(
        this.editReportsToHighlight + 1,
        this.editReportsToMatches.length - 1
      );
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.editReportsToHighlight = Math.max(this.editReportsToHighlight - 1, -1);
    } else if (event.key === 'Enter' && this.editReportsToHighlight >= 0) {
      event.preventDefault();
      this.selectEditReportsTo(this.editReportsToMatches[this.editReportsToHighlight]);
    }
  }

  clearEditReportsTo() {
    this.editEmployee.reportsTo = '';
    this.editEmployee.reportingToId = null;
    this.editReportsToMatches = this.reportingManagers;
    this.editReportsToHighlight = -1;
  }

  selectEditReportsTo(mgr: DisplayEmployee) {
    if (mgr.reportingToId === this.editEmployee.userId) {
      this.mutualReportName = mgr.name;
      this.mutualReportOpen = true;
      this.editReportsToOpen = false;
      this.editReportsToMatches = [];
      this.editReportsToHighlight = -1;
      return;
    }

    this.editEmployee.reportsTo = mgr.name;
    this.editEmployee.reportingToId = mgr.userId;
    this.editReportsToOpen = false;
    this.editReportsToMatches = [];
    this.editReportsToHighlight = -1;
  }

  closeMutualReport() {
    this.mutualReportOpen = false;
    this.mutualReportName = '';
  }

  closeSelfReport() {
    this.selfReportOpen = false;
    this.selfReportField = null;
  }
}
