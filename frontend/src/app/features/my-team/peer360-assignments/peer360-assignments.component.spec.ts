import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { of, throwError } from 'rxjs';

import { Peer360AssignmentsComponent } from './peer360-assignments.component';
import { AuthService } from '../../../core/auth/auth.service';
import { EmployeeService, ReviewCycle } from '../../../services/employees.services';
import { Peer360AssignmentService } from '../../../services/peer360-assignment.service';
import { ReviewCyclesService } from '../../../services/review-cycles.service';
import { User } from '../../../models/employee.model';
import { Review } from '../../../models/review.model';

describe('Peer360AssignmentsComponent', () => {
  let employeeService: jasmine.SpyObj<EmployeeService>;
  let assignmentService: jasmine.SpyObj<Peer360AssignmentService>;
  let cyclesService: jasmine.SpyObj<ReviewCyclesService>;
  let router: jasmine.SpyObj<Router>;

  const subject: User = {
    userId: 'alice', email: 'alice@x.com', firstName: 'Alice', lastName: 'Anderson',
    role: 'EMPLOYEE', position: 'Engineer', status: 'Active', reportingTo: 'manager-1',
  };
  const bob: User = {
    userId: 'bob', email: 'bob@x.com', firstName: 'Bob', lastName: 'Brown',
    role: 'EMPLOYEE', position: 'Engineer', status: 'Active', reportingTo: 'manager-1',
  };
  const charlie: User = {
    userId: 'charlie', email: 'c@x.com', firstName: 'Charlie', lastName: 'Crew',
    role: 'EMPLOYEE', position: 'Engineer', status: 'Active', reportingTo: 'manager-1',
  };
  const archived: User = {
    userId: 'arch', email: 'arch@x.com', firstName: 'Arch', lastName: 'Archived',
    role: 'EMPLOYEE', position: 'Eng', status: 'Archived', reportingTo: 'manager-1',
  };
  const outsider: User = {
    userId: 'eve', email: 'eve@x.com', firstName: 'Eve', lastName: 'Outside',
    role: 'EMPLOYEE', position: 'Engineer', status: 'Active', reportingTo: 'manager-2',
  };

  /** Default cycle has a Peer 360 milestone with templateId tpl-peer-1. */
  function defaultCycle(overrides: Partial<ReviewCycle> = {}): any {
    return {
      cycleId: 'cycle-1',
      cycleTitle: 'FY26 Q1 Review',
      startDate: '2026-01-01',
      dueDate: '2026-03-31',
      status: 'ACTIVE',
      milestones: [
        {
          milestoneId: 'ms-self', name: 'Self review', assignedRoles: ['EMPLOYEE'],
          startDate: '2026-01-01', endDate: '2026-01-15', status: 'OPEN',
          templateSnapshot: { templateId: 'tpl-self-1', templateName: 'Self', reviewType: 'SELF', questions: [] },
        },
        {
          milestoneId: 'ms-peer', name: '360 feedback', assignedRoles: ['EMPLOYEE'],
          startDate: '2026-01-15', endDate: '2026-02-15', status: 'OPEN',
          templateSnapshot: { templateId: 'tpl-peer-1', templateName: 'Peer 360', reviewType: 'PEER_360', questions: [] },
        },
      ],
      ...overrides,
    };
  }

  function cycleWithoutPeer360(): any {
    return {
      cycleId: 'cycle-1',
      cycleTitle: 'FY26 Q1 (no 360)',
      startDate: '2026-01-01',
      dueDate: '2026-03-31',
      status: 'ACTIVE',
      milestones: [
        {
          milestoneId: 'ms-self', name: 'Self review', assignedRoles: ['EMPLOYEE'],
          startDate: '2026-01-01', endDate: '2026-01-15', status: 'OPEN',
          templateSnapshot: { templateId: 'tpl-self-1', templateName: 'Self', reviewType: 'SELF', questions: [] },
        },
      ],
    };
  }

  // Shared setup keeps each test focused on the branch it is validating.
  function buildComponent(opts: {
    assignments?: Review[];
    managerId?: string;
    cycle?: any;
    employees?: User[];
    employeeId?: string;
    cycleId?: string;
    authSub?: string;
    managerReviews?: Review[];
    employeesError?: boolean;
    assignmentsError?: boolean;
    cycleError?: boolean;
    managerReviewsError?: boolean;
  } = {}) {
    employeeService = jasmine.createSpyObj<EmployeeService>('EmployeeService', ['getEmployees', 'getReviewsBySubject']);
    assignmentService = jasmine.createSpyObj<Peer360AssignmentService>('Peer360AssignmentService',
      ['list', 'create', 'remove']);
    cyclesService = jasmine.createSpyObj<ReviewCyclesService>('ReviewCyclesService', ['getCycle']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);

    employeeService.getEmployees.and.returnValue(
      opts.employeesError ? throwError(() => new Error('employees failed')) : of(opts.employees ?? [subject, bob, charlie, archived, outsider])
    );
    employeeService.getReviewsBySubject.and.returnValue(
      opts.managerReviewsError ? throwError(() => new Error('manager reviews failed')) : of(opts.managerReviews ?? [])
    );
    assignmentService.list.and.returnValue(
      opts.assignmentsError ? throwError(() => new Error('assignments failed')) : of(opts.assignments ?? [])
    );
    cyclesService.getCycle.and.returnValue(
      opts.cycleError ? throwError(() => new Error('cycle failed')) : of(opts.cycle ?? defaultCycle())
    );

    TestBed.configureTestingModule({
      imports: [Peer360AssignmentsComponent],
      providers: [
        { provide: EmployeeService, useValue: employeeService },
        { provide: Peer360AssignmentService, useValue: assignmentService },
        { provide: ReviewCyclesService, useValue: cyclesService },
        { provide: AuthService, useValue: { getTokenPayload: () => ({ sub: opts.authSub ?? 'manager-1' }) } },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: convertToParamMap({
                employeeId: opts.employeeId ?? 'alice',
                cycleId: opts.cycleId ?? 'cycle-1',
              }),
              queryParamMap: convertToParamMap(opts.managerId ? { managerId: opts.managerId } : {}),
            },
          },
        },
        { provide: Router, useValue: router },
      ],
    });

    const fixture = TestBed.createComponent(Peer360AssignmentsComponent);
    fixture.detectChanges();
    return { fixture, component: fixture.componentInstance };
  }

  // Baseline rendering and template resolution cases.
  it('renders the cycle title (not the cycleId)', () => {
    const { component, fixture } = buildComponent();
    expect(component.cycle?.cycleTitle).toBe('FY26 Q1 Review');
    expect(fixture.nativeElement.textContent).toContain('FY26 Q1 Review');
  });

  it('resolves the PEER_360 template from the cycle\'s milestone snapshot', () => {
    const { component } = buildComponent();
    expect(component.peer360Template).not.toBeNull();
    expect(component.peer360Template!.templateId).toBe('tpl-peer-1');
    expect(component.peer360Template!.templateName).toBe('Peer 360');
    expect(component.peer360Template!.milestoneName).toBe('360 feedback');
  });

  it('blocks assignment when the cycle has no PEER_360 milestone', () => {
    const { component, fixture } = buildComponent({ cycle: cycleWithoutPeer360() });
    expect(component.peer360Template).toBeNull();
    expect(component.canAssign()).toBeFalse();
    expect(fixture.nativeElement.textContent).toContain("doesn't have a Peer 360 milestone");
  });

  // Eligibility rules: remove the subject, inactive users, existing reviewers,
  // and optionally users outside the manager's direct team.
  it('builds eligible list excluding the subject and archived users', () => {
    const { component } = buildComponent();
    const ids = component.eligible.map(e => e.user.userId);
    expect(ids).toContain('bob');
    expect(ids).toContain('charlie');
    expect(ids).not.toContain('alice');
    expect(ids).not.toContain('arch');
  });

  it('default scope: excludes employees from outside the manager\'s team', () => {
    const { component } = buildComponent();
    const ids = component.eligible.map(e => e.user.userId);
    expect(component.showOutsideTeam).toBeFalse();
    expect(ids).toContain('bob');
    expect(ids).toContain('charlie');
    expect(ids).not.toContain('eve');
  });

  it('toggling "Show outside team" expands the eligible list to the full company', () => {
    const { component } = buildComponent();
    component.showOutsideTeam = true;
    component.onScopeToggle();
    const ids = component.eligible.map(e => e.user.userId);
    expect(ids).toContain('bob');
    expect(ids).toContain('charlie');
    expect(ids).toContain('eve');
    expect(ids).not.toContain('alice');
    expect(ids).not.toContain('arch');
  });

  it('hides authors that already have an assignment', () => {
    const existing: Review = {
      reviewId: 'rev-bob', cycleId: 'cycle-1', userId: 'bob', subjectUserId: 'alice',
      authorUserId: 'bob', reviewType: 'PEER_360', status: 'NOT_STARTED', answers: [],
    };
    const { component } = buildComponent({ assignments: [existing] });
    expect(component.assignments.length).toBe(1);
    const ids = component.eligible.map(e => e.user.userId);
    expect(ids).not.toContain('bob');
    expect(ids).toContain('charlie');
  });

  it('uses the cycle-resolved templateId when assigning', () => {
    const { component } = buildComponent();
    component.eligible.find(e => e.user.userId === 'bob')!.selected = true;
    component.eligible.find(e => e.user.userId === 'charlie')!.selected = true;

    const created: Review[] = [
      { reviewId: 'rev-bob', cycleId: 'cycle-1', userId: 'bob', subjectUserId: 'alice', authorUserId: 'bob', reviewType: 'PEER_360', status: 'NOT_STARTED', answers: [] },
      { reviewId: 'rev-c',   cycleId: 'cycle-1', userId: 'c',   subjectUserId: 'alice', authorUserId: 'charlie', reviewType: 'PEER_360', status: 'NOT_STARTED', answers: [] },
    ];
    assignmentService.create.and.returnValue(of({ created, skipped: [], all: created }));

    component.assignSelected();

    expect(assignmentService.create).toHaveBeenCalledWith('cycle-1', {
      subjectUserId: 'alice',
      authorUserIds: jasmine.arrayContaining(['bob', 'charlie']),
      templateId: 'tpl-peer-1',
    });
    expect(component.assignments.length).toBe(2);
  });

  // Removal can only happen while a peer review has not started.
  it('disables remove button for non-NOT_STARTED rows', () => {
    const inProgress: Review = {
      reviewId: 'rev-bob', cycleId: 'cycle-1', userId: 'bob', subjectUserId: 'alice',
      authorUserId: 'bob', reviewType: 'PEER_360', status: 'IN_PROGRESS', answers: [],
    };
    const { component } = buildComponent({ assignments: [inProgress] });
    expect(component.canRemove(inProgress)).toBeFalse();
  });

  it('surfaces a 409 error when removing an in-progress row', () => {
    const row: Review = {
      reviewId: 'rev-bob', cycleId: 'cycle-1', userId: 'bob', subjectUserId: 'alice',
      authorUserId: 'bob', reviewType: 'PEER_360', status: 'NOT_STARTED', answers: [],
    };
    const { component } = buildComponent({ assignments: [row] });
    assignmentService.remove.and.returnValue(throwError(() => ({ status: 409 })));

    component.removeAssignment(row);

    expect(component.errorMessage.toLowerCase()).toContain('started');
  });

  // Startup fallback branches keep the page stable when route or API data is incomplete.
  it('stops early when required route params are missing', () => {
    const { component } = buildComponent({ employeeId: '', cycleId: 'cycle-1' });

    expect(component.loading).toBeFalse();
    expect(component.errorMessage).toContain('Missing employeeId or cycleId');
    expect(employeeService.getEmployees).not.toHaveBeenCalled();
    expect(assignmentService.list).not.toHaveBeenCalled();
  });

  it('falls back to empty loaded state when startup requests fail', () => {
    const { component } = buildComponent({
      employeesError: true,
      assignmentsError: true,
      cycleError: true,
      managerReviewsError: true,
    });

    expect(component.loading).toBeFalse();
    expect(component.subject).toBeNull();
    expect(component.assignments).toEqual([]);
    expect(component.cycle).toBeNull();
    expect(component.peer360Template).toBeNull();
    expect(component.eligible).toEqual([]);
  });

  it('uses subject reportingTo as managerId when URL managerId is absent', () => {
    const { component } = buildComponent({ authSub: '' });

    expect(component.managerId).toBe('manager-1');
  });

  // Submitted manager reviews lock assignment controls for this subject/cycle.
  it('locks assignment state once the manager review is completed or published', () => {
    const managerReview: Review = {
      reviewId: 'mgr-1',
      cycleId: 'cycle-1',
      userId: 'manager-1',
      subjectUserId: 'alice',
      authorUserId: 'manager-1',
      reviewType: 'MANAGER',
      status: 'PUBLISHED',
      answers: [],
    };

    const { component } = buildComponent({ managerReviews: [managerReview] });

    expect(component.selfReviewSubmitted).toBeTrue();
  });

  // Legacy cycle data can expose Peer 360 as "PEER 360" and may omit names.
  it('supports PEER 360 snapshots with fallback template and milestone names', () => {
    const cycle = defaultCycle({
      milestones: [
        {
          milestoneId: 'ms-peer',
          name: undefined,
          assignedRoles: ['EMPLOYEE'],
          startDate: '2026-01-01',
          endDate: '2026-02-01',
          status: 'OPEN',
          templateSnapshot: { templateId: 'tpl-peer-alt', title: 'Legacy 360', reviewType: 'PEER 360', questions: [] },
        },
      ],
    } as any);

    const { component } = buildComponent({ cycle });

    expect(component.peer360Template).toEqual({
      templateId: 'tpl-peer-alt',
      templateName: 'Legacy 360',
      milestoneName: 'Peer 360',
    });
  });

  it('skips peer360 snapshots without a templateId before finding a valid one', () => {
    const cycle = defaultCycle({
      milestones: [
        {
          milestoneId: 'bad-peer',
          name: 'Bad Peer',
          assignedRoles: ['EMPLOYEE'],
          startDate: '2026-01-01',
          endDate: '2026-02-01',
          status: 'OPEN',
          templateSnapshot: { reviewType: 'PEER_360', questions: [] },
        },
        {
          milestoneId: 'good-peer',
          name: 'Good Peer',
          assignedRoles: ['EMPLOYEE'],
          startDate: '2026-01-01',
          endDate: '2026-02-01',
          status: 'OPEN',
          templateSnapshot: { templateId: 'tpl-good', reviewType: 'PEER_360', questions: [] },
        },
      ],
    } as any);

    const { component } = buildComponent({ cycle });

    expect(component.peer360Template?.templateId).toBe('tpl-good');
    expect(component.peer360Template?.templateName).toBe('Peer 360 template');
  });

  // Grouping/search branches include manager-name matches and collapsed state.
  it('groups eligible peers, filters search by manager name, and toggles collapse state', () => {
    const managerTwo: User = {
      userId: 'manager-2', email: 'm2@x.com', firstName: 'Morgan', lastName: 'Manager',
      role: 'MANAGER', position: 'Manager', status: 'Archived', reportingTo: null,
    };
    const noManager: User = {
      userId: 'nomgr', email: 'n@x.com', firstName: 'No', lastName: 'Manager',
      role: 'EMPLOYEE', position: 'Engineer', status: 'Active', reportingTo: null,
    };
    const { component } = buildComponent({
      employees: [subject, bob, charlie, outsider, managerTwo, noManager],
    });

    component.showOutsideTeam = true;
    component.onScopeToggle();
    expect(component.groupedEligible.map(g => g.managerName)).toContain('No Manager');
    expect(component.groupedEligible.map(g => g.managerName)).toContain('Morgan Manager');

    component.searchQuery = 'morgan';
    component.onSearchChange();
    expect(component.groupedEligible.length).toBe(1);
    expect(component.groupedEligible[0].peers.map(p => p.user.userId)).toEqual(['eve']);

    component.toggleGroup('manager-2');
    expect(component.isGroupCollapsed('manager-2')).toBeTrue();
    component.toggleGroup('manager-2');
    expect(component.isGroupCollapsed('manager-2')).toBeFalse();
    expect(component.groupSelectedCount([{ user: bob, selected: true }, { user: charlie, selected: false }])).toBe(1);
  });

  // Assignment guard and API response branches.
  it('guards assignment when nothing can be assigned', () => {
    const { component } = buildComponent();

    component.assignSelected();

    expect(assignmentService.create).not.toHaveBeenCalled();
  });

  it('uses numeric user id fallback and singular success message when assigning one reviewer', () => {
    const numericPeer = {
      id: 42, email: 'num@x.com', firstName: 'Numeric', lastName: 'Peer',
      role: 'EMPLOYEE', position: 'Engineer', status: 'Active', reportingTo: 'manager-1',
    } as User;
    const { component } = buildComponent({ employees: [subject, numericPeer] });
    component.eligible[0].selected = true;
    const created: Review[] = [
      { reviewId: 'rev-num', cycleId: 'cycle-1', userId: '42', subjectUserId: 'alice', authorUserId: '42', reviewType: 'PEER_360', status: 'NOT_STARTED', answers: [] },
    ];
    assignmentService.create.and.returnValue(of({ created, skipped: [], all: created }));

    component.assignSelected();

    expect(assignmentService.create).toHaveBeenCalledWith('cycle-1', {
      subjectUserId: 'alice',
      authorUserIds: ['42'],
      templateId: 'tpl-peer-1',
    });
    expect(component.successMessage).toBe('Successfully assigned 1 peer reviewer.');
  });

  it('surfaces assignment errors and resets saving state', () => {
    const { component } = buildComponent();
    component.eligible.find(e => e.user.userId === 'bob')!.selected = true;
    assignmentService.create.and.returnValue(throwError(() => ({ error: { message: 'No permission' } })));

    component.assignSelected();

    expect(component.saving).toBeFalse();
    expect(component.errorMessage).toBe('No permission');
  });

  // Successful and guarded remove paths refresh the local assignment/eligible state.
  it('removes an assignment and recomputes eligibility', () => {
    const row: Review = {
      reviewId: 'rev-bob', cycleId: 'cycle-1', userId: 'bob', subjectUserId: 'alice',
      authorUserId: 'bob', reviewType: 'PEER_360', status: 'NOT_STARTED', answers: [],
    };
    const { component } = buildComponent({ assignments: [row] });
    assignmentService.remove.and.returnValue(of(undefined));

    component.removeAssignment(row);

    expect(assignmentService.remove).toHaveBeenCalledOnceWith('cycle-1', 'rev-bob');
    expect(component.assignments).toEqual([]);
    expect(component.eligible.map(e => e.user.userId)).toContain('bob');
  });

  it('ignores remove requests without a review id or removable status', () => {
    const { component } = buildComponent();

    component.removeAssignment({ cycleId: 'cycle-1', userId: 'bob', reviewType: 'PEER_360', status: 'NOT_STARTED', answers: [] });
    component.removeAssignment({ reviewId: 'rev-done', cycleId: 'cycle-1', userId: 'bob', reviewType: 'PEER_360', status: 'COMPLETED', answers: [] });

    expect(assignmentService.remove).not.toHaveBeenCalled();
  });

  it('surfaces non-conflict remove errors', () => {
    const row: Review = {
      reviewId: 'rev-bob', cycleId: 'cycle-1', userId: 'bob', subjectUserId: 'alice',
      authorUserId: 'bob', reviewType: 'PEER_360', status: 'NOT_STARTED', answers: [],
    };
    const { component } = buildComponent({ assignments: [row] });
    assignmentService.remove.and.returnValue(throwError(() => ({ error: { message: 'Remove failed' } })));

    component.removeAssignment(row);

    expect(component.errorMessage).toBe('Remove failed');
  });

  // Small display helpers are covered because the template depends on their fallback output.
  it('formats author names, initials, status labels/classes, and back navigation', () => {
    const { component } = buildComponent();

    expect(component.authorName(undefined)).toBe('—');
    expect(component.authorName('bob')).toBe('Bob Brown');
    expect(component.authorName('unknown-user')).toBe('unknown-user');
    expect(component.initials(subject)).toBe('AA');
    expect(component.initials({ ...subject, firstName: '', lastName: '' })).toBe('');
    expect(component.statusLabel('IN_PROGRESS')).toBe('In Progress');
    expect(component.statusLabel('custom')).toBe('custom');
    expect(component.statusLabel(undefined)).toBe('Not started');
    expect(component.statusClass('IN_PROGRESS')).toBe('in-progress');
    expect(component.statusClass(undefined)).toBe('not-started');

    component.back();
    expect(router.navigate).toHaveBeenCalledOnceWith(['/my-team']);
  });
});
