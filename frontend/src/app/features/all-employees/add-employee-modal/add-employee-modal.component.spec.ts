import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NgForm } from '@angular/forms';
import { of } from 'rxjs';

import { AiReviewService, DisciplineInfo } from '../../../services/ai-review.service';
import { DisplayEmployee } from '../display-employee.model';
import { AddEmployeeData, AddEmployeeModalComponent } from './add-employee-modal.component';

describe('AddEmployeeModalComponent profile criteria', () => {
  let fixture: ComponentFixture<AddEmployeeModalComponent>;
  let component: AddEmployeeModalComponent;
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

  function employee(overrides: Partial<DisplayEmployee> = {}): DisplayEmployee {
    return {
      id: 1,
      userId: 'user-1',
      firstName: 'Alex',
      lastName: 'Manager',
      name: 'Alex Manager',
      email: 'alex.manager@example.com',
      role: 'Engineering Manager',
      employeeType: 'Manager',
      dbRole: 'Manager' as any,
      reportsTo: '',
      reportingToId: null,
      status: 'Active' as any,
      hireDate: '2024-01-01',
      ...overrides
    };
  }

  function validNewEmployee() {
    return {
      firstName: 'Jane',
      lastName: 'Smith',
      email: 'jane.smith@example.com',
      employeeType: 'Employee',
      role: ' Engineer ',
      seniority: 'Engineer',
      discipline: 'Software & DevOps Engineering',
      status: 'Active',
      reportsTo: '',
      hireDate: '',
      teamMembers: [] as { userId: string; name: string }[]
    };
  }

  function formStub(invalid = false, invalidFields: string[] = []): NgForm {
    const fields = ['firstName', 'lastName', 'email', 'employeeType', 'role', 'discipline', 'seniority'];
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
      imports: [AddEmployeeModalComponent],
      providers: [{ provide: AiReviewService, useValue: aiReviewService }]
    }).compileComponents();

    fixture = TestBed.createComponent(AddEmployeeModalComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should load discipline seniority levels from AI review metadata', () => {
    component.newEmployee.discipline = 'Software & DevOps Engineering';

    expect(component.seniorityLevels.map(level => level.key)).toEqual(['Graduate', 'Engineer']);
  });

  it('should reset seniority when the discipline changes to an incompatible option', () => {
    component.newEmployee.discipline = 'Software & DevOps Engineering';
    component.newEmployee.seniority = 'Engineer';

    component.newEmployee.discipline = 'Site Lead';
    component.onDisciplineChange();

    expect(component.newEmployee.seniority).toBe('');
  });

  it('should emit seniority and discipline when adding an employee', () => {
    let submitted: AddEmployeeData | undefined;
    component.submitted.subscribe(value => {
      submitted = value;
    });

    component.newEmployee = {
      firstName: 'Jane',
      lastName: 'Smith',
      email: 'jane.smith@example.com',
      employeeType: 'Employee',
      role: 'Engineer',
      seniority: 'Engineer',
      discipline: 'Software & DevOps Engineering',
      status: 'Active',
      reportsTo: '',
      hireDate: '',
      teamMembers: []
    };

    component.onSubmit({ invalid: false } as NgForm);

    expect(submitted?.seniority).toBe('Engineer');
    expect(submitted?.discipline).toBe('Software & DevOps Engineering');
  });

  // Step 1 is the gatekeeper for required profile fields and duplicate emails.
  it('should mark required fields and stay on step 1 when profile fields are invalid', () => {
    const form = formStub(false, ['email']);

    component.nextAddStep(form);

    expect(form.controls['firstName'].markAsTouched).toHaveBeenCalled();
    expect(form.controls['email'].markAsTouched).toHaveBeenCalled();
    expect(component.addStep).toBe(1);
  });

  it('should block duplicate emails before moving to reporting structure', () => {
    component.employees = [employee({ email: 'jane.smith@example.com' })];
    component.newEmployee = validNewEmployee();

    component.nextAddStep(formStub());

    expect(component.emailExistsError).toBeTrue();
    expect(component.addStep).toBe(1);

    component.newEmployee.email = '  JANE.SMITH@EXAMPLE.COM  ';
    expect(component.checkEmailExists()).toBeTrue();

    component.onEmailInput();
    expect(component.emailExistsError).toBeFalse();
  });

  it('should move to step 2 when required profile fields are valid and email is unique', () => {
    component.employees = [employee({ email: 'alex.manager@example.com' })];
    component.newEmployee = validNewEmployee();

    component.nextAddStep(formStub());

    expect(component.addStep).toBe(2);
    expect(component.emailExistsError).toBeFalse();
  });

  // Submit should emit a clean payload and protect business rules the template cannot enforce.
  it('should trim role before emitting submitted employee data', () => {
    spyOn(component.submitted, 'emit');
    component.newEmployee = validNewEmployee();

    component.onSubmit(formStub(false));

    expect(component.submitted.emit).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      firstName: 'Jane',
      lastName: 'Smith',
      role: 'Engineer',
      discipline: 'Software & DevOps Engineering',
      seniority: 'Engineer'
    }));
  });

  it('should not submit invalid, duplicate, self-reporting, or blank-role employees', () => {
    spyOn(component.submitted, 'emit');
    spyOn(component.notify, 'emit');
    component.newEmployee = validNewEmployee();

    component.onSubmit(formStub(true));
    expect(component.submitted.emit).not.toHaveBeenCalled();

    component.employees = [employee({ email: 'jane.smith@example.com' })];
    component.onSubmit(formStub(false));
    expect(component.emailExistsError).toBeTrue();

    component.employees = [];
    component.emailExistsError = false;
    component.newEmployee.reportsTo = 'Jane Smith';
    component.onSubmit(formStub(false));
    expect(component.notify.emit).toHaveBeenCalledWith({
      message: 'An employee cannot report to themselves.',
      type: 'error'
    });

    component.newEmployee.reportsTo = '';
    component.newEmployee.role = '   ';
    component.onSubmit(formStub(false));
    expect(component.notify.emit).toHaveBeenCalledWith({
      message: 'Position cannot be blank or spaces only.',
      type: 'error'
    });
    expect(component.submitted.emit).not.toHaveBeenCalled();
  });

  // Manager lists exclude archived employees and non-manager contributors.
  it('should compute reporting managers and available team members', () => {
    component.employees = [
      employee({ userId: 'mgr-1', name: 'Active Manager', employeeType: 'Manager' }),
      employee({ userId: 'admin-1', name: 'Active Admin', employeeType: 'Admin' }),
      employee({ userId: 'emp-1', name: 'Contributor', employeeType: 'Employee' }),
      employee({ userId: 'old-1', name: 'Archived Manager', status: 'Archived' as any })
    ];
    component.newEmployee.teamMembers = [{ userId: 'emp-1', name: 'Contributor' }];

    expect(component.reportingManagers.map(e => e.name)).toEqual(['Active Manager', 'Active Admin']);
    expect(component.getAvailableForNewEmployee().map(e => e.userId)).toEqual(['mgr-1', 'admin-1']);
  });

  // Autocomplete flows are important because they create the new manager/team relationships.
  it('should search, keyboard-select, and remove managed team members', () => {
    component.employees = [
      employee({ userId: 'emp-1', name: 'Taylor Employee', employeeType: 'Employee' }),
      employee({ userId: 'emp-2', name: 'Robin Employee', employeeType: 'Employee' })
    ];

    component.onAddMemberFocus();
    expect(component.addMemberOpen).toBeTrue();
    expect(component.addMemberMatches.length).toBe(2);

    component.addMemberSearch = 'taylor';
    component.onAddMemberInput();
    expect(component.addMemberMatches.map(e => e.name)).toEqual(['Taylor Employee']);

    const downEvent = { key: 'ArrowDown', preventDefault: jasmine.createSpy('downPrevent') } as any;
    component.onAddMemberKeyDown(downEvent);
    expect(component.addMemberHighlight).toBe(0);

    const enterEvent = { key: 'Enter', preventDefault: jasmine.createSpy('enterPrevent') } as any;
    component.onAddMemberKeyDown(enterEvent);

    expect(component.newEmployee.teamMembers).toEqual([{ userId: 'emp-1', name: 'Taylor Employee' }]);
    expect(component.addMemberSearch).toBe('');
    expect(component.addMemberOpen).toBeFalse();

    component.removeNewTeamMember({ userId: 'emp-1', name: 'Taylor Employee' });
    expect(component.newEmployee.teamMembers).toEqual([]);
  });

  it('should require confirmation before adding someone who already reports to another manager', () => {
    const linkedEmployee = employee({
      userId: 'emp-1',
      name: 'Linked Employee',
      employeeType: 'Employee',
      reportingToId: 'mgr-1',
      reportsTo: 'Old Manager'
    });

    component.selectAddMember(linkedEmployee);

    expect(component.unlinkConfirmOpen).toBeTrue();
    expect(component.unlinkConfirmEmployee).toBe(linkedEmployee);
    expect(component.newEmployee.teamMembers).toEqual([]);

    component.confirmUnlink();
    expect(component.newEmployee.teamMembers).toEqual([{ userId: 'emp-1', name: 'Linked Employee' }]);
    expect(component.unlinkConfirmOpen).toBeFalse();

    component.selectAddMember(linkedEmployee);
    component.cancelUnlink();
    expect(component.unlinkConfirmEmployee).toBeNull();
    expect(component.unlinkConfirmOpen).toBeFalse();
  });

  it('should search and select reports-to managers', () => {
    const manager = employee({ userId: 'mgr-1', name: 'Alex Manager', employeeType: 'Manager' });
    component.employees = [
      manager,
      employee({ userId: 'emp-1', name: 'Employee Person', employeeType: 'Employee' })
    ];

    component.newEmployee.reportsTo = 'alex';
    component.onAddReportsToInput();
    expect(component.addReportsToMatches).toEqual([manager]);

    component.onAddReportsToFocus();
    expect(component.addReportsToOpen).toBeTrue();

    const downEvent = { key: 'ArrowDown', preventDefault: jasmine.createSpy('downPrevent') } as any;
    component.onAddReportsToKeyDown(downEvent);
    expect(component.addReportsToHighlight).toBe(0);

    const enterEvent = { key: 'Enter', preventDefault: jasmine.createSpy('enterPrevent') } as any;
    component.onAddReportsToKeyDown(enterEvent);
    expect(component.newEmployee.reportsTo).toBe('Alex Manager');
    expect(component.isValidReportsTo).toBeTrue();

    component.clearAddReportsTo();
    expect(component.newEmployee.reportsTo).toBe('');
    expect(component.addReportsToMatches).toEqual([manager]);
  });

  // Small input helpers prevent malformed data from reaching the parent component.
  it('should sanitize names, block manual date typing, and highlight matching text', () => {
    const input = document.createElement('input');
    input.value = 'Ja123ne!';
    input.setSelectionRange(4, 4);
    const model = { firstName: '' };

    component.sanitizeNameField({ target: input } as any, model, 'firstName');

    expect(input.value).toBe('Jane');
    expect(model.firstName).toBe('Jane');

    const blocked = { key: 'a', preventDefault: jasmine.createSpy('preventBlocked') } as any;
    component.preventDateTyping(blocked);
    expect(blocked.preventDefault).toHaveBeenCalled();

    const allowed = { key: 'Tab', preventDefault: jasmine.createSpy('preventAllowed') } as any;
    component.preventDateTyping(allowed);
    expect(allowed.preventDefault).not.toHaveBeenCalled();

    expect(component.highlightMatch('Jane Smith', 'Jane')).toBe('<strong>Jane</strong> Smith');
    expect(component.highlightMatch('Jane Smith', '')).toBe('Jane Smith');
  });

  it('should emit closed when close is called', () => {
    spyOn(component.closed, 'emit');

    component.close();

    expect(component.closed.emit).toHaveBeenCalled();
  });
});
