import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { NgForm } from '@angular/forms';
import { of } from 'rxjs';

import { AiReviewService, DisciplineInfo } from '../../../services/ai-review.service';
import { DisplayEmployee } from '../display-employee.model';
import { EditEmployeeModalComponent, EditEmployeeSubmitPayload } from './edit-employee-modal.component';

describe('EditEmployeeModalComponent profile criteria', () => {
  let fixture: ComponentFixture<EditEmployeeModalComponent>;
  let component: EditEmployeeModalComponent;
  let aiReviewService: jasmine.SpyObj<AiReviewService>;

  const disciplines: DisciplineInfo[] = [
    {
      discipline: 'Software & DevOps Engineering',
      levels: [
        { key: 'Graduate', displayName: 'Graduate' },
        { key: 'Engineer', displayName: 'Engineer' }
      ]
    },
    {
      discipline: 'Site Lead',
      levels: [{ key: 'Site Lead', displayName: 'Site Lead' }]
    }
  ];

  const employee: DisplayEmployee = {
    id: 1,
    userId: 'user-1',
    firstName: 'Jane',
    lastName: 'Smith',
    name: 'Jane Smith',
    email: 'jane.smith@example.com',
    role: 'Engineer',
    employeeType: 'Employee',
    dbRole: 'EMPLOYEE',
    reportsTo: '',
    reportingToId: null,
    status: 'Active',
    hireDate: '',
    seniority: 'Engineer',
    discipline: 'Software & DevOps Engineering',
    teamMembers: []
  };

  function makeEmployee(overrides: Partial<DisplayEmployee> = {}): DisplayEmployee {
    return {
      id: 99,
      userId: 'user-99',
      firstName: 'Alex',
      lastName: 'Person',
      name: 'Alex Person',
      email: 'alex.person@example.com',
      role: 'Engineer',
      employeeType: 'Employee',
      dbRole: 'EMPLOYEE' as any,
      reportsTo: '',
      reportingToId: null,
      status: 'Active' as any,
      hireDate: '2024-01-01',
      seniority: 'Engineer',
      discipline: 'Software & DevOps Engineering',
      teamMembers: [],
      ...overrides
    };
  }

  function formStub(invalid = false, invalidFields: string[] = []): NgForm {
    const fields = ['firstName', 'lastName', 'employeeType', 'role', 'discipline', 'seniority'];
    const controls = Object.fromEntries(fields.map(field => [
      field,
      {
        invalid: invalidFields.includes(field),
        markAsTouched: jasmine.createSpy(`${field}Touched`)
      }
    ]));
    return { invalid, controls } as unknown as NgForm;
  }

  beforeEach(async () => {
    aiReviewService = jasmine.createSpyObj<AiReviewService>('AiReviewService', ['getDisciplines']);
    aiReviewService.getDisciplines.and.returnValue(of(disciplines));

    await TestBed.configureTestingModule({
      imports: [EditEmployeeModalComponent],
      providers: [{ provide: AiReviewService, useValue: aiReviewService }]
    }).compileComponents();

    fixture = TestBed.createComponent(EditEmployeeModalComponent);
    component = fixture.componentInstance;
    component.employee = employee;
    component.employees = [employee];
    fixture.detectChanges();
  });

  it('should expose seniority levels for the selected discipline', () => {
    expect(component.seniorityLevels.map(level => level.key)).toEqual(['Graduate', 'Engineer']);
  });

  it('should reset seniority when the discipline changes to an incompatible option', () => {
    component.editEmployee.discipline = 'Site Lead';
    component.onDisciplineChange();

    expect(component.editEmployee.seniority).toBe('');
  });

  it('should emit seniority and discipline when updating an employee', () => {
    let submitted: EditEmployeeSubmitPayload | undefined;
    component.submitted.subscribe(value => {
      submitted = value;
    });

    component.editEmployee.seniority = 'Graduate';
    component.editEmployee.discipline = 'Software & DevOps Engineering';

    component.onEditSubmit({ invalid: false } as NgForm);
    component.confirmSave();

    expect(submitted?.employee.seniority).toBe('Graduate');
    expect(submitted?.employee.discipline).toBe('Software & DevOps Engineering');
  });

  // Initialization should clone editable data so modal changes do not mutate the selected row early.
  it('should clone the selected employee and default a missing reportingToId to null', () => {
    expect(component.editEmployee).not.toBe(component.employee);
    expect(component.editEmployee.teamMembers).not.toBe(component.employee.teamMembers);
    expect(component.editEmployee.reportingToId).toBeNull();

    component.editEmployee.firstName = 'Changed';

    expect(component.employee.firstName).toBe('Jane');
  });

  // Step 1 blocks incomplete forms and manager downgrades while active direct reports remain.
  it('should mark required profile fields and stay on step 1 when invalid', () => {
    const form = formStub(false, ['role']);

    component.nextEditStep(form);

    expect(form.controls['firstName'].markAsTouched).toHaveBeenCalled();
    expect(form.controls['role'].markAsTouched).toHaveBeenCalled();
    expect(component.editStep).toBe(1);
  });

  it('should block downgrade from manager when active direct reports exist', () => {
    const manager = makeEmployee({
      userId: 'mgr-1',
      firstName: 'Mary',
      lastName: 'Manager',
      name: 'Mary Manager',
      employeeType: 'Manager',
      dbRole: 'Manager' as any
    });
    const report = makeEmployee({
      userId: 'emp-1',
      name: 'Direct Report',
      reportingToId: 'mgr-1',
      reportsTo: 'Mary Manager'
    });
    component.employee = manager;
    component.employees = [manager, report];
    component.ngOnInit();

    component.onRoleTypeChange('Employee');
    expect(component.downgradeBlockOpen).toBeTrue();
    expect(component.editEmployee.employeeType).toBe('Manager');

    component.cancelDowngradeBlock();
    expect(component.downgradeError).toContain("Can't downgrade while managing 1 active employee");

    component.editEmployee.employeeType = 'Employee';
    component.nextEditStep(formStub());
    expect(component.downgradeError).toContain("Can't downgrade while managing 1 active employee");

    component.unlinkDirectReport(report);
    component.nextEditStep(formStub());
    expect(component.editStep).toBe(2);
  });

  // Archiving managers is blocked until their active reports are draft-unlinked.
  it('should block archiving manager-like employees with active reports', fakeAsync(() => {
    const manager = makeEmployee({
      userId: 'mgr-1',
      name: 'Mary Manager',
      employeeType: 'Manager',
      dbRole: 'Manager' as any,
      status: 'Active' as any
    });
    const report = makeEmployee({
      userId: 'emp-1',
      name: 'Direct Report',
      reportingToId: 'mgr-1',
      reportsTo: 'Mary Manager'
    });
    component.employee = manager;
    component.employees = [manager, report];
    component.ngOnInit();

    component.onStatusChange('Archived');

    expect(component.archiveBlockOpen).toBeTrue();
    expect(component.archiveError).toContain("Can't archive while managing 1 active employee");

    component.cancelArchiveBlock();
    tick();

    expect(component.archiveBlockOpen).toBeFalse();
    expect(component.editEmployee.status).toBe('Active');
  }));

  it('should emit viewTeam and close block popups from goToTeam', () => {
    spyOn(component.viewTeam, 'emit');
    component.downgradeBlockOpen = true;
    component.archiveBlockOpen = true;

    component.goToTeam();

    expect(component.downgradeBlockOpen).toBeFalse();
    expect(component.archiveBlockOpen).toBeFalse();
    expect(component.viewTeam.emit).toHaveBeenCalledWith('user-1');
  });

  it('should use plural block messages when managing multiple active reports', fakeAsync(() => {
    const manager = makeEmployee({
      userId: 'mgr-1',
      name: 'Mary Manager',
      employeeType: 'Manager',
      dbRole: 'Manager' as any,
      status: 'Active' as any
    });
    const reportA = makeEmployee({ userId: 'emp-1', name: 'Report A', reportingToId: 'mgr-1' });
    const reportB = makeEmployee({ userId: 'emp-2', name: 'Report B', reportingToId: 'mgr-1' });
    component.employee = manager;
    component.employees = [manager, reportA, reportB];
    component.ngOnInit();

    component.onRoleTypeChange('Employee');
    component.cancelDowngradeBlock();
    expect(component.downgradeError).toContain('2 active employees');

    component.editEmployee.status = 'Archived' as any;
    component.onStatusChange('Archived');
    component.cancelArchiveBlock();
    tick();

    expect(component.archiveError).toContain('2 active employees');
    expect(component.editEmployee.status).toBe('Active');
  }));

  // Submit validates conflicts that cannot be represented by field validators alone.
  it('should block invalid, duplicate-email, self-reporting, and blank-role submits', () => {
    spyOn(component.submitted, 'emit');
    spyOn(component.notify, 'emit');

    component.onEditSubmit(formStub(true));
    expect(component.submitted.emit).not.toHaveBeenCalled();

    component.employees = [
      employee,
      makeEmployee({ userId: 'other-1', email: 'jane.smith@example.com' })
    ];
    component.onEditSubmit(formStub());
    expect(component.notify.emit).toHaveBeenCalledWith({
      message: 'This email is already registered to another employee.',
      type: 'error'
    });

    component.employees = [employee];
    component.editEmployee.reportingToId = 'user-1';
    component.onEditSubmit(formStub());
    expect(component.notify.emit).toHaveBeenCalledWith({
      message: 'An employee cannot report to themselves.',
      type: 'error'
    });

    component.editEmployee.reportingToId = null;
    component.editEmployee.role = '   ';
    component.onEditSubmit(formStub());
    expect(component.notify.emit).toHaveBeenCalledWith({
      message: 'Position cannot be blank or spaces only.',
      type: 'error'
    });
    expect(component.submitted.emit).not.toHaveBeenCalled();
  });

  it('should block archiving during submit when active reports still exist', fakeAsync(() => {
    const manager = makeEmployee({
      userId: 'mgr-1',
      name: 'Mary Manager',
      email: 'mary.manager@example.com',
      employeeType: 'Manager',
      dbRole: 'Manager' as any,
      status: 'Active' as any
    });
    const report = makeEmployee({
      userId: 'emp-1',
      name: 'Direct Report',
      email: 'direct.report@example.com',
      reportingToId: 'mgr-1'
    });
    component.employee = manager;
    component.employees = [manager, report];
    component.ngOnInit();
    component.editEmployee.status = 'Archived' as any;

    component.onEditSubmit(formStub());

    expect(component.archiveBlockOpen).toBeTrue();
    expect(component.archiveError).toContain("Can't archive while managing 1 active employee");

    tick();
    expect(component.editEmployee.status).toBe('Active');
  }));

  it('should clear stale reportsTo text and open confirmation for a valid submit', () => {
    component.editEmployee.reportsTo = 'Not A Real Manager';
    component.editEmployee.reportingToId = 'missing';
    component.editEmployee.role = ' Senior Engineer ';

    component.onEditSubmit(formStub());

    expect(component.editEmployee.reportsTo).toBe('');
    expect(component.editEmployee.reportingToId).toBeNull();
    expect(component.editEmployee.role).toBe('Senior Engineer');
    expect(component.confirmPending).toBeTrue();
    expect(component.confirmMessage).toContain('Update Jane Smith?');
  });

  it('should confirm and cancel the final update payload', () => {
    let submitted: EditEmployeeSubmitPayload | undefined;
    component.submitted.subscribe(value => submitted = value);
    component.pendingUnlinkedMemberIds = ['emp-1'];
    component.editEmployee.teamMembers = [{ userId: 'emp-2', name: 'New Member' }];

    component.cancelConfirm();
    expect(component.confirmPending).toBeFalse();

    component.confirmSave();

    expect(submitted?.employee.teamMembers).toEqual([{ userId: 'emp-2', name: 'New Member' }]);
    expect(submitted?.pendingUnlinkedMemberIds).toEqual(['emp-1']);
  });

  // Draft team members combine active direct reports with newly-added members until save.
  it('should compute active reports, draft team members, and change state', () => {
    const report = makeEmployee({
      userId: 'emp-1',
      name: 'Direct Report',
      reportingToId: 'user-1',
      reportsTo: 'Jane Smith'
    });
    component.employees = [employee, report];
    component.editEmployee.teamMembers = [{ userId: 'emp-2', name: 'Added Member' }];

    expect(component.activeDirectReports.map(e => e.userId)).toEqual(['emp-1']);
    expect(component.draftTeamMembers).toEqual([
      { userId: 'emp-1', name: 'Direct Report' },
      { userId: 'emp-2', name: 'Added Member' }
    ]);
    expect(component.hasChanges).toBeTrue();

    component.unlinkDirectReport(report);

    expect(component.pendingUnlinkedMemberIds).toEqual(['emp-1']);
    expect(component.activeDirectReports).toEqual([]);
  });

  it('should remove existing direct reports as pending unlinks and remove newly added members directly', () => {
    const report = makeEmployee({
      userId: 'emp-1',
      name: 'Direct Report',
      reportingToId: 'user-1',
      reportsTo: 'Jane Smith'
    });
    component.employees = [employee, report];
    component.editEmployee.teamMembers = [
      { userId: 'emp-1', name: 'Direct Report' },
      { userId: 'emp-2', name: 'New Member' }
    ];

    component.removeTeamMember({ userId: 'emp-1', name: 'Direct Report' });
    expect(component.pendingUnlinkedMemberIds).toEqual(['emp-1']);

    component.removeTeamMember({ userId: 'emp-2', name: 'New Member' });
    expect(component.editEmployee.teamMembers).toEqual([]);
  });

  // Manages autocomplete handles normal add, linked-employee confirmation, and self-search prevention.
  it('should search and keyboard-select a managed employee', () => {
    const candidate = makeEmployee({
      userId: 'emp-1',
      firstName: 'Taylor',
      lastName: 'Employee',
      name: 'Taylor Employee'
    });
    component.employees = [employee, candidate];

    component.onEditMemberFocus();
    expect(component.editMemberOpen).toBeTrue();
    expect(component.editMemberMatches).toEqual([candidate]);

    component.editMemberSearch = 'taylor';
    component.onEditMemberInput();
    expect(component.editMemberMatches).toEqual([candidate]);

    component.onEditMemberKeyDown({ key: 'ArrowDown', preventDefault: jasmine.createSpy('down') } as any);
    expect(component.editMemberHighlight).toBe(0);

    component.onEditMemberKeyDown({ key: 'Enter', preventDefault: jasmine.createSpy('enter') } as any);
    expect(component.editEmployee.teamMembers).toEqual([{ userId: 'emp-1', name: 'Taylor Employee' }]);

    component.onEditMemberKeyDown({ key: 'ArrowUp', preventDefault: jasmine.createSpy('up') } as any);
    expect(component.editMemberHighlight).toBe(-1);
  });

  it('should close autocomplete dropdowns after blur delay', fakeAsync(() => {
    component.editMemberOpen = true;
    component.editReportsToOpen = true;

    component.onEditMemberBlur();
    component.onEditReportsToBlur();
    tick(200);

    expect(component.editMemberOpen).toBeFalse();
    expect(component.editReportsToOpen).toBeFalse();
  }));

  it('should confirm unlink before adding an employee linked to another manager', () => {
    const linked = makeEmployee({
      userId: 'emp-1',
      name: 'Linked Employee',
      reportingToId: 'mgr-2',
      reportsTo: 'Other Manager'
    });

    component.selectEditMember(linked);

    expect(component.unlinkConfirmOpen).toBeTrue();
    expect(component.unlinkConfirmEmployee).toBe(linked);

    component.confirmUnlink();
    expect(component.editEmployee.teamMembers).toEqual([{ userId: 'emp-1', name: 'Linked Employee' }]);
    expect(component.unlinkConfirmOpen).toBeFalse();

    component.selectEditMember(linked);
    component.cancelUnlink();
    expect(component.unlinkConfirmEmployee).toBeNull();
  });

  it('should open self-report warning when searching own name as a managed member', () => {
    component.editMemberSearch = 'jane smith';

    component.onEditMemberInput();

    expect(component.selfReportOpen).toBeTrue();
    expect(component.selfReportField).toBe('manages');

    component.closeSelfReport();
    expect(component.selfReportOpen).toBeFalse();
    expect(component.selfReportField).toBeNull();
  });

  // Reports-to autocomplete must prevent self-reporting and circular manager relationships.
  it('should search, select, and clear reports-to manager', () => {
    const manager = makeEmployee({
      userId: 'mgr-1',
      name: 'Alex Manager',
      firstName: 'Alex',
      lastName: 'Manager',
      employeeType: 'Manager',
      dbRole: 'Manager' as any
    });
    component.employees = [employee, manager];
    component.editEmployee.reportsTo = 'alex';

    component.onEditReportsToInput();
    expect(component.editReportsToMatches).toEqual([manager]);

    component.onEditReportsToFocus();
    component.onEditReportsToKeyDown({ key: 'ArrowDown', preventDefault: jasmine.createSpy('down') } as any);
    component.onEditReportsToKeyDown({ key: 'ArrowUp', preventDefault: jasmine.createSpy('up') } as any);
    component.onEditReportsToKeyDown({ key: 'ArrowDown', preventDefault: jasmine.createSpy('downAgain') } as any);
    component.onEditReportsToKeyDown({ key: 'Enter', preventDefault: jasmine.createSpy('enter') } as any);

    expect(component.editEmployee.reportsTo).toBe('Alex Manager');
    expect(component.editEmployee.reportingToId).toBe('mgr-1');
    expect(component.isValidEditReportsTo).toBeTrue();

    component.clearEditReportsTo();
    expect(component.editEmployee.reportsTo).toBe('');
    expect(component.editEmployee.reportingToId).toBeNull();
  });

  it('should prevent mutual reporting loops and self reports-to search', () => {
    const directReportManager = makeEmployee({
      userId: 'mgr-2',
      name: 'Morgan Manager',
      firstName: 'Morgan',
      lastName: 'Manager',
      employeeType: 'Manager',
      dbRole: 'Manager' as any,
      reportingToId: 'user-1'
    });

    component.selectEditReportsTo(directReportManager);
    expect(component.mutualReportOpen).toBeTrue();
    expect(component.mutualReportName).toBe('Morgan Manager');

    component.closeMutualReport();
    expect(component.mutualReportOpen).toBeFalse();

    component.editEmployee.reportsTo = 'jane smith';
    component.onEditReportsToInput();
    expect(component.selfReportOpen).toBeTrue();
    expect(component.selfReportField).toBe('reportsTo');
    expect(component.editEmployee.reportsTo).toBe('');
  });

  // Small helpers protect user-entered profile data from malformed values.
  it('should sanitize names, prevent manual date typing, highlight matches, and close', () => {
    const input = document.createElement('input');
    input.value = 'Ja123ne!';
    input.setSelectionRange(4, 4);

    component.sanitizeNameField({ target: input } as any, component.editEmployee, 'firstName');
    expect(input.value).toBe('Jane');
    expect(component.editEmployee.firstName).toBe('Jane');

    const blocked = { key: 'a', preventDefault: jasmine.createSpy('blocked') } as any;
    component.preventDateTyping(blocked);
    expect(blocked.preventDefault).toHaveBeenCalled();

    const allowed = { key: 'Tab', preventDefault: jasmine.createSpy('allowed') } as any;
    component.preventDateTyping(allowed);
    expect(allowed.preventDefault).not.toHaveBeenCalled();

    expect(component.highlightMatch('Jane Smith', 'Jane')).toBe('<strong>Jane</strong> Smith');
    expect(component.highlightMatch('Jane Smith', '')).toBe('Jane Smith');

    spyOn(component.closed, 'emit');
    component.close();
    expect(component.closed.emit).toHaveBeenCalled();
  });
});
