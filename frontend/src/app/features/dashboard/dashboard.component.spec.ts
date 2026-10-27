import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { AuthService } from '../../core/auth/auth.service';
import { User } from '../../models/employee.model';
import { Review } from '../../models/review.model';
import { EmployeeService, PublicUser, ReviewCycle } from '../../services/employees.services';
import { DashboardComponent } from './dashboard.component';

describe('DashboardComponent', () => {
  let employeeService: jasmine.SpyObj<EmployeeService>;
  let authService: jasmine.SpyObj<AuthService>;

  const currentUser: User = {
    userId: 'user-1',
    email: 'user@x.com',
    firstName: 'Una',
    lastName: 'User',
    role: 'EMPLOYEE',
    position: 'Engineer',
    status: 'Active',
    reportingTo: 'manager-1',
    hireDate: '01/02/2024',
    discipline: 'Software',
  };
  const manager: User = {
    userId: 'manager-1',
    email: 'manager@x.com',
    firstName: 'Mina',
    lastName: 'Manager',
    role: 'MANAGER',
    position: 'Engineering Manager',
    status: 'Active',
    reportingTo: null,
  };
  const managerPublic: PublicUser = {
    userId: 'manager-1',
    firstName: 'Mina',
    lastName: 'Manager',
    role: 'MANAGER',
  };
  const alice: User = {
    userId: 'alice',
    email: 'alice@x.com',
    firstName: 'Alice',
    lastName: 'Anderson',
    role: 'EMPLOYEE',
    position: 'Consultant',
    status: 'Active',
    reportingTo: 'manager-1',
    discipline: 'Consulting',
  };
  const bob: User = {
    userId: 'bob',
    email: 'bob@x.com',
    firstName: 'Bob',
    lastName: 'Brown',
    role: 'EMPLOYEE',
    position: 'Engineer',
    status: 'Active',
    reportingTo: 'manager-1',
  };
  const archived: User = {
    userId: 'archived',
    email: 'archived@x.com',
    firstName: 'Archived',
    lastName: 'Person',
    role: 'EMPLOYEE',
    position: 'Engineer',
    status: 'Archived',
    reportingTo: 'manager-1',
  };

  function activeCycle(overrides: Partial<ReviewCycle> = {}): ReviewCycle {
    return {
      cycleId: 'cycle-1',
      cycleTitle: 'FY26 Review',
      startDate: '2026-01-01',
      dueDate: '2026-03-31',
      status: 'ACTIVE',
      updatedAt: '2026-01-15T00:00:00Z',
      milestones: [
        {
          milestoneId: 'm-self',
          name: 'Self Review',
          assignedRoles: ['EMPLOYEE'],
          startDate: '2026-01-05',
          endDate: '2026-01-20',
          status: 'OPEN',
        },
        {
          milestoneId: 'm-peer',
          name: 'Peer 360',
          assignedRoles: ['EMPLOYEE'],
          startDate: '2026-02-01',
          endDate: '2026-02-15',
          status: 'OPEN',
        },
        {
          milestoneId: 'm-manager',
          name: 'Manager Review',
          assignedRoles: ['MANAGER'],
          startDate: '2026-03-01',
          endDate: '2026-03-20',
          status: 'OPEN',
        },
      ],
      ...overrides,
    };
  }

  function review(partial: Partial<Review>): Review {
    return {
      reviewId: 'review-1',
      cycleId: 'cycle-1',
      userId: partial.subjectUserId ?? partial.authorUserId ?? 'user-1',
      subjectUserId: partial.subjectUserId,
      authorUserId: partial.authorUserId,
      reviewType: 'SELF',
      status: 'NOT_STARTED',
      answers: [],
      ...partial,
    };
  }

  function buildComponent(opts: {
    user?: User | null;
    cycles?: ReviewCycle[];
    assignedReviews?: Review[];
    subjectReviews?: Review[];
    allUsers?: User[];
    team?: User[];
    cycleReviews?: Review[];
    isAdmin?: boolean;
    isManagerOrAdmin?: boolean;
    userError?: boolean;
    cycleError?: boolean;
    assignedError?: boolean;
    subjectError?: boolean;
    usersError?: boolean;
    teamError?: boolean;
    cycleReviewsError?: boolean;
    tokenSub?: string;
    tokenPayloadMissing?: boolean;
  } = {}) {
    employeeService = jasmine.createSpyObj<EmployeeService>('EmployeeService', [
      'getUserById',
      'getCycles',
      'getMyReviews',
      'getMySubjectReviews',
      'getAllUsers',
      'getMyTeam',
      'getReviewsByCycle',
      'getUserPublic',
    ]);
    authService = jasmine.createSpyObj<AuthService>('AuthService', [
      'getTokenPayload',
      'isAdmin',
      'isManagerOrAdmin',
      'cacheUserName',
      'cacheUserRole',
    ]);

    const user = Object.prototype.hasOwnProperty.call(opts, 'user') ? opts.user : currentUser;
    employeeService.getUserById.and.callFake(((id: string) => {
      if (opts.userError) return throwError(() => new Error('user failed'));
      if (id === 'manager-1') return of(manager);
      return of(user as User | null);
    }) as any);
    employeeService.getCycles.and.returnValue(
      opts.cycleError ? throwError(() => new Error('cycle failed')) : of(opts.cycles ?? [activeCycle()])
    );
    employeeService.getMyReviews.and.returnValue(
      opts.assignedError ? throwError(() => new Error('assigned failed')) : of(opts.assignedReviews ?? [])
    );
    employeeService.getMySubjectReviews.and.returnValue(
      opts.subjectError ? throwError(() => new Error('subject failed')) : of(opts.subjectReviews ?? [])
    );
    employeeService.getAllUsers.and.returnValue(
      opts.usersError ? throwError(() => new Error('users failed')) : of(opts.allUsers ?? [currentUser, manager, alice, bob, archived])
    );
    employeeService.getMyTeam.and.returnValue(
      opts.teamError ? throwError(() => new Error('team failed')) : of(opts.team ?? [alice, bob])
    );
    employeeService.getReviewsByCycle.and.returnValue(
      opts.cycleReviewsError ? throwError(() => new Error('cycle reviews failed')) : of(opts.cycleReviews ?? [])
    );
    employeeService.getUserPublic.and.callFake(((id: string) => {
      if (id === 'manager-1') return of(managerPublic);
      const found = [currentUser, manager, alice, bob, archived].find(u => u.userId === id);
      return of(found ? {
        userId: found.userId!,
        firstName: found.firstName,
        lastName: found.lastName,
        role: found.role,
      } : null);
    }) as any);

    authService.getTokenPayload.and.returnValue(
      opts.tokenPayloadMissing
        ? null
        : {
            sub: opts.tokenSub ?? 'user-1',
            email: 'user@x.com',
            'cognito:groups': [],
          }
    );
    authService.isAdmin.and.returnValue(opts.isAdmin ?? false);
    authService.isManagerOrAdmin.and.returnValue(opts.isManagerOrAdmin ?? false);

    TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        { provide: EmployeeService, useValue: employeeService },
        { provide: AuthService, useValue: authService },
        provideRouter([]),
      ],
    });

    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    return { fixture, component: fixture.componentInstance };
  }

  it('loads the current user, caches identity, and builds a self-review task', () => {
    const { component, fixture } = buildComponent({
      subjectReviews: [
        review({
          reviewId: 'self-1',
          subjectUserId: 'user-1',
          authorUserId: 'user-1',
          reviewType: 'SELF',
          status: 'DRAFT',
        }),
      ],
    });

    expect(component.loading).toBeFalse();
    expect(component.displayName).toBe('Una User');
    expect(component.roleLabel).toBe('Complete your self-review and track your performance.');
    expect(authService.cacheUserName).toHaveBeenCalledOnceWith('Una', 'User');
    expect(authService.cacheUserRole).toHaveBeenCalledOnceWith('EMPLOYEE');
    expect(component.activeCycle?.cycleId).toBe('cycle-1');
    expect(component.tasks).toEqual([
      jasmine.objectContaining({
        id: 'self-1',
        title: 'Complete Self Review',
        status: 'In Draft',
        type: 'self-review',
        actionLink: ['/my-review/fill', 'self'],
        actionQueryParams: jasmine.objectContaining({
          cycleId: 'cycle-1',
          milestoneId: 'm-self',
          reviewId: 'self-1',
        }),
      }) as any,
    ]);
    expect(fixture.nativeElement.textContent).toContain('Una');
  });

  it('creates peer-review tasks with subject names and sorts by due date', () => {
    const { component } = buildComponent({
      assignedReviews: [
        review({
          reviewId: 'peer-1',
          subjectUserId: 'alice',
          authorUserId: 'user-1',
          reviewType: 'PEER_360',
          status: 'NOT_STARTED',
        }),
        review({
          reviewId: 'self-1',
          subjectUserId: 'user-1',
          authorUserId: 'user-1',
          reviewType: 'SELF',
          status: 'NOT_STARTED',
        }),
      ],
    });

    expect(component.tasks.map(t => t.title)).toEqual([
      'Complete Self Review',
      'Peer Review - Alice Anderson',
    ]);
    expect(component.tasks[1]).toEqual(jasmine.objectContaining({
      id: 'peer-1',
      dueDate: '2026-02-15',
      status: 'Not Started',
      type: 'peer-review',
      actionLink: ['/my-review/fill', 'peer-360'],
    }) as any);
  });

  it('does not show completed self or peer tasks', () => {
    const { component } = buildComponent({
      assignedReviews: [
        review({ reviewId: 'peer-done', subjectUserId: 'alice', reviewType: 'PEER_360', status: 'PUBLISHED' }),
      ],
      subjectReviews: [
        review({ reviewId: 'self-done', subjectUserId: 'user-1', reviewType: 'SELF', status: 'COMPLETED' }),
      ],
    });

    expect(component.tasks).toEqual([]);
  });

  it('falls back to the first milestone and a default name for peer tasks when peer metadata is missing', () => {
    const noPeerCycle = activeCycle({
      milestones: [
        {
          milestoneId: 'm-only',
          name: 'Kickoff',
          assignedRoles: ['EMPLOYEE'],
          startDate: '2026-01-01',
          endDate: '2026-01-10',
          status: 'OPEN',
        },
      ],
    });
    const { component } = buildComponent({
      cycles: [noPeerCycle],
      assignedReviews: [
        review({
          reviewId: undefined,
          subjectUserId: 'missing-user',
          authorUserId: 'user-1',
          reviewType: 'PEER_360',
          status: 'IN_PROGRESS',
        }),
      ],
    });

    const peerTask = component.tasks.find(t => t.type === 'peer-review');
    expect(peerTask).toEqual(jasmine.objectContaining({
      id: 'cycle-1:peer:missing-user',
      title: 'Peer Review - Team Member',
      dueDate: '2026-01-10',
      status: 'In Draft',
      actionQueryParams: jasmine.objectContaining({ milestoneId: 'm-only', reviewId: '' }),
    }) as any);
  });

  it('loads manager details and no team tasks for a regular employee', () => {
    const { component } = buildComponent();

    expect(employeeService.getMyTeam).not.toHaveBeenCalled();
    expect(employeeService.getReviewsByCycle).not.toHaveBeenCalled();
    expect(component.manager).toEqual(managerPublic);
    expect(component.teamMembers).toEqual([]);
    expect(component.allUsers.map(u => u.userId)).toEqual(['user-1']);
  });

  it('builds manager review tasks for active direct reports', () => {
    const managerUser = { ...manager, reportingTo: null };
    const { component } = buildComponent({
      user: managerUser,
      isManagerOrAdmin: true,
      team: [alice, bob],
      cycleReviews: [
        review({
          reviewId: 'mgr-alice',
          subjectUserId: 'alice',
          authorUserId: 'user-1',
          reviewType: 'MANAGER',
          status: 'DRAFT',
        }),
        review({
          reviewId: 'mgr-bob',
          subjectUserId: 'bob',
          authorUserId: 'user-1',
          reviewType: 'MANAGER',
          status: 'COMPLETED',
        }),
      ],
    });

    expect(employeeService.getMyTeam).toHaveBeenCalledOnceWith('user-1');
    expect(employeeService.getReviewsByCycle).toHaveBeenCalledOnceWith('cycle-1');
    expect(component.tasks).toContain(jasmine.objectContaining({
      id: 'mgr-alice',
      title: 'Manager Review - Alice Anderson',
      status: 'In Draft',
      type: 'manager-review',
      actionLink: ['/my-team/review'],
      actionQueryParams: jasmine.objectContaining({
        cycleId: 'cycle-1',
        milestoneId: 'm-manager',
        subjectUserId: 'alice',
        reviewId: 'mgr-alice',
        viewOnly: 'false',
        from: 'dashboard',
      }),
    }) as any);
    expect(component.tasks).not.toContain(
      jasmine.objectContaining({
        id: 'mgr-bob',
        type: 'manager-review',
      }) as any
    );
  });

  it('derives admin team members from all users and excludes archived reports', () => {
    const admin: User = { ...currentUser, role: 'ADMIN', userId: 'admin-1', reportingTo: 'manager-1' };
    const adminReportA = { ...alice, reportingTo: 'admin-1' };
    const adminReportB = { ...bob, reportingTo: 'admin-1' };
    const archivedAdminReport = { ...archived, reportingTo: 'admin-1' };
    const { component } = buildComponent({
      user: admin,
      tokenSub: 'admin-1',
      isAdmin: true,
      isManagerOrAdmin: true,
      allUsers: [admin, manager, adminReportA, adminReportB, archivedAdminReport],
      cycleReviews: [],
    });

    expect(employeeService.getMyTeam).not.toHaveBeenCalled();
    expect(component.teamMembers.map(u => u.userId)).toEqual(['alice', 'bob']);
    expect(component.manager).toEqual(managerPublic);
  });

  it('does not build manager tasks when manager milestone is closed or review loading fails', () => {
    const closedManagerCycle = activeCycle({
      milestones: activeCycle().milestones.map(m =>
        m.milestoneId === 'm-manager' ? { ...m, status: 'COMPLETED' } : m
      ),
    });
    const { component } = buildComponent({
      user: manager,
      isManagerOrAdmin: true,
      cycles: [closedManagerCycle],
      team: [alice],
      cycleReviewsError: true,
    });

    expect(employeeService.getReviewsByCycle).not.toHaveBeenCalled();
    expect(component.tasks.every(t => t.type !== 'manager-review')).toBeTrue();
  });

  it('handles failed initial requests and no active cycle without leaving the page loading', () => {
    const { component } = buildComponent({
      userError: true,
      cycleError: true,
      assignedError: true,
      subjectError: true,
      usersError: true,
    });

    expect(component.loading).toBeFalse();
    expect(component.currentUser).toBeNull();
    expect(component.activeCycle).toBeNull();
    expect(component.tasks).toEqual([]);
    expect(component.allUsers).toEqual([]);
  });

  it('builds milestone labels, dates, states, and bounded progress', () => {
    jasmine.clock().install();
    jasmine.clock().mockDate(new Date('2026-02-10T12:00:00Z'));

    const { component } = buildComponent({
      cycles: [
        activeCycle({
          milestones: [
            {
              milestoneId: 'm-self',
              name: 'Self Review',
              assignedRoles: ['EMPLOYEE'],
              startDate: '2026-01-10',
              endDate: '2026-01-20',
              status: 'OPEN',
            },
            {
              milestoneId: 'm-peer',
              name: 'Peer 360',
              assignedRoles: ['EMPLOYEE'],
              startDate: '2026-03-01',
              endDate: '2026-03-10',
              status: 'OPEN',
            },
          ],
        }),
      ],
    });

    expect(component.cycleDueDateLabel).toBe('31/03/2026');
    expect(component.milestones.map(m => m.label)).toEqual([
      'Self Review',
      'Peer 360',
      'Review Cycle End',
    ]);
    expect(component.milestones.map(m => m.state)).toEqual(['completed', 'current', 'upcoming']);
    expect(component.progressPercent).toBeGreaterThan(0);
    expect(component.progressPercent).toBeLessThan(100);

    jasmine.clock().uninstall();
  });

  it('returns empty milestone timeline progress from date math when no valid milestone dates exist', () => {
    jasmine.clock().install();
    jasmine.clock().mockDate(new Date('2026-02-01T00:00:00Z'));

    const { component } = buildComponent({
      cycles: [
        activeCycle({
          startDate: '2026-01-01',
          dueDate: '',
          milestones: [],
        }),
      ],
    });

    expect(component.milestones).toEqual([]);
    expect(Number.isNaN(component.progressPercent)).toBeTrue();

    jasmine.clock().uninstall();
  });

  it('exposes task action helpers and role-specific empty messages', () => {
    const { component } = buildComponent();
    const draftTask = {
      id: 't1',
      title: 'Draft',
      dueDate: '2027-01-01',
      status: 'In Draft' as const,
      type: 'self-review' as const,
      actionLink: [],
      actionQueryParams: {},
    };
    const completedTask = { ...draftTask, status: 'Completed' as const };

    expect(component.canStartTask(draftTask)).toBeTrue();
    expect(component.canStartTask(completedTask)).toBeFalse();
    expect(component.getTaskActionLabel(draftTask)).toBe('Continue');
    expect(component.getTaskActionLabel({ ...draftTask, status: 'Not Started' })).toBe('Start');
    expect(component.tasksEmptyMessage).toBe("Nothing to do right now — enjoy your day!");

    component.currentUser = { ...currentUser, role: 'MANAGER' };
    expect(component.tasksEmptyMessage).toBe('No reviews waiting for you. Check back when the next cycle opens.');

    authService.isAdmin.and.returnValue(true);
    expect(component.tasksEmptyMessage).toBe("You're all caught up! No pending tasks right now.");
  });

  it('opens/closes member profile and resolves member profile helpers', () => {
    const { component } = buildComponent();
    component.allUsers = [manager];

    component.openMemberProfile(alice);
    expect(component.selectedMember).toBe(alice);
    component.closeMemberProfile();
    expect(component.selectedMember).toBeNull();

    expect(component.getMemberManagerName(alice)).toBe('Mina Manager');
    expect(component.getMemberManagerName({ ...alice, reportingTo: null })).toBe('—');
    expect(component.getMemberManagerName({ ...alice, reportingTo: 'missing' })).toBe('—');
    expect(component.getMemberDepartment(alice)).toBe('Consulting Department');
    expect(component.getMemberDepartment(bob)).toBe('-');
    expect(component.getLatestRating(alice)).toBe('-');
    expect(component.getCurrentGoals(alice)).toBe('-');
    expect(component.getCompletedReviews(alice)).toBe('-');
    expect(component.getMemberSkills(alice)).toEqual([]);
  });

  it('formats dates and avatar urls', () => {
    const { component } = buildComponent();

    expect(component.formatHireDate('01/02/2026')).toContain('1 Feb 2026');
    expect(component.formatHireDate('')).toBe('—');
    expect(component.formatDate('2026-03-15')).toContain('15 Mar');
    expect(component.formatDate('')).toBe('—');
    expect(component.formatTaskDate('31/03/2026')).toContain('31 Mar 2026');
    expect(component.formatTaskDate('')).toBe('---');
    expect(component.getAvatarUrl('Alice', 'Anderson')).toContain('name=Alice%20Anderson');
  });

  it('sorts newest active cycle first', () => {
    const older = activeCycle({ cycleId: 'older', updatedAt: '2026-01-01T00:00:00Z' });
    const newer = activeCycle({ cycleId: 'newer', updatedAt: '2026-02-01T00:00:00Z' });

    const { component } = buildComponent({ cycles: [older, newer] });

    expect(component.activeCycle?.cycleId).toBe('newer');
  });

  it('falls back when token payload and cycle updatedAt values are missing', () => {
    const withUpdatedAt = activeCycle({ cycleId: 'with-date', updatedAt: '2026-02-01T00:00:00Z' });
    const withoutUpdatedAt = activeCycle({ cycleId: 'without-date', updatedAt: undefined });

    const { component } = buildComponent({
      tokenPayloadMissing: true,
      cycles: [withoutUpdatedAt, withUpdatedAt],
    });

    expect(employeeService.getUserById).toHaveBeenCalledWith('');
    expect(component.activeCycle?.cycleId).toBe('with-date');
  });

  it('covers low-level status and fallback branches used by task builders', () => {
    const { component } = buildComponent();

    expect((component as any).mapStatus('COMPLETED')).toBe('completed');
    expect((component as any).mapStatus('OPEN')).toBe('current');
    expect((component as any).mapStatus('NOT_STARTED')).toBe('upcoming');
    expect((component as any).toTaskStatus('COMPLETED')).toBe('Completed');
    component.allUsers = [];
    expect((component as any).getUserName(undefined)).toBe('Unknown');

    component.activeCycle = null;
    expect((component as any).findMilestone('SELF')).toBeNull();
    expect((component as any).milestonesFallback()).toBeNull();
    expect((component as any).milestoneType(null)).toBe('SELF');
  });

  it('uses cycle due date and empty milestone id when a peer task has no milestone fallback', () => {
    const { component } = buildComponent({
      cycles: [activeCycle({ milestones: [] })],
      assignedReviews: [
        review({
          reviewId: undefined,
          subjectUserId: undefined,
          userId: 'alice',
          authorUserId: 'user-1',
          reviewType: 'PEER_360',
          status: 'NOT_STARTED',
        }),
      ],
    });

    expect(component.tasks[0]).toEqual(jasmine.objectContaining({
      id: 'cycle-1:peer:alice',
      title: 'Peer Review - Alice Anderson',
      dueDate: '2026-03-31',
      actionQueryParams: jasmine.objectContaining({ milestoneId: '' }),
    }) as any);
  });

  it('groups milestones that share dates, skips invalid dates, and appends cycle end to an existing date', () => {
    jasmine.clock().install();
    jasmine.clock().mockDate(new Date('2025-12-20T00:00:00Z'));

    const { component } = buildComponent({
      cycles: [
        activeCycle({
          startDate: '2025-12-01',
          dueDate: '2026-01-10',
          milestones: [
            {
              milestoneId: 'm-a',
              name: 'Self Review',
              assignedRoles: ['EMPLOYEE'],
              startDate: '',
              endDate: '2026-01-01',
              status: 'OPEN',
            },
            {
              milestoneId: 'm-b',
              name: 'Peer 360',
              assignedRoles: ['EMPLOYEE'],
              startDate: '2026-01-01',
              endDate: '2026-01-02',
              status: 'OPEN',
            },
            {
              milestoneId: 'm-bad',
              name: 'Bad Date',
              assignedRoles: ['EMPLOYEE'],
              startDate: 'not-a-date',
              endDate: 'not-a-date',
              status: 'OPEN',
            },
            {
              milestoneId: 'm-end',
              name: 'Manager Review',
              assignedRoles: ['MANAGER'],
              startDate: '2026-01-10',
              endDate: '2026-01-10',
              status: 'OPEN',
            },
          ],
        }),
      ],
    });

    expect(component.milestones.map(m => m.label)).toEqual([
      'Self Review / Peer 360',
      'Manager Review / Review Cycle End',
    ]);
    expect(component.milestones[0].isNext).toBeTrue();

    jasmine.clock().uninstall();
  });
});
