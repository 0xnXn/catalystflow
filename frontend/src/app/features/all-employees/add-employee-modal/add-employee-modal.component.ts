import { Component, Input, Output, EventEmitter, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, NgForm } from '@angular/forms';
import { DisplayEmployee } from '../display-employee.model';
import { AiReviewService, DisciplineInfo, DisciplineSeniorityLevel } from '../../../services/ai-review.service';

export interface AddEmployeeData {
  firstName: string;
  lastName: string;
  email: string;
  employeeType: string;
  role: string;
  seniority: string;
  discipline: string;
  status: string;
  hireDate: string;
  reportsTo: string;
  teamMembers: { userId: string; name: string }[];
}

@Component({
  selector: 'app-add-employee-modal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './add-employee-modal.component.html',
  styleUrls: ['../modal-shared.css']
})
export class AddEmployeeModalComponent implements OnInit {
  private aiReviewService = inject(AiReviewService);

  @Input() employees: DisplayEmployee[] = [];
  @Input() today: string = '';

  @Output() closed    = new EventEmitter<void>();
  @Output() submitted = new EventEmitter<AddEmployeeData>();
  @Output() notify    = new EventEmitter<{ message: string; type: 'success' | 'error' }>();

  addStep = 1;
  emailExistsError = false;
  disciplines: DisciplineInfo[] = [];

  newEmployee = {
    firstName: '', lastName: '', email: '',
    employeeType: '', role: '', seniority: '', discipline: '', status: 'Active', reportsTo: '', hireDate: '',
    teamMembers: [] as { userId: string; name: string }[]
  };

  ngOnInit(): void {
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
    return this.disciplines.find(d => d.discipline === this.newEmployee.discipline)?.levels ?? [];
  }

  onDisciplineChange(): void {
    if (!this.seniorityLevels.some(level => level.key === this.newEmployee.seniority)) {
      this.newEmployee.seniority = '';
    }
  }

  // ── COMPUTED ──────────────────────────────────────────────────
  get reportingManagers(): DisplayEmployee[] {
    return this.employees.filter(e =>
      e.status !== 'Archived' &&
      (e.employeeType === 'Admin' || e.employeeType === 'Manager')
    );
  }

  getAvailableForNewEmployee(): DisplayEmployee[] {
    return this.employees.filter(e =>
      e.status !== 'Archived' &&
      !this.newEmployee.teamMembers.some(m => m.userId === e.userId)
    );
  }

  // ── HELPERS ───────────────────────────────────────────────────
  checkEmailExists(): boolean {
    const email = (this.newEmployee.email || '').trim().toLowerCase();
    return this.employees.some(e => e.email.toLowerCase() === email);
  }

  onEmailInput() { this.emailExistsError = false; }

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
    const allowed = ['Tab','Shift','Control','Alt','Escape','F1','F2','F3','F4','F5','F6','F7','F8','F9','F10','F11','F12'];
    if (!allowed.includes(event.key)) event.preventDefault();
  }

  highlightMatch(name: string, search: string): string {
    if (!search) return name;
    const regex = new RegExp(`(${search})`, 'gi');
    return name.replace(regex, '<strong>$1</strong>');
  }

  // ── NAVIGATION ────────────────────────────────────────────────
  close() { this.closed.emit(); }

  nextAddStep(form: NgForm) {
    const fields = ['firstName', 'lastName', 'email', 'employeeType', 'role', 'discipline', 'seniority'];
    fields.forEach(f => { if (form.controls[f]) form.controls[f].markAsTouched(); });
    if (fields.some(f => form.controls[f]?.invalid)) return;
    if (this.checkEmailExists()) { this.emailExistsError = true; return; }
    this.addStep = 2;
  }

  onSubmit(form: NgForm) {
    if (form.invalid) return;
    if (this.checkEmailExists()) { this.emailExistsError = true; return; }

    const fullName = `${this.newEmployee.firstName} ${this.newEmployee.lastName}`.trim();
    if (this.newEmployee.reportsTo && this.newEmployee.reportsTo === fullName) {
      this.notify.emit({ message: 'An employee cannot report to themselves.', type: 'error' });
      return;
    }

    const position = this.newEmployee.role.trim();
    if (!position) {
      this.notify.emit({ message: 'Position cannot be blank or spaces only.', type: 'error' });
      return;
    }

    this.submitted.emit({ ...this.newEmployee, role: position });
  }

  // ── MANAGES AUTOCOMPLETE ──────────────────────────────────────
  addMemberSearch = '';
  addMemberOpen = false;
  addMemberMatches: DisplayEmployee[] = [];
  addMemberHighlight = -1;

  onAddMemberInput() {
    const s = this.addMemberSearch.toLowerCase();
    this.addMemberMatches = s ? this.getAvailableForNewEmployee().filter(e => e.name.toLowerCase().includes(s)) : [];
    this.addMemberHighlight = -1;
  }

  onAddMemberFocus() {
    this.addMemberOpen = true;
    this.addMemberMatches = this.addMemberSearch
      ? this.getAvailableForNewEmployee().filter(e => e.name.toLowerCase().includes(this.addMemberSearch.toLowerCase()))
      : this.getAvailableForNewEmployee();
  }

  onAddMemberBlur() { setTimeout(() => { this.addMemberOpen = false; }, 200); }

  onAddMemberKeyDown(event: KeyboardEvent) {
    if (event.key === 'ArrowDown') { event.preventDefault(); this.addMemberHighlight = Math.min(this.addMemberHighlight + 1, this.addMemberMatches.length - 1); }
    else if (event.key === 'ArrowUp') { event.preventDefault(); this.addMemberHighlight = Math.max(this.addMemberHighlight - 1, -1); }
    else if (event.key === 'Enter' && this.addMemberHighlight >= 0) { event.preventDefault(); this.selectAddMember(this.addMemberMatches[this.addMemberHighlight]); }
  }

  // ── Unlink confirm popup ───────────────────────────────────────
  unlinkConfirmOpen = false;
  unlinkConfirmEmployee: DisplayEmployee | null = null;

  selectAddMember(emp: DisplayEmployee) {
    if (emp.reportingToId) {
      this.unlinkConfirmEmployee = emp;
      this.unlinkConfirmOpen = true;
      this.addMemberSearch = ''; this.addMemberMatches = []; this.addMemberOpen = false;
      return;
    }
    this.addToManages(emp);
  }

  confirmUnlink() {
    if (this.unlinkConfirmEmployee) this.addToManages(this.unlinkConfirmEmployee);
    this.unlinkConfirmOpen = false;
    this.unlinkConfirmEmployee = null;
  }

  cancelUnlink() {
    this.unlinkConfirmOpen = false;
    this.unlinkConfirmEmployee = null;
  }

  private addToManages(emp: DisplayEmployee) {
    if (!this.newEmployee.teamMembers.some(m => m.userId === emp.userId)) {
      this.newEmployee.teamMembers.push({ userId: emp.userId, name: emp.name });
    }
    this.addMemberSearch = ''; this.addMemberMatches = []; this.addMemberOpen = false; this.addMemberHighlight = -1;
  }

  removeNewTeamMember(member: { userId: string; name: string }) {
    this.newEmployee.teamMembers = this.newEmployee.teamMembers.filter(m => m.userId !== member.userId);
  }

  // ── REPORTS TO AUTOCOMPLETE ───────────────────────────────────
  addReportsToOpen = false;
  addReportsToMatches: DisplayEmployee[] = [];
  addReportsToHighlight = -1;

  onAddReportsToInput() {
    const s = this.newEmployee.reportsTo?.toLowerCase() || '';
    this.addReportsToMatches = s ? this.reportingManagers.filter(e => e.name.toLowerCase().includes(s)) : [];
    this.addReportsToHighlight = -1;
  }

  onAddReportsToFocus() {
    this.addReportsToOpen = true;
    const s = this.newEmployee.reportsTo?.toLowerCase() || '';
    this.addReportsToMatches = s ? this.reportingManagers.filter(e => e.name.toLowerCase().includes(s)) : this.reportingManagers;
  }

  onAddReportsToBlur() { setTimeout(() => { this.addReportsToOpen = false; }, 200); }

  onAddReportsToKeyDown(event: KeyboardEvent) {
    if (event.key === 'ArrowDown') { event.preventDefault(); this.addReportsToHighlight = Math.min(this.addReportsToHighlight + 1, this.addReportsToMatches.length - 1); }
    else if (event.key === 'ArrowUp') { event.preventDefault(); this.addReportsToHighlight = Math.max(this.addReportsToHighlight - 1, -1); }
    else if (event.key === 'Enter' && this.addReportsToHighlight >= 0) { event.preventDefault(); this.selectAddReportsTo(this.addReportsToMatches[this.addReportsToHighlight]); }
  }

  clearAddReportsTo() { this.newEmployee.reportsTo = ''; this.addReportsToMatches = this.reportingManagers; this.addReportsToHighlight = -1; }

  selectAddReportsTo(mgr: DisplayEmployee) {
    this.newEmployee.reportsTo = mgr.name;
    this.addReportsToOpen = false; this.addReportsToMatches = []; this.addReportsToHighlight = -1;
  }

  get isValidReportsTo(): boolean {
  return this.reportingManagers.some(
    m => m.name === this.newEmployee.reportsTo
  );
}
}
