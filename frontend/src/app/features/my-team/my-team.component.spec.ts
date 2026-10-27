import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { of, throwError } from 'rxjs';

import { MyTeamComponent } from './my-team.component';
import { AuthService } from '../../core/auth/auth.service';
import { EmployeeService, ReviewCycle } from '../../services/employees.services';
import { NotificationService } from '../../services/notification.service';
import { User } from '../../models/employee.model';
import { Review } from '../../models/review.model';

describe('MyTeamComponent', () => {
  let employeeService: jasmine.SpyObj<EmployeeService>;
  let notificationService: jasmine.SpyObj<NotificationService>;
  let router: jasmine.SpyObj<Router>;

  const manager: User = {
    userId: 'manager-1', email: 'manager@x.com', firstName: 'Mina', lastName: 'Manager',
    role: 'MANAGER', position: 'Engineering Manager', status: 'Active', reportingTo: null,
  };
  const alice: User = {
    userId: 'alice', email: 'alice@x.com', firstName: 'Alice', lastName: 'Anderson',
    role: 'EMPLOYEE', position: 'Engineer', status: 'Active', reportingTo: 'manager-1',
  };
  const bob: User = {
    userId: 'bob', email: 'bob@x.com', firstName: 'Bob', lastName: 'Brown',
    role: 'EMPLOYEE', position: 'Consultant', status: 'Active', reportingTo: 'manager-1',
  };
  const archived: User = {
    userId: 'arch', email: 'arch@x.com', firstName: 'Arch', lastName: 'Archived',
    role: 'EMPLOYEE', position: 'Engineer', status: 'Archived', reportingTo: 'manager-1',
  };

  function cycle(id: string, title: string, updatedAt: string, status: ReviewCycle['status'] = 'ACTIVE'): ReviewCycle {
    return {
      cycleId: id,
      cycleTitle: title,
      startDate: '2026-01-01',
      dueDate: '2026-03-31',
      status,
      updatedAt,
      milestones: [
        {
          milestoneId: `${id}-manager`,
          name: 'Manager review',
          assignedRoles: ['MANAGER'],
          startDate: '2026-02-01',
          endDate: '2026-02-28',
          status: 'OPEN',
          templateSnapshot: { templateId: `${id}-tpl`, questions: [] },
        },
      ],
    };
  }

  function review(partial: Partial<Review>): Review {
    return {
      reviewId: 'review-1',
      cycleId: 'cycle-new',
      userId: partial.authorUserId ?? partial.subjectUserId ?? 'alice',
      subjectUserId: 'alice',
      authorUserId: 'alice',
      reviewType: 'SELF',
      status: 'NOT_STARTED',
      answers: [],
      ...partial,
    };
  }

  function buildComponent(opts: {
    team?: User[];
    employees?: User[] | null;
    cycles?: ReviewCycle[] | null;
    cycleReviews?: Review[];
    subjectReviews?: Review[];
    teamError?: boolean;
    cyclesError?: boolean;
    employeesError?: boolean;
    cycleReviewsError?: boolean;
    subjectReviewsError?: boolean;
    tokenPayload?: { sub?: string } | null;
  } = {}) {
    const tokenPayload = Object.prototype.hasOwnProperty.call(opts, 'tokenPayload')
      ? opts.tokenPayload
      : { sub: 'manager-1' };
    employeeService = jasmine.createSpyObj<EmployeeService>('EmployeeService', [
      'getMyTeam', 'getCycles', 'getEmployees', 'getReviewsByCycle', 'getReviewsBySubject',
    ]);
    notificationService = jasmine.createSpyObj<NotificationService>('NotificationService', [
      'sendManualReminder', 'getJobStatus',
    ]);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);

    employeeService.getMyTeam.and.returnValue(
      opts.teamError
        ? throwError(() => new Error('team failed'))
        : of(Object.prototype.hasOwnProperty.call(opts, 'team') ? opts.team as any : [alice, bob, archived])
    );
    employeeService.getCycles.and.returnValue(
      opts.cyclesError
        ? throwError(() => new Error('cycles failed'))
        : of(Object.prototype.hasOwnProperty.call(opts, 'cycles') ? opts.cycles as any : [
            cycle('cycle-old', 'FY26 Q1 Review', '2026-01-01T00:00:00Z'),
            cycle('cycle-new', 'FY26 Q2 Review', '2026-04-01T00:00:00Z'),
            cycle('cycle-done', 'FY25 Wrap', '2025-12-01T00:00:00Z', 'COMPLETED'),
          ])
    );
    employeeService.getEmployees.and.returnValue(
      opts.employeesError
        ? throwError(() => new Error('employees failed'))
        : of(Object.prototype.hasOwnProperty.call(opts, 'employees') ? opts.employees as any : [manager, alice, bob, archived])
    );
    employeeService.getReviewsByCycle.and.returnValue(
      opts.cycleReviewsError ? throwError(() => new Error('reviews failed')) : of(opts.cycleReviews ?? [])
    );
    employeeService.getReviewsBySubject.and.returnValue(
      opts.subjectReviewsError ? throwError(() => new Error('subject reviews failed')) : of(opts.subjectReviews ?? [])
    );
    notificationService.sendManualReminder.and.returnValue(of({ created: [], skipped: [] }));
    notificationService.getJobStatus.and.returnValue(of({ status: 'PENDING', materialized: true }));

    TestBed.configureTestingModule({
      imports: [MyTeamComponent],
      providers: [
        { provide: EmployeeService, useValue: employeeService },
        { provide: NotificationService, useValue: notificationService },
        { provide: AuthService, useValue: { getTokenPayload: () => tokenPayload } },
        { provide: Router, useValue: router },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });

    const fixture = TestBed.createComponent(MyTeamComponent);
    fixture.detectChanges();
    return { fixture, component: fixture.componentInstance };
  }

  it('loads only active team members and defaults to the most recently updated active cycle', () => {
    const { component, fixture } = buildComponent();

    expect(employeeService.getMyTeam).toHaveBeenCalledOnceWith('manager-1');
    expect(component.members.map(m => m.userId)).toEqual(['alice', 'bob']);
    expect(component.availableActiveCycles.map(c => c.cycleId)).toEqual(['cycle-new', 'cycle-old']);
    expect(component.activeCycle?.cycleTitle).toBe('FY26 Q2 Review');
    expect(component.selectedCycleId).toBe('cycle-new');
    expect(employeeService.getReviewsByCycle).toHaveBeenCalledOnceWith('cycle-new');
    expect(fixture.nativeElement.textContent).toContain('FY26 Q2 Review');
  });

  it('indexes self, manager, and peer360 reviews for the selected cycle', () => {
    const completedManagerReview = review({
      reviewId: 'mgr-alice',
      subjectUserId: 'alice',
      authorUserId: 'manager-1',
      reviewType: 'MANAGER',
      status: 'COMPLETED',
      overallRating: 'Exceeds Expectations',
      submittedAt: '2026-03-01',
    });
    const { component } = buildComponent({
      cycleReviews: [
        review({ reviewId: 'self-alice', subjectUserId: 'alice', reviewType: 'SELF', status: 'COMPLETED' }),
        completedManagerReview,
        review({ reviewId: 'peer-1', subjectUserId: 'alice', authorUserId: 'bob', reviewType: 'PEER_360', status: 'COMPLETED' }),
        review({ reviewId: 'peer-2', subjectUserId: 'alice', authorUserId: 'charlie', reviewType: 'PEER_360', status: 'IN_PROGRESS' }),
      ],
    });

    expect(component.getSelfReviewStatus(alice)).toBe('COMPLETED');
    expect(component.getManagerReviewStatus(alice)).toBe('COMPLETED');
    expect(component.managerReviewCompleted(alice)).toBeTrue();
    expect(component.peerReviewSummary(alice)).toBe('1/2 completed');
    expect(component.overallRating(alice)).toBe('Exceeds Expectations');
    expect(component.completedMembers).toEqual([alice]);
  });

  it('changes cycles and reloads review state from the new cycle', () => {
    const cycles = [
      cycle('cycle-old', 'FY26 Q1 Review', '2026-01-01T00:00:00Z'),
      cycle('cycle-new', 'FY26 Q2 Review', '2026-04-01T00:00:00Z'),
    ];
    const { component } = buildComponent({
      cycles,
      cycleReviews: [
        review({ reviewId: 'self-alice', subjectUserId: 'alice', reviewType: 'SELF', status: 'COMPLETED' }),
      ],
    });
    employeeService.getReviewsByCycle.calls.reset();
    employeeService.getReviewsByCycle.and.returnValue(of([
      review({ reviewId: 'self-bob', cycleId: 'cycle-old', subjectUserId: 'bob', reviewType: 'SELF', status: 'IN_PROGRESS' }),
    ]));

    component.onCycleChange('cycle-old');

    expect(component.selectedCycleId).toBe('cycle-old');
    expect(component.activeCycle?.cycleTitle).toBe('FY26 Q1 Review');
    expect(component.getSelfReviewStatus(alice)).toBe('NOT_STARTED');
    expect(component.getSelfReviewStatus(bob)).toBe('IN_PROGRESS');
    expect(employeeService.getReviewsByCycle).toHaveBeenCalledOnceWith('cycle-old');
  });

  it('filters defensively when optional employee fields are missing', () => {
    const legacyUser = {
      userId: 'legacy', email: undefined, firstName: 'Legacy', lastName: 'User',
      role: 'EMPLOYEE', position: undefined, status: 'Active', reportingTo: 'manager-1',
    } as unknown as User;
    const { component } = buildComponent({ team: [legacyUser, alice] });

    component.searchQuery = 'legacy';
    component.applyFilters();

    expect(component.filtered).toEqual([legacyUser]);

    component.searchQuery = null as any;
    component.applyFilters();
    expect(component.filtered.length).toBe(2);

    component.members = [{ ...legacyUser, firstName: undefined, lastName: undefined } as any];
    component.searchQuery = '';
    component.applyFilters();
    expect(component.filtered.length).toBe(1);
  });

  it('navigates to manager review with the selected cycle and manager milestone', () => {
    const existing = review({
      reviewId: 'mgr-alice',
      subjectUserId: 'alice',
      authorUserId: 'manager-1',
      reviewType: 'MANAGER',
      status: 'IN_PROGRESS',
    });
    const { component } = buildComponent({ cycleReviews: [existing] });

    component.startReview(alice);

    expect(router.navigate).toHaveBeenCalledOnceWith(['/my-team/review'], {
      queryParams: {
        cycleId: 'cycle-new',
        milestoneId: 'cycle-new-manager',
        subjectUserId: 'alice',
        reviewId: 'mgr-alice',
        viewOnly: 'false',
        from: 'my-team',
      },
    });
  });

  it('navigates completed manager reviews in view-only mode', () => {
    const { component } = buildComponent({
      cycleReviews: [
        review({
          reviewId: 'mgr-alice',
          subjectUserId: 'alice',
          authorUserId: 'manager-1',
          reviewType: 'MANAGER',
          status: 'PUBLISHED',
        }),
      ],
    });

    component.startReview(alice);

    expect(router.navigate.calls.mostRecent().args[1]).toEqual({
      queryParams: jasmine.objectContaining({
        reviewId: 'mgr-alice',
        viewOnly: 'true',
      }),
    });
  });

  it('navigates to Peer 360 assignment for the active cycle and manager', () => {
    const { component } = buildComponent();

    component.managePeer360(alice);

    expect(router.navigate).toHaveBeenCalledOnceWith(
      ['/my-team', 'alice', 'cycle', 'cycle-new', 'peer360'],
      { queryParams: { managerId: 'manager-1' } }
    );
  });

  it('opens details with only this manager\'s manager reviews and latest completed rating', () => {
    const { component } = buildComponent({
      subjectReviews: [
        review({
          reviewId: 'old-mgr',
          cycleId: 'cycle-old',
          subjectUserId: 'alice',
          authorUserId: 'manager-1',
          reviewType: 'MANAGER',
          status: 'COMPLETED',
          submittedAt: '2026-02-01',
          overallRating: 'Meets Expectations',
        }),
        review({
          reviewId: 'other-manager',
          subjectUserId: 'alice',
          authorUserId: 'manager-2',
          reviewType: 'MANAGER',
          status: 'COMPLETED',
          submittedAt: '2026-04-01',
          overallRating: 'Exceeds Expectations',
        }),
        review({
          reviewId: 'new-draft',
          cycleId: 'cycle-new',
          subjectUserId: 'alice',
          authorUserId: 'manager-1',
          reviewType: 'MANAGER',
          status: 'DRAFT',
          submittedAt: '2026-03-01',
        }),
      ],
    });

    component.openDetails(alice);

    expect(employeeService.getReviewsBySubject).toHaveBeenCalledOnceWith('alice');
    expect(component.detailsItem?.totalReviews).toBe(2);
    expect(component.detailsItem?.reviews.map(item => item.review.reviewId)).toEqual(['new-draft', 'old-mgr']);
    expect(component.detailsItem?.latestRating).toBe('Meets Expectations');
    expect(component.detailsItem?.loading).toBeFalse();
  });

  it('falls back to an empty team when the team request fails', () => {
    const { component } = buildComponent({ teamError: true });

    expect(component.loading).toBeFalse();
    expect(component.members).toEqual([]);
    expect(component.filtered).toEqual([]);
  });

  it('handles missing token payload, failed lookups, and no active cycle', () => {
    const { component } = buildComponent({
      tokenPayload: null,
      cyclesError: true,
      employeesError: true,
      team: [alice],
    });

    expect(employeeService.getMyTeam).toHaveBeenCalledOnceWith('');
    expect(component.allEmployees).toEqual([]);
    expect(component.availableActiveCycles).toEqual([]);
    expect(component.activeCycle).toBeNull();
    expect(component.selectedCycleId).toBe('');
    expect(component.loading).toBeFalse();
    expect(employeeService.getReviewsByCycle).not.toHaveBeenCalled();
  });

  it('uses createdAt as the active-cycle sort fallback', () => {
    const older = { ...cycle('older', 'Older', '', 'ACTIVE'), updatedAt: undefined, createdAt: '2026-01-01' };
    const newer = { ...cycle('newer', 'Newer', '', 'ACTIVE'), updatedAt: undefined, createdAt: '2026-02-01' };

    const { component } = buildComponent({ cycles: [older, newer] });

    expect(component.availableActiveCycles.map(c => c.cycleId)).toEqual(['newer', 'older']);
    expect(component.activeCycle?.cycleId).toBe('newer');
  });

  it('handles null service payloads and empty date sort fallbacks', () => {
    const blankA = { ...cycle('blank-a', 'Blank A', '', 'ACTIVE'), updatedAt: undefined, createdAt: undefined };
    const blankB = { ...cycle('blank-b', 'Blank B', '', 'ACTIVE'), updatedAt: undefined, createdAt: undefined };

    const { component } = buildComponent({
      team: null as any,
      cycles: [blankA, blankB],
      cycleReviews: [
        review({ reviewId: 'self-user-fallback', subjectUserId: undefined, userId: 'alice', reviewType: 'SELF', status: 'COMPLETED' }),
        review({ reviewId: 'manager-empty-subject', subjectUserId: undefined, authorUserId: 'manager-1', reviewType: 'MANAGER', status: 'COMPLETED' }),
      ],
    });

    expect(component.members).toEqual([]);
    expect(component.allEmployees.length).toBe(4);
    expect(component.availableActiveCycles.map(c => c.cycleId)).toEqual(['blank-a', 'blank-b']);
    expect(component.getSelfReviewStatus({ ...alice, userId: undefined })).toBe('NOT_STARTED');
    expect(component.getSelfReviewStatus(alice)).toBe('COMPLETED');
    expect(component.managerDrafts.size).toBe(0);
  });

  it('handles null cycle and employee payload fallbacks', () => {
    const { component } = buildComponent({
      cycles: null,
      employees: null,
      team: null as any,
    });

    expect(component.allEmployees).toEqual([]);
    expect(component.availableActiveCycles).toEqual([]);
    expect(component.activeCycle).toBeNull();
    expect(component.loading).toBeFalse();
  });

  it('clears cycle state when changing to an unknown cycle id', () => {
    const { component } = buildComponent();
    employeeService.getReviewsByCycle.calls.reset();

    component.onCycleChange('missing-cycle');

    expect(component.activeCycle).toBeNull();
    expect(component.managerMilestone).toBeNull();
    expect(component.selfReviewStatus.size).toBe(0);
    expect(component.managerDrafts.size).toBe(0);
    expect(employeeService.getReviewsByCycle).not.toHaveBeenCalled();
  });

  it('falls back to empty review state when cycle review loading fails', () => {
    const { component } = buildComponent({ cycleReviewsError: true });

    expect(component.loading).toBeFalse();
    expect(component.selfReviewStatus.size).toBe(0);
    expect(component.managerDrafts.size).toBe(0);
    expect(component.peerReviews.size).toBe(0);
  });

  it('supports status filters and all sort branches', () => {
    const { component } = buildComponent({
      cycleReviews: [
        review({ reviewId: 'self-alice', subjectUserId: 'alice', reviewType: 'SELF', status: 'COMPLETED' }),
        review({ reviewId: 'self-bob', subjectUserId: 'bob', reviewType: 'SELF', status: 'DRAFT' }),
        review({ reviewId: 'mgr-bob', subjectUserId: 'bob', authorUserId: 'manager-1', reviewType: 'MANAGER', status: 'IN_PROGRESS' }),
      ],
    });

    component.searchQuery = '';
    component.selfStatusFilter = 'DRAFT';
    component.applyFilters();
    expect(component.filtered).toEqual([bob]);

    component.selfStatusFilter = '';
    component.statusFilter = 'Active';
    component.applyFilters();
    expect(component.filtered.length).toBe(2);
    component.statusFilter = '';

    component.sortBy('name');
    expect(component.sortColumn).toBe('name');
    expect(component.sortDir).toBe(-1);
    expect(component.filtered.map(m => m.userId)).toEqual(['bob', 'alice']);

    component.sortBy('selfStatus');
    expect(component.sortColumn).toBe('selfStatus');
    expect(component.sortDir).toBe(1);
    expect(component.filtered.map(m => m.userId)).toEqual(['bob', 'alice']);

    component.sortBy('managerStatus');
    expect(component.filtered.map(m => m.userId)).toEqual(['alice', 'bob']);

    component.sortColumn = 'unknown';
    component.applyFilters();
    expect(component.filtered.length).toBe(2);

    component.sortBy('unknown');
    expect(component.sortDir).toBe(-1);
  });

  it('exposes dashboard counts and readiness helpers', () => {
    const { component } = buildComponent({
      cycleReviews: [
        review({ reviewId: 'self-alice', subjectUserId: 'alice', reviewType: 'SELF', status: 'PUBLISHED' }),
        review({ reviewId: 'self-bob', subjectUserId: 'bob', reviewType: 'SELF', status: 'COMPLETED' }),
        review({ reviewId: 'mgr-bob', subjectUserId: 'bob', authorUserId: 'manager-1', reviewType: 'MANAGER', status: 'COMPLETED' }),
      ],
    });

    expect(component.totalMembers).toBe(2);
    expect(component.selfSubmittedCount).toBe(2);
    expect(component.managerDoneCount).toBe(1);
    expect(component.employeeSubmitted(alice)).toBeTrue();
    expect(component.isReadyForReview(alice)).toBeTrue();
    expect(component.readyCount).toBe(1);
    expect(component.reviewButtonLabel(alice)).toBe('Start Review');
    expect(component.reviewButtonLabel(bob)).toBe('View Review');
  });

  it('returns continue label and in-progress manager status when a draft exists', () => {
    const { component } = buildComponent({
      cycleReviews: [
        review({ reviewId: 'mgr-alice', subjectUserId: 'alice', authorUserId: 'manager-1', reviewType: 'MANAGER', status: 'DRAFT' }),
      ],
    });

    expect(component.hasManagerDraft(alice)).toBeTrue();
    expect(component.reviewButtonLabel(alice)).toBe('Continue Review');
    expect(component.humanManagerStatusLabel(alice)).toBe('In Progress');
    expect(component.managerStatusClass(alice)).toBe('in-progress');
  });

  it('formats manager and self status labels/classes', () => {
    const { component } = buildComponent({
      cycleReviews: [
        review({ reviewId: 'self-alice', subjectUserId: 'alice', reviewType: 'SELF', status: 'IN_PROGRESS' }),
        review({ reviewId: 'mgr-alice', subjectUserId: 'alice', authorUserId: 'manager-1', reviewType: 'MANAGER', status: 'PUBLISHED' }),
      ],
    });

    expect(component.managerStatusLabel(alice)).toBe('Published');
    expect(component.managerStatusClass(alice)).toBe('completed');
    expect(component.selfStatusLabel(alice)).toBe('In Progress');
    expect(component.selfStatusClass(alice)).toBe('in-progress');
    expect(component.managerStatusLabel({ ...alice, userId: 'unknown' })).toBe('Not Started');
    expect(component.managerStatusClass({ ...alice, userId: 'unknown' })).toBe('awaiting-self-review');

    component.managerDrafts.set('custom', review({ subjectUserId: 'custom', authorUserId: 'manager-1', reviewType: 'MANAGER', status: 'CUSTOM_STATUS' as any }));
    component.selfReviewStatus.set('custom', 'CUSTOM_SELF' as any);
    const custom = { ...alice, userId: 'custom' };
    expect(component.managerStatusLabel(custom)).toBe('CUSTOM_STATUS');
    expect(component.selfStatusLabel(custom)).toBe('CUSTOM_SELF');
  });

  it('reports completed peer review summaries', () => {
    const { component } = buildComponent({
      cycleReviews: [
        review({ reviewId: 'peer-1', subjectUserId: 'alice', authorUserId: 'bob', reviewType: 'PEER_360', status: 'COMPLETED' }),
        review({ reviewId: 'peer-2', subjectUserId: 'alice', authorUserId: 'charlie', reviewType: 'PEER_360', status: 'PUBLISHED' }),
      ],
    });

    expect(component.hasPeerAssignments(alice)).toBeTrue();
    expect(component.peerReviewSummary(alice)).toBe('2/2 completed');
    expect(component.hasPeerAssignments(bob)).toBeFalse();
    expect(component.peerReviewSummary(bob)).toBe('Not assigned');
  });

  it('ignores peer reviews that have no subject user id', () => {
    const { component } = buildComponent({
      cycleReviews: [
        review({ reviewId: 'peer-no-subject', subjectUserId: undefined, authorUserId: 'bob', reviewType: 'PEER_360', status: 'COMPLETED' }),
      ],
    });

    expect(component.peerReviews.size).toBe(0);
  });

  it('does not navigate review actions when there is no active cycle', () => {
    const { component } = buildComponent({ cycles: [] });

    component.startReview(alice);
    component.managePeer360(alice);

    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('toggles reminder menus and exposes disabled reasons', () => {
    const { component } = buildComponent({
      cycleReviews: [
        review({ reviewId: 'self-alice', subjectUserId: 'alice', reviewType: 'SELF', status: 'COMPLETED' }),
        review({ reviewId: 'peer-complete', subjectUserId: 'alice', authorUserId: 'bob', reviewType: 'PEER_360', status: 'PUBLISHED' }),
        review({ reviewId: 'peer-open', subjectUserId: 'bob', authorUserId: 'alice', reviewType: 'PEER_360', status: 'IN_PROGRESS' }),
      ],
    });
    const event = { stopPropagation: jasmine.createSpy('stop') } as unknown as MouseEvent;

    component.toggleReminderMenu(alice, event);
    expect(component.openReminderMenuFor).toBe('alice');
    component.toggleReminderMenu(alice, event);
    expect(component.openReminderMenuFor).toBe('');
    component.toggleReminderMenu(bob, event);
    component.closeReminderMenus();
    expect(component.openReminderMenuFor).toBe('');

    expect(component.canRemindEmployee(alice)).toBeFalse();
    expect(component.employeeReminderDisabledReason(alice)).toBe('Employee review is complete');
    expect(component.peerReminderDisabledReason({ ...alice, userId: 'none' })).toBe('No peer reviewers assigned');
    expect(component.peerReminderDisabledReason(alice)).toBe('All peer reviews are complete');
    expect(component.canRemindPeers(bob)).toBeTrue();
    expect(component.peerReminderDisabledReason(bob)).toBe('');

    component.activeCycle = null;
    expect(component.employeeReminderDisabledReason(bob)).toBe('No active cycle');
    expect(component.peerReminderDisabledReason(bob)).toBe('No active cycle');
  });

  it('opens reminder confirmations and guards unavailable reminder actions', () => {
    const { component } = buildComponent({
      cycleReviews: [
        review({ reviewId: 'self-alice', subjectUserId: 'alice', reviewType: 'SELF', status: 'COMPLETED' }),
        review({ reviewId: 'peer-open', subjectUserId: 'bob', authorUserId: 'alice', reviewType: 'PEER_360', status: 'IN_PROGRESS' }),
        review({ reviewId: 'peer-done', subjectUserId: 'bob', authorUserId: 'manager-1', reviewType: 'PEER_360', status: 'COMPLETED' }),
      ],
    });
    const event = { stopPropagation: jasmine.createSpy('stop') } as unknown as MouseEvent;

    component.openReminderConfirm(alice, 'employee', event);
    expect(component.reminderModal).toBeNull();

    component.openReminderConfirm(bob, 'employee', event);
    expect(component.reminderModal?.kind).toBe('employee');
    expect(component.reminderModal?.recipients).toEqual(['Bob Brown']);
    expect(component.reminderModalTitle()).toBe('Send employee reminder');
    expect(component.reminderModalBody()).toContain('Bob Brown');

    component.openReminderConfirm(bob, 'peer', event);
    expect(component.reminderModal?.kind).toBe('peer');
    expect(component.reminderModal?.recipients).toEqual(['Alice Anderson']);
    expect(component.reminderModalTitle()).toBe('Send peer review reminders');
    expect(component.reminderModalBody()).toContain('1 incomplete peer reviewer');

    component.peerReviews.set('bob', [
      review({ reviewId: 'peer-open-1', subjectUserId: 'bob', authorUserId: 'alice', reviewType: 'PEER_360', status: 'IN_PROGRESS' }),
      review({ reviewId: 'peer-open-2', subjectUserId: 'bob', authorUserId: 'arch', reviewType: 'PEER_360', status: 'DRAFT' }),
    ]);
    component.openReminderConfirm(bob, 'peer', event);
    expect(component.reminderModalBody()).toContain('2 incomplete peer reviewers');

    component.closeReminderModal();
    expect(component.reminderModalTitle()).toBe('');
    expect(component.reminderModalBody()).toBe('');

    component.openReminderConfirm(alice, 'peer', event);
    expect(component.reminderModal).toBeNull();

    component.activeCycle = null;
    component.openReminderConfirm(bob, 'employee', event);
    expect(component.reminderModal).toBeNull();
  });

  it('confirms employee reminders, queues jobs, polls delivery, and clears modal state', () => {
    const { component } = buildComponent();
    notificationService.sendManualReminder.and.returnValue(of({
      created: [{ jobId: 'job-1', recipientUserId: 'alice', status: 'PENDING' }],
    }));
    notificationService.getJobStatus.and.returnValue(of({ status: 'COMPLETED', materialized: true }));

    component.openReminderConfirm(alice, 'employee');
    component.confirmReminder();

    expect(notificationService.sendManualReminder).toHaveBeenCalledWith({
      cycleId: 'cycle-new',
      targetType: 'SELF_REVIEW_SUBJECT',
      subjectUserIds: ['alice'],
    });
    expect(component.reminderModal).toBeNull();
    expect(component.sendingReminderKey).toBe('');
    expect(component.notificationToasts.map(t => t.message)).toContain('Reminder queued for Alice Anderson.');
    expect(component.notificationToasts.map(t => t.message)).toContain('Reminder sent.');
    expect(notificationService.getJobStatus).toHaveBeenCalledWith('job-1');

    component.ngOnDestroy();
  });

  it('confirms peer reminders and shows skipped reminder outcomes from response payloads', () => {
    const { component } = buildComponent({
      cycleReviews: [
        review({ reviewId: 'peer-open', subjectUserId: 'bob', authorUserId: 'alice', reviewType: 'PEER_360', status: 'IN_PROGRESS' }),
      ],
    });
    notificationService.sendManualReminder.and.returnValue(of({
      created: [{ jobId: 'job-peer', recipientUserId: 'alice', status: 'PENDING' }],
      skipped: [
        { recipientUserId: 'alice', reason: 'ALREADY_REMINDED_TODAY' },
        { recipientUserId: 'missing', reason: 'FORBIDDEN' },
        { recipientUserId: undefined },
      ],
    }));

    component.openReminderConfirm(bob, 'peer');
    component.confirmReminder();

    expect(notificationService.sendManualReminder).toHaveBeenCalledWith({
      cycleId: 'cycle-new',
      targetType: 'PEER_REVIEW_EMPLOYEE',
      subjectUserIds: ['bob'],
    });
    expect(component.notificationToasts.map(t => t.message)).toContain('Reminders queued for Alice Anderson.');
    expect(component.notificationToasts.map(t => t.message)).toContain('Already reminded Alice Anderson today.');
    expect(component.notificationToasts.map(t => t.message)).toContain('You do not have permission to remind the recipient.');
    expect(component.notificationToasts.map(t => t.message)).toContain('No reminder needed for the recipient.');

    component.ngOnDestroy();
  });

  it('shows no reminder needed when reminder response has no created jobs or skipped items', () => {
    const { component } = buildComponent();
    notificationService.sendManualReminder.and.returnValue(of({ created: [], skipped: [] }));

    component.openReminderConfirm(alice, 'employee');
    component.confirmReminder();

    expect(component.notificationToasts.map(t => t.message)).toContain('No reminder needed.');

    component.ngOnDestroy();
  });

  it('handles reminder errors, parsed reminder responses, and guard clauses', () => {
    const { component } = buildComponent();
    const noId = { ...alice, userId: undefined, id: undefined };

    component.confirmReminder();
    expect(notificationService.sendManualReminder).not.toHaveBeenCalled();

    component.reminderModal = { member: noId, kind: 'employee', recipients: ['this employee'] };
    component.confirmReminder();
    expect(notificationService.sendManualReminder).not.toHaveBeenCalled();

    notificationService.sendManualReminder.and.returnValue(throwError(() => ({
      error: JSON.stringify({ skipped: [{ recipientUserId: 'alice', reason: 'FORBIDDEN' }] }),
    })));
    component.openReminderConfirm(alice, 'employee');
    component.confirmReminder();
    expect(component.notificationToasts.map(t => t.message)).toContain('You do not have permission to remind Alice Anderson.');

    notificationService.sendManualReminder.and.returnValue(throwError(() => ({
      error: { text: JSON.stringify({ skipped: [{ recipientUserId: 'alice' }] }) },
    })));
    component.openReminderConfirm(alice, 'employee');
    component.confirmReminder();
    expect(component.notificationToasts.map(t => t.message)).toContain('No reminder needed for Alice Anderson.');

    notificationService.sendManualReminder.and.returnValue(throwError(() => ({ status: 403 })));
    component.openReminderConfirm(alice, 'employee');
    component.confirmReminder();
    expect(component.notificationToasts.map(t => t.message)).toContain('You do not have permission to send this reminder.');

    notificationService.sendManualReminder.and.returnValue(throwError(() => ({ error: { message: 'Queue unavailable' } })));
    component.openReminderConfirm(alice, 'employee');
    component.confirmReminder();
    expect(component.notificationToasts.map(t => t.message)).toContain('Queue unavailable');

    notificationService.sendManualReminder.and.returnValue(throwError(() => ({ error: '{bad json' })));
    component.openReminderConfirm(alice, 'employee');
    component.confirmReminder();
    expect(component.notificationToasts.map(t => t.message)).toContain('Could not send reminder. Please try again.');

    component.ngOnDestroy();
  });

  it('covers reminder formatting helpers, delivery plural copy, and toast dismissal', () => {
    const { component } = buildComponent();

    expect((component as any).formatNameList([])).toBe('the recipient');
    expect((component as any).formatNameList(['A'])).toBe('A');
    expect((component as any).formatNameList(['A', 'B'])).toBe('A and B');
    expect((component as any).formatNameList(['A', 'B', 'C'])).toBe('A, B, and C');
    expect((component as any).formatNameList(['A', 'B', 'C', 'D'])).toBe('A, B, C, and 1 other');
    expect((component as any).formatNameList(['A', 'B', 'C', 'D', 'E'])).toBe('A, B, C, and 2 others');
    expect((component as any).recipientName(undefined, alice, 'employee')).toBe('Alice Anderson');
    expect((component as any).recipientName(undefined, alice, 'peer')).toBe('the recipient');
    expect((component as any).recipientName('manager-1', alice, 'peer')).toBe('Mina Manager');

    (component as any).showDeliveryResultToast(new Map([
      ['job-1', 'COMPLETED'],
      ['job-2', 'COMPLETED'],
    ]));
    expect(component.notificationToasts.map(t => t.message)).toContain('2 reminders sent.');

    (component as any).showDeliveryResultToast(new Map([
      ['job-3', 'SKIPPED'],
      ['job-4', 'SKIPPED'],
    ]));
    expect(component.notificationToasts.map(t => t.message)).toContain('2 reminders skipped.');

    for (let i = 1; i <= 6; i += 1) {
      (component as any).showToast('info', `Toast ${i}`);
    }
    expect(component.notificationToasts.map(t => t.message)).toEqual(['Toast 2', 'Toast 3', 'Toast 4', 'Toast 5', 'Toast 6']);

    const toastId = component.notificationToasts[0].id;
    component.dismissToast(toastId);
    expect(component.notificationToasts.some(t => t.id === toastId)).toBeFalse();

    component.ngOnDestroy();
  });

  it('does not navigate peer360 assignment without a usable employee id', () => {
    const { component } = buildComponent();
    const noId = { ...alice, userId: undefined, id: undefined };

    component.managePeer360(noId);

    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('falls back to numeric id for peer360 assignment navigation', () => {
    const { component } = buildComponent();
    const numericId = { ...alice, userId: undefined, id: 42 };

    component.managePeer360(numericId);

    expect(router.navigate).toHaveBeenCalledOnceWith(
      ['/my-team', '42', 'cycle', 'cycle-new', 'peer360'],
      { queryParams: { managerId: 'manager-1' } }
    );
  });

  it('starts a new manager review with empty optional query params when no draft or manager milestone exists', () => {
    const noManagerCycle = { ...cycle('cycle-new', 'No manager milestone', '2026-04-01T00:00:00Z'), milestones: [] };
    const { component } = buildComponent({ cycles: [noManagerCycle] });

    component.startReview(alice);

    expect(router.navigate).toHaveBeenCalledOnceWith(['/my-team/review'], {
      queryParams: jasmine.objectContaining({
        milestoneId: '',
        reviewId: '',
        viewOnly: 'false',
      }),
    });
  });

  it('opens empty details when subject review loading fails', () => {
    const { component } = buildComponent({ subjectReviewsError: true });

    component.openDetails(alice);

    expect(component.detailsItem?.member).toBe(alice);
    expect(component.detailsItem?.reviews).toEqual([]);
    expect(component.detailsItem?.totalReviews).toBe(0);
    expect(component.detailsItem?.latestRating).toBeUndefined();
    expect(component.detailsItem?.loading).toBeFalse();
  });

  it('sorts details by cycle id when submitted dates are missing and handles members without user ids', () => {
    const noUserId = { ...alice, userId: undefined, id: 99 };
    const { component } = buildComponent({
      subjectReviews: [
        review({ reviewId: 'cycle-a-review', cycleId: 'cycle-a', subjectUserId: 'alice', authorUserId: 'manager-1', reviewType: 'MANAGER' }),
        review({ reviewId: 'cycle-z-review', cycleId: 'cycle-z', subjectUserId: 'alice', authorUserId: 'manager-1', reviewType: 'MANAGER' }),
      ],
    });

    component.openDetails(noUserId);

    expect(employeeService.getReviewsBySubject).toHaveBeenCalledWith('');
    expect(component.detailsItem?.reviews.map(item => item.review.reviewId)).toEqual(['cycle-z-review', 'cycle-a-review']);
  });

  it('navigates to a full historical review and ignores rows without review ids', () => {
    const { component } = buildComponent();

    component.viewFullReview({ review: review({ reviewId: undefined, subjectUserId: 'alice' }) });
    expect(router.navigate).not.toHaveBeenCalled();

    component.viewFullReview({
      review: review({
        reviewId: 'mgr-old',
        cycleId: 'cycle-old',
        subjectUserId: 'alice',
        authorUserId: 'manager-1',
        reviewType: 'MANAGER',
      }),
    });

    expect(router.navigate).toHaveBeenCalledOnceWith(['/my-team/review'], {
      queryParams: {
        cycleId: 'cycle-old',
        subjectUserId: 'alice',
        reviewId: 'mgr-old',
        viewOnly: 'true',
        from: 'my-team',
      },
    });
  });

  it('closes details and formats initials with missing names', () => {
    const { component } = buildComponent();
    component.detailsItem = { member: alice, reviews: [], totalReviews: 0, loading: false };

    component.closeDetails();

    expect(component.detailsItem).toBeNull();
    expect(component.initials(alice)).toBe('AA');
    expect(component.initials({ ...alice, firstName: '', lastName: '' })).toBe('');
    expect(component.memberName({ ...alice, firstName: undefined, lastName: undefined } as any)).toBe('this employee');
    expect(component.memberId({ ...alice, userId: undefined, id: 123 })).toBe('123');
  });

  it('formats dates and cycle days remaining safely', () => {
    jasmine.clock().install();
    jasmine.clock().mockDate(new Date('2026-03-29T00:00:00Z'));
    const { component } = buildComponent();

    expect(component.formatDate('31/03/2026')).toContain('31 Mar 2026');
    expect(component.formatDate('1/1/999999999999')).toBe('—');
    expect(component.formatDate('not-a-date')).toBe('—');
    expect(component.formatDate(null)).toBe('—');
    expect(component.cycleDaysRemaining).toBeGreaterThanOrEqual(2);

    component.activeCycle = null;
    expect(component.cycleDaysRemaining).toBe(0);

    component.activeCycle = { ...cycle('bad', 'Bad date', '2026-01-01'), dueDate: 'bad' };
    expect(component.cycleDaysRemaining).toBe(0);
    jasmine.clock().uninstall();
  });

  it('maps rating text to colour classes', () => {
    const { component } = buildComponent();

    expect(component.ratingClass()).toBe('grey');
    expect(component.ratingClass('Exceeds Expectations')).toBe('green');
    expect(component.ratingClass('Meets Expectations')).toBe('blue');
    expect(component.ratingClass('Below Expectations')).toBe('red');
    expect(component.ratingClass('Other')).toBe('grey');
  });

  it('resolves manager and reviewer display names with fallbacks', () => {
    const { component } = buildComponent();

    expect(component.managerName(alice)).toBe('Mina Manager');
    expect(component.managerName({ ...alice, reportingTo: null })).toBe('—');
    expect(component.managerName({ ...alice, reportingTo: 'missing' })).toBe('—');
    expect(component.reviewerName(review({ authorUserId: 'manager-1' }))).toBe('Mina Manager');
    expect(component.reviewerName(review({ authorUserId: undefined }))).toBe('—');
    expect(component.reviewerName(review({ authorUserId: 'missing' }))).toBe('—');

    component.allEmployees = [];
    component.members = [{ ...alice, userId: undefined, id: 7 }];
    expect(component.reviewerName(review({ authorUserId: '7' }))).toBe('Alice Anderson');
  });

  it('returns manager submission metadata helpers', () => {
    const { component } = buildComponent({
      cycleReviews: [
        review({
          reviewId: 'mgr-alice',
          subjectUserId: 'alice',
          authorUserId: 'manager-1',
          reviewType: 'MANAGER',
          status: 'COMPLETED',
          submittedAt: '2026-03-01',
          overallRating: 'Meets Expectations',
        }),
      ],
    });

    expect(component.selfReviewSubmittedAt(alice)).toBeNull();
    expect(component.managerReviewSubmittedAt(alice)).toBe('2026-03-01');
    expect(component.overallRating(alice)).toBe('Meets Expectations');
    expect(component.managerReviewSubmittedAt(bob)).toBeNull();
    expect(component.overallRating(bob)).toBeNull();
  });
});
