import { HttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';

import { EmployeeService } from '../../services/employees.services';
import { AiReviewService } from '../../services/ai-review.service';
import { NotificationService } from '../../services/notification.service';
import { CycleSummaryComponent } from './cycle-summary.component';

describe('CycleSummaryComponent manual reminders', () => {
  let employeeService: jasmine.SpyObj<EmployeeService>;
  let notificationService: jasmine.SpyObj<NotificationService>;
  let aiReviewService: jasmine.SpyObj<AiReviewService>;
  let http: jasmine.SpyObj<HttpClient>;
  let router: jasmine.SpyObj<Router>;

  const manager = {
    userId: 'manager-1',
    email: 'sarah@example.com',
    firstName: 'Sarah',
    lastName: 'Jones',
    role: 'MANAGER',
    position: 'Lead',
    status: 'Active',
    reportingTo: null,
  };

  const employee = {
    userId: 'emp-1',
    email: 'jane@example.com',
    firstName: 'Jane',
    lastName: 'Smith',
    role: 'EMPLOYEE',
    position: 'Consultant',
    status: 'Active',
    reportingTo: 'manager-1',
  };

  const unassignedEmployee = {
    userId: 'emp-2',
    email: 'sam@example.com',
    firstName: 'Sam',
    lastName: 'Lee',
    role: 'EMPLOYEE',
    position: 'Engineer',
    status: 'Active',
    reportingTo: null,
  };

  const secondEmployee = {
    userId: 'emp-3',
    email: 'alex@example.com',
    firstName: 'Alex',
    lastName: 'Kim',
    role: 'EMPLOYEE',
    position: 'Engineer',
    status: 'Active',
    reportingTo: 'manager-1',
  };

  const activeCycle = {
    cycleId: 'cycle-1',
    cycleTitle: 'FY26 Review',
    status: 'ACTIVE',
    milestones: [],
  };

  const activeCycleWithMilestones = {
    ...activeCycle,
    milestones: [
      {
        milestoneId: 'self-ms',
        name: 'Self Review',
        assignedRoles: ['EMPLOYEE'],
        templateSnapshot: {
          reviewType: 'SELF',
          questions: [{ questionId: 'sq1', text: 'Self impact?' }],
        },
      },
      {
        milestoneId: 'manager-ms',
        name: 'Manager Review',
        assignedRoles: ['MANAGER'],
        templateSnapshot: {
          reviewType: 'MANAGER',
          questions: [{ questionId: 'mq1', text: 'Manager comments?' }],
        },
      },
      {
        milestoneId: 'peer-ms',
        name: 'Peer 360',
        assignedRoles: ['EMPLOYEE'],
        templateSnapshot: {
          reviewType: 'PEER_360',
          questions: [{ questionId: 'pq1', text: 'Peer feedback?' }],
        },
      },
    ],
  };

  function review(overrides: Record<string, unknown>) {
    return {
      reviewId: 'review-1',
      cycleId: 'cycle-1',
      subjectUserId: 'emp-1',
      authorUserId: 'emp-1',
      userId: 'emp-1',
      reviewType: 'SELF',
      status: 'IN_PROGRESS',
      answers: [],
      ...overrides,
    };
  }

  function buildComponent(
    reviews: any[] = [],
    users: any[] = [manager, employee],
    cycles: any[] = [activeCycle],
    dataCycles: any = cycles,
  ) {
    employeeService = jasmine.createSpyObj<EmployeeService>('EmployeeService', [
      'getCycles',
      'getEmployees',
      'getReviewsByCycle',
      'getReviewsBySubject',
    ]);
    notificationService = jasmine.createSpyObj<NotificationService>('NotificationService', [
      'sendManualReminder',
      'getJobStatus',
    ]);
    aiReviewService = jasmine.createSpyObj<AiReviewService>('AiReviewService', [
      'getManagerGolden',
      'getInsightsStatus',
      'getPeer360Digest',
    ]);
    http = jasmine.createSpyObj<HttpClient>('HttpClient', ['post']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);

    let cycleCallCount = 0;
    employeeService.getCycles.and.callFake(() => of((cycleCallCount++ === 0 ? cycles : dataCycles) as any));
    employeeService.getEmployees.and.returnValue(of(users as any));
    employeeService.getReviewsByCycle.and.returnValue(of(reviews as any));
    employeeService.getReviewsBySubject.and.returnValue(of([]));
    notificationService.sendManualReminder.and.returnValue(of({ created: [], skipped: [] }));
    notificationService.getJobStatus.and.returnValue(of({
      status: 'PENDING',
      materialized: true,
    }));
    aiReviewService.getManagerGolden.and.returnValue(of({ status: 'COMPLETED', result: { overallRating: 4 } as any }));
    aiReviewService.getInsightsStatus.and.returnValue(of({ jobId: 'insights-1', status: 'COMPLETED', result: { summary: 'Insight' } as any }));
    aiReviewService.getPeer360Digest.and.returnValue(of({ status: 'COMPLETED', result: { themes: [], consensus: [], dissent: [], overallSentiment: 'mixed' } as any }));
    http.post.and.returnValue(of({ message: 'Bundle published successfully' }));

    TestBed.configureTestingModule({
      imports: [CycleSummaryComponent],
      providers: [
        { provide: EmployeeService, useValue: employeeService },
        { provide: NotificationService, useValue: notificationService },
        { provide: AiReviewService, useValue: aiReviewService },
        { provide: Router, useValue: router },
        { provide: HttpClient, useValue: http },
      ],
    });

    const fixture = TestBed.createComponent(CycleSummaryComponent);
    fixture.detectChanges();
    return { component: fixture.componentInstance, fixture };
  }

  function firstRow(component: CycleSummaryComponent): any {
    return component.managerGroups[0].employees[0];
  }

  function toastMessages(component: CycleSummaryComponent): string[] {
    return component.notificationToasts.map(toast => toast.message);
  }

  afterEach(() => {
    TestBed.resetTestingModule();
  });

  it('keeps manager id and name on employee rows during group building', () => {
    const { component } = buildComponent();
    const row = firstRow(component);

    expect(row.managerId).toBe('manager-1');
    expect(row.managerName).toBe('Sarah Jones');
  });

  it('handles no selectable cycles, fallback cycle names, wrapped loadData payloads, and missing managers', () => {
    const noCycle = buildComponent([], [], [{ cycleId: 'draft-1', cycleTitle: 'Draft', status: 'DRAFT' }]);
    expect(noCycle.component.selectedCycleId).toBe('');
    expect(noCycle.component.loading).toBeFalse();
    expect(employeeService.getEmployees).not.toHaveBeenCalled();
    noCycle.component.ngOnDestroy();
    TestBed.resetTestingModule();

    const namedCycle = { cycleId: 'cycle-named', name: 'Named Cycle', status: 'COMPLETED' };
    const idOnlyCycle = { cycleId: 'cycle-id-only', status: 'COMPLETED' };
    const wrapped = buildComponent(
      { reviews: [review({ subjectUserId: undefined })] } as any,
      { users: [{ ...employee, reportingTo: 'missing-manager', position: undefined, discipline: 'Support' }] } as any,
      [namedCycle, idOnlyCycle],
      { cycles: [namedCycle, idOnlyCycle] },
    );

    expect(wrapped.component.cycles.map(c => c.name)).toEqual(['Named Cycle', 'cycle-id-only']);
    expect(wrapped.component.selectedCycleId).toBe('cycle-named');
    expect(wrapped.component.selectedCycleName).toBe('Named Cycle');
    expect(wrapped.component.unassignedEmployees[0].employeeName).toBe('Jane Smith');
    expect(wrapped.component.unassignedEmployees[0].role).toBe('Support');

    wrapped.component.ngOnDestroy();
  });

  it('covers empty wrapped payload fallbacks and row/manager display fallbacks', () => {
    const emptyWrapped = buildComponent(
      { items: [] } as any,
      { items: [] } as any,
      [activeCycle],
      { items: [] },
    );
    expect(emptyWrapped.component.managerGroups).toEqual([]);
    expect(emptyWrapped.component.unassignedEmployees).toEqual([]);
    expect((emptyWrapped.component as any).fullCycle).toBeNull();
    emptyWrapped.component.ngOnDestroy();
    TestBed.resetTestingModule();

    const managerWithEmail = {
      userId: 'manager-email',
      email: 'manager-email@example.com',
      role: 'MANAGER',
      status: 'Active',
    };
    const managerWithIdOnly = {
      userId: 'manager-id-only',
      role: 'MANAGER',
      status: 'Active',
    };
    const namelessEmployee = {
      userId: 'emp-id-only',
      role: 'EMPLOYEE',
      status: 'Active',
      reportingTo: 'manager-email',
    };
    const noIdEmployee = {
      email: 'employee-email@example.com',
      role: 'EMPLOYEE',
      status: 'Active',
    };
    const idOnlyManagerEmployee = {
      userId: 'emp-managed-by-id',
      firstName: 'Id',
      lastName: 'Managed',
      role: 'EMPLOYEE',
      status: 'Active',
      reportingTo: 'manager-id-only',
    };
    const fallbackRows = buildComponent(
      [],
      [managerWithEmail, managerWithIdOnly, namelessEmployee, noIdEmployee, idOnlyManagerEmployee],
    );

    expect(fallbackRows.component.managerGroups[0].managerName).toBe('manager-email@example.com');
    expect(fallbackRows.component.managerGroups[0].employees[0].employeeName).toBe('emp-id-only');
    expect(fallbackRows.component.managerGroups[0].employees[0].role).toBe('');
    expect(fallbackRows.component.managerGroups[1].managerName).toBe('manager-id-only');
    expect(fallbackRows.component.unassignedEmployees[0].employeeId).toBe('');
    expect(fallbackRows.component.unassignedEmployees[0].employeeName).toBe('employee-email@example.com');

    fallbackRows.component.ngOnDestroy();
  });

  it('sends employee reminders with SELF_REVIEW_SUBJECT target type', () => {
    const { component } = buildComponent();
    const row = firstRow(component);

    component.sendReminder(row, 'employee');

    expect(notificationService.sendManualReminder).toHaveBeenCalledWith({
      cycleId: 'cycle-1',
      targetType: 'SELF_REVIEW_SUBJECT',
      subjectUserIds: ['emp-1'],
    });

    component.ngOnDestroy();
  });

  it('sends manager reminders with MANAGER_REVIEW_MANAGER target type and subject employee id', () => {
    const { component } = buildComponent();
    const row = firstRow(component);

    component.sendReminder(row, 'manager');

    expect(notificationService.sendManualReminder).toHaveBeenCalledWith({
      cycleId: 'cycle-1',
      targetType: 'MANAGER_REVIEW_MANAGER',
      subjectUserIds: ['emp-1'],
    });

    component.ngOnDestroy();
  });

  it('disables completed self and manager review reminder actions', () => {
    const { component } = buildComponent([
      review({ reviewType: 'SELF', status: 'COMPLETED' }),
      review({
        reviewId: 'manager-review',
        reviewType: 'MANAGER',
        authorUserId: 'manager-1',
        userId: 'manager-1',
        status: 'PUBLISHED',
      }),
    ]);
    const row = firstRow(component);

    expect(component.canRemindEmployee(row)).toBeFalse();
    expect(component.employeeReminderDisabledReason(row)).toBe('Employee review is complete');
    expect(component.canRemindManager(row)).toBeFalse();
    expect(component.managerReminderDisabledReason(row)).toBe('Manager review is complete');
  });

  it('disables manager reminders for unassigned employees', () => {
    const { component } = buildComponent([], [unassignedEmployee]);
    const row = component.unassignedEmployees[0] as any;

    expect(component.canRemindManager(row)).toBeFalse();
    expect(component.managerReminderDisabledReason(row)).toBe('No manager assigned');
  });

  it('shows named already-reminded warning toast for skipped employee reminders', () => {
    const { component } = buildComponent();
    const row = firstRow(component);
    notificationService.sendManualReminder.and.returnValue(of({
      created: [],
      skipped: [{
        recipientUserId: 'emp-1',
        reason: 'ALREADY_REMINDED_TODAY',
        status: 'COMPLETED',
      }],
    }));

    component.sendReminder(row, 'employee');

    expect(toastMessages(component)).toContain('Already reminded Jane Smith today.');
    expect(notificationService.getJobStatus).not.toHaveBeenCalled();

    component.ngOnDestroy();
  });

  it('shows named already-reminded warning toast for skipped manager reminders', () => {
    const { component } = buildComponent();
    const row = firstRow(component);
    notificationService.sendManualReminder.and.returnValue(of({
      created: [],
      skipped: [{
        recipientUserId: 'manager-1',
        reason: 'ALREADY_REMINDED_TODAY',
        status: 'COMPLETED',
      }],
    }));

    component.sendReminder(row, 'manager');

    expect(toastMessages(component)).toContain('Already reminded manager Sarah Jones today.');
    expect(notificationService.getJobStatus).not.toHaveBeenCalled();

    component.ngOnDestroy();
  });

  it('shows queued toast and polls only created jobs', () => {
    const { component } = buildComponent();
    const row = firstRow(component);
    notificationService.sendManualReminder.and.returnValue(of({
      created: [{ jobId: 'job-1', recipientUserId: 'manager-1', status: 'PENDING' }],
      skipped: [{
        jobId: 'job-2',
        recipientUserId: 'manager-1',
        reason: 'ALREADY_REMINDED_TODAY',
        status: 'COMPLETED',
      }],
    }));

    component.sendReminder(row, 'manager');

    expect(toastMessages(component)).toContain('Reminder queued for manager Sarah Jones.');
    expect(notificationService.getJobStatus).toHaveBeenCalledWith('job-1');
    expect(notificationService.getJobStatus).not.toHaveBeenCalledWith('job-2');

    component.ngOnDestroy();
  });

  it('shows no reminder needed copy for not-remindable manager skips', () => {
    const { component } = buildComponent();
    const row = firstRow(component);
    notificationService.sendManualReminder.and.returnValue(of({
      skipped: [{ recipientUserId: 'manager-1', reason: 'NOT_REMINDABLE' }],
    }));

    component.sendReminder(row, 'manager');

    expect(toastMessages(component)).toContain('No reminder needed for manager Sarah Jones.');

    component.ngOnDestroy();
  });

  it('caps long recipient lists and visible toast stack', () => {
    const { component } = buildComponent();

    expect((component as any).formatNameList(['A', 'B', 'C', 'D', 'E'])).toBe('A, B, C, and 2 others');
    expect((component as any).formatNameList([])).toBe('the recipient');
    expect((component as any).formatNameList(['A', 'B'])).toBe('A and B');

    for (let i = 1; i <= 6; i += 1) {
      (component as any).showToast('info', `Toast ${i}`);
    }

    expect(toastMessages(component)).toEqual(['Toast 2', 'Toast 3', 'Toast 4', 'Toast 5', 'Toast 6']);

    component.ngOnDestroy();
  });

  it('shows reminder delivery result toasts for completed and skipped reminder jobs', () => {
    const { component } = buildComponent();
    const row = firstRow(component);
    notificationService.sendManualReminder.and.returnValue(of({
      created: [{ jobId: 'job-1', recipientUserId: 'emp-1', status: 'PENDING' }],
    }));
    notificationService.getJobStatus.and.returnValue(of({ status: 'COMPLETED', materialized: true }));

    component.sendReminder(row, 'employee');
    expect(toastMessages(component)).toContain('Reminder sent.');

    notificationService.getJobStatus.and.returnValue(of({ status: 'SKIPPED', materialized: true }));
    component.sendReminder(row, 'employee');
    expect(toastMessages(component)).toContain('Reminder skipped.');

    component.ngOnDestroy();
  });

  it('filters rows, toggles groups, selection state, dropdowns, and selected cycle resets', () => {
    const { component } = buildComponent([
      review({
        reviewId: 'manager-review',
        reviewType: 'MANAGER',
        subjectUserId: 'emp-1',
        authorUserId: 'manager-1',
        userId: 'manager-1',
        status: 'COMPLETED',
      }),
    ], [manager, employee, secondEmployee]);
    const group = component.managerGroups[0];
    const row = group.employees[0] as any;

    expect(component.canPublish(row)).toBeTrue();
    expect(component.allSelected).toBeFalse();
    component.toggleSelect(row);
    expect(component.selectedIds.has('emp-1')).toBeTrue();
    component.toggleSelect(row);
    expect(component.selectedIds.has('emp-1')).toBeFalse();

    component.toggleSelectAll();
    expect(component.allSelected).toBeTrue();
    component.toggleSelectAll();
    expect(component.selectedIds.size).toBe(0);

    component.searchTerm = 'sarah';
    expect(component.filteredManagerGroups.length).toBe(1);
    component.searchTerm = 'alex';
    expect(component.filteredManagerGroups[0].employees.map(e => e.employeeName)).toEqual(['Alex Kim']);
    component.searchTerm = 'no match';
    expect(component.filteredManagerGroups).toEqual([]);

    component.toggleGroup(group);
    expect(group.expanded).toBeFalse();
    component.toggleDropdown('emp-1', { stopPropagation: jasmine.createSpy('stop') } as any);
    expect(component.openDropdownFor).toBe('emp-1');
    component.toggleDropdown('emp-1', { stopPropagation: jasmine.createSpy('stop') } as any);
    expect(component.openDropdownFor).toBeNull();
    component.toggleReminderMenu(row, { stopPropagation: jasmine.createSpy('stop') } as any);
    expect(component.openReminderMenuFor).toBe('emp-1');
    expect(component.openDropdownFor).toBeNull();
    component.toggleReminderMenu(row, { stopPropagation: jasmine.createSpy('stop') } as any);
    expect(component.openReminderMenuFor).toBeNull();
    component.closeDropdown();
    expect(component.openReminderMenuFor).toBeNull();

    component.selectedIds = new Set(['emp-1']);
    component.onCycleChange();
    expect(component.selectedIds.size).toBe(0);

    component.ngOnDestroy();
  });

  it('excludes a not-yet-submitted (DRAFT) manager review from the attachment count and view list', () => {
    // AI-Summary inconsistency: a DRAFT manager review (started, not submitted) must NOT
    // appear as a viewable "attachment" — only COMPLETED/PUBLISHED reviews are real
    // documents. The Submitted Dates column already shows "Pending" for it, so the
    // attachment dropdown + count must agree.
    const { component } = buildComponent([
      review({ status: 'COMPLETED', submittedAt: '2026-02-01' }), // self submitted → counts
      review({
        reviewId: 'manager-draft',
        reviewType: 'MANAGER',
        subjectUserId: 'emp-1',
        authorUserId: 'manager-1',
        userId: 'manager-1',
        status: 'DRAFT', // started but not submitted → must NOT count / show
      }),
    ], [manager, employee]);
    const row = firstRow(component) as any;

    expect(row.managerReviewStatus).toBe('DRAFT');
    expect(component.isCompletedStatus(row.managerReviewStatus)).toBeFalse();
    expect(component.isCompletedStatus(row.selfReviewStatus)).toBeTrue();
    expect(component.attachmentCount(row)).toBe(1); // only the submitted self review
  });

  it('covers unassigned filtering, no-cycle reminder guards, attachment counts, and start navigation', () => {
    const { component } = buildComponent([
      review({ status: 'COMPLETED', insightsJobId: 'insights-1', submittedAt: '2026-02-01' }),
      review({
        reviewId: 'manager-review',
        reviewType: 'MANAGER',
        subjectUserId: 'emp-1',
        authorUserId: 'manager-1',
        userId: 'manager-1',
        status: 'COMPLETED',
        submittedAt: '2026-03-01',
        managerGoldenJobId: 'golden-1',
        peer360DigestJobId: 'digest-1',
        completedByAdmin: 'Admin User',
      }),
      review({ reviewId: 'peer-1', reviewType: 'PEER_360', status: 'COMPLETED' }),
      review({ reviewId: 'peer-2', reviewType: 'PEER_360', status: 'PUBLISHED' }),
    ], [manager, employee, { ...unassignedEmployee, firstName: '', lastName: '', position: undefined, jobTitle: 'QA' }]);
    const row = firstRow(component) as any;

    expect(row.selfSubmittedDate).toContain('2026');
    expect(row.managerSubmittedDate).toContain('2026');
    expect(row.completedByAdmin).toBe('Admin User');
    expect(row.role).toBe('Consultant');
    expect(row.peer360Count).toBe(2);
    expect(component.attachmentCount(row)).toBe(6);

    component.searchTerm = 'sam@example.com';
    expect(component.filteredUnassigned.map(e => e.employeeName)).toEqual(['sam@example.com']);

    component.selectedCycleId = '';
    expect(component.canRemindEmployee(row)).toBeFalse();
    expect(component.employeeReminderDisabledReason(row)).toBe('No cycle selected');
    expect(component.canRemindManager(row)).toBeFalse();
    expect(component.managerReminderDisabledReason(row)).toBe('No cycle selected');
    component.sendReminder(row, 'employee');
    component.sendReminder(row, 'manager');
    expect(notificationService.sendManualReminder).not.toHaveBeenCalled();

    component.selectedCycleId = 'cycle-1';
    component.startReview(row);
    expect(router.navigate).toHaveBeenCalledWith(['/review/start/manager'], {
      queryParams: { subjectUserId: 'emp-1', cycleId: 'cycle-1', adminOverride: 'true' },
    });

    component.ngOnDestroy();
  });

  it('parses reminder error payloads and status errors into user-facing toasts', () => {
    const { component } = buildComponent();
    const row = firstRow(component);

    notificationService.sendManualReminder.and.returnValue(throwError(() => ({
      error: JSON.stringify({
        skipped: [{ recipientUserId: 'emp-1', reason: 'FORBIDDEN' }],
      }),
    })));
    component.sendReminder(row, 'employee');
    expect(toastMessages(component)).toContain('You do not have permission to remind Jane Smith.');

    notificationService.sendManualReminder.and.returnValue(throwError(() => ({
      error: { text: JSON.stringify({ skipped: [{ recipientUserId: 'manager-1' }] }) },
    })));
    component.sendReminder(row, 'manager');
    expect(toastMessages(component)).toContain('No reminder needed for manager Sarah Jones.');

    notificationService.sendManualReminder.and.returnValue(throwError(() => ({ status: 403 })));
    component.sendReminder(row, 'employee');
    expect(toastMessages(component)).toContain('You do not have permission to send this reminder.');

    notificationService.sendManualReminder.and.returnValue(throwError(() => ({ error: { message: 'Lambda is down' } })));
    component.sendReminder(row, 'employee');
    expect(toastMessages(component)).toContain('Lambda is down');

    notificationService.sendManualReminder.and.returnValue(throwError(() => ({ error: '{bad json' })));
    component.sendReminder(row, 'employee');
    expect(toastMessages(component)).toContain('Could not send reminder. Please try again.');

    component.ngOnDestroy();
  });

  it('opens review modals, maps answers, and closes modal state', () => {
    const { component } = buildComponent([], [manager, employee], [activeCycleWithMilestones]);
    const row = firstRow(component);
    employeeService.getReviewsBySubject.and.returnValue(of([
      review({ answers: [{ questionId: 'sq1', response: ['A', 'B'] }] }),
      review({
        reviewId: 'manager-review',
        reviewType: 'MANAGER',
        status: 'COMPLETED',
        answers: [{ questionId: 'mq1', response: 'Manager answer' }],
      }),
      review({
        reviewId: 'peer-review',
        reviewType: 'PEER_360',
        status: 'PUBLISHED',
        answers: [{ questionId: 'pq1', response: '' }],
      }),
      review({
        reviewId: 'peer-draft',
        reviewType: 'PEER_360',
        status: 'DRAFT',
        answers: [{ questionId: 'pq1', response: 'Hidden' }],
      }),
    ] as any));

    component.openSelfReview(row);
    expect(component.modalType).toBe('self');
    expect(component.modalQuestions.map(q => q.questionId)).toEqual(['sq1']);
    expect(component.getModalAnswer('sq1')).toBe('A, B');
    component.modalAnswers = { sq1: [] };
    expect(component.getModalAnswer('sq1')).toBe('—');
    component.modalAnswers = { sq1: ['A', 'B'] };
    expect(component.getModalAnswer('missing')).toBe('—');

    component.openManagerReview(row);
    expect(component.modalType).toBe('manager');
    expect(component.getModalAnswer('mq1')).toBe('Manager answer');

    component.openPeer360Reviews(row);
    expect(component.modalType).toBe('peer360');
    expect(component.modalPeerReviews.length).toBe(1);
    expect(component.getPeerAnswer(component.modalPeerReviews[0], 'pq1')).toBe('—');
    expect(component.getPeerAnswer({ answers: [{ questionId: 'pq1', response: ['One'] }] }, 'pq1')).toBe('One');
    expect(component.getPeerAnswer({ answers: [{ questionId: 'pq1', response: [] }] }, 'pq1')).toBe('—');
    expect(component.getPeerAnswer({}, 'pq1')).toBe('—');
    expect(component.getPeerAnswer({ answers: [] }, 'missing')).toBe('—');

    component.closeModal();
    expect(component.modalOpen).toBeFalse();
    expect(component.modalError).toBeNull();

    component.ngOnDestroy();
  });

  it('falls back to assigned-role milestones and handles reviews without answers', () => {
    const cycleWithRoleFallback = {
      ...activeCycle,
      milestones: [
        {
          assignedRoles: ['MANAGER'],
          templateSnapshot: { questions: [{ questionId: 'mq-role', text: 'Role manager?' }] },
        },
        {
          assignedRoles: ['EMPLOYEE'],
          templateSnapshot: { questions: [{ questionId: 'eq-role', text: 'Role employee?' }] },
        },
      ],
    };
    const { component } = buildComponent([], [manager, employee], [cycleWithRoleFallback]);
    const row = firstRow(component);
    employeeService.getReviewsBySubject.and.returnValue(of([
      review({ reviewType: 'MANAGER', answers: undefined }),
    ] as any));

    component.openManagerReview(row);
    expect(component.modalQuestions.map(q => q.questionId)).toEqual(['mq-role']);
    expect(component.getModalAnswer('mq-role')).toBe('—');

    component.openPeer360Reviews(row);
    expect(component.modalQuestions.map(q => q.questionId)).toEqual(['eq-role']);

    component.ngOnDestroy();
  });

  it('opens AI artifact modals for missing, completed, pending, and failed jobs', () => {
    const { component } = buildComponent([], [manager, employee]);
    const row = {
      ...firstRow(component),
      managerGoldenJobId: 'golden-1',
      insightsJobId: 'insights-1',
      peer360DigestJobId: 'digest-1',
    } as any;

    component.openGoldenReview({ ...row, managerGoldenJobId: null });
    expect(component.modalError).toBe('No AI golden review has been generated for this employee yet.');
    expect(component.modalLoading).toBeFalse();

    component.openEmployeeGolden({ ...row, insightsJobId: null });
    expect(component.modalError).toBe('No AI insights has been generated for this employee yet.');

    component.openPeer360Digest({ ...row, peer360DigestJobId: null });
    expect(component.modalError).toBe('No AI peer-360 digest has been generated for this employee yet.');

    component.openGoldenReview(row);
    expect(component.modalGolden).toEqual(jasmine.objectContaining({ overallRating: 4 }));

    aiReviewService.getManagerGolden.and.returnValue(of({ status: 'PROCESSING' } as any));
    component.openGoldenReview(row);
    expect(component.modalError).toBe('Golden review is processing.');

    aiReviewService.getManagerGolden.and.returnValue(throwError(() => ({})));
    component.openGoldenReview(row);
    expect(component.modalError).toBe('Failed to load golden review.');

    aiReviewService.getInsightsStatus.and.returnValue(of({ jobId: 'insights-1', status: 'COMPLETED', result: { summary: 'Done' } } as any));
    component.openEmployeeGolden(row);
    expect(component.modalInsights).toEqual(jasmine.objectContaining({ summary: 'Done' }));

    aiReviewService.getInsightsStatus.and.returnValue(of({ jobId: 'insights-1', status: 'PROCESSING', errorMessage: 'Still working' } as any));
    component.openEmployeeGolden(row);
    expect(component.modalError).toBe('Still working');

    aiReviewService.getInsightsStatus.and.returnValue(of({ jobId: 'insights-1', status: 'PROCESSING' } as any));
    component.openEmployeeGolden(row);
    expect(component.modalError).toBe('Insights are processing.');

    aiReviewService.getInsightsStatus.and.returnValue(throwError(() => ({})));
    component.openEmployeeGolden(row);
    expect(component.modalError).toBe('Failed to load insights.');

    aiReviewService.getPeer360Digest.and.returnValue(of({ status: 'FAILED' } as any));
    component.openPeer360Digest(row);
    expect(component.modalError).toBe('Peer-360 digest is failed.');

    aiReviewService.getPeer360Digest.and.returnValue(of({
      status: 'COMPLETED',
      result: { themes: ['Support'], consensus: [], dissent: [], overallSentiment: 'positive' },
    } as any));
    component.openPeer360Digest(row);
    expect(component.modalDigest).toEqual(jasmine.objectContaining({ overallSentiment: 'positive' }));

    aiReviewService.getPeer360Digest.and.returnValue(throwError(() => new Error('digest gone')));
    component.openPeer360Digest(row);
    expect(component.modalError).toBe('digest gone');

    aiReviewService.getPeer360Digest.and.returnValue(throwError(() => ({})));
    component.openPeer360Digest(row);
    expect(component.modalError).toBe('Failed to load peer-360 digest.');

    component.ngOnDestroy();
  });

  it('covers publish notification plural outcomes and private formatting fallbacks', () => {
    const { component } = buildComponent([], [manager, employee, secondEmployee]);
    const rows = component.managerGroups[0].employees as any[];
    component.selectedIds = new Set(rows.map(row => row.employeeId));
    http.post.and.returnValues(...rows.map(row => of({
      notificationJobs: [{ jobId: `PUBLISHED#cycle-1#${row.employeeId}`, recipientUserId: row.employeeId }],
    })));
    notificationService.getJobStatus.and.callFake((jobId: string) => of({
      status: jobId.includes('emp-1') ? 'SKIPPED' : 'FAILED',
      materialized: true,
    } as any));

    component.publishSelected();

    expect(toastMessages(component)).toContain('Publish notification skipped for Jane Smith.');
    expect(toastMessages(component)).toContain('Publish notification could not be sent to Alex Kim.');
    (component as any).showPublishDeliveryResultToasts(
      new Map([
        ['missing-1', 'COMPLETED'],
        ['missing-2', 'SKIPPED'],
        ['missing-3', 'SKIPPED'],
        ['missing-4', 'FAILED'],
        ['missing-5', 'FAILED'],
      ]),
      new Map([
        ['missing-2', 'Recipient A'],
        ['missing-3', 'Recipient B'],
        ['missing-4', 'Recipient C'],
        ['missing-5', 'Recipient D'],
      ]),
    );
    expect(toastMessages(component)).toContain('Publish notification sent to the recipient.');
    expect(toastMessages(component)).toContain('Publish notifications skipped for Recipient A and Recipient B.');
    expect(toastMessages(component)).toContain('Publish notifications could not be sent to Recipient C and Recipient D.');
    expect((component as any).publishRecipientName(undefined, [])).toBe('the recipient');
    expect((component as any).publishRecipientName(undefined, [rows[0]])).toBe('Jane Smith');
    expect((component as any).publishRecipientName('manager-1', [])).toBe('Sarah Jones');
    expect((component as any).recipientName('unknown', { employeeName: 'Fallback', managerName: '' }, 'manager')).toBe('the recipient');
    expect((component as any).recipientName('unknown', { employeeName: 'Fallback', managerName: '' }, 'employee')).toBe('Fallback');
    expect((component as any).userName({ email: 'only@example.com' })).toBe('only@example.com');
    expect((component as any).userName({ userId: 'user-only' })).toBe('user-only');
    expect((component as any).userName({})).toBe('the recipient');

    component.ngOnDestroy();
  });

  it('covers plural reminder delivery toasts and pending status fallback', () => {
    const { component } = buildComponent();
    const row = firstRow(component);

    (component as any).showReminderDeliveryResultToast(new Map([
      ['job-1', 'COMPLETED'],
      ['job-2', 'COMPLETED'],
    ]));
    expect(toastMessages(component)).toContain('2 reminders sent.');

    (component as any).showReminderDeliveryResultToast(new Map([
      ['job-3', 'SKIPPED'],
      ['job-4', 'SKIPPED'],
    ]));
    expect(toastMessages(component)).toContain('2 reminders skipped.');

    notificationService.sendManualReminder.and.returnValue(of({
      created: [{ jobId: 'job-pending', recipientUserId: 'emp-1', status: 'PENDING' }],
    }));
    notificationService.getJobStatus.and.returnValue(of({ materialized: false } as any));
    component.sendReminder(row, 'employee');
    expect(notificationService.getJobStatus).toHaveBeenCalledWith('job-pending');

    component.ngOnDestroy();
  });

  it('exports modal content for standard and peer360 modes and removes existing print iframe', () => {
    const { component } = buildComponent([], [manager, employee], [activeCycleWithMilestones]);
    const oldIframe = document.createElement('iframe');
    oldIframe.id = '__modal_print__';
    document.body.appendChild(oldIframe);
    component.modalTitle = 'Employee Self Review';
    component.modalSubtitle = 'Jane Smith';
    component.modalSubmittedDate = '1 Mar 2026';
    component.modalQuestions = [{ questionId: 'sq1', text: 'Self impact?' }];
    component.modalAnswers = { sq1: '' };

    component.exportModal();

    expect(document.querySelectorAll('#__modal_print__').length).toBe(1);
    expect(document.title).toContain('Jane_Smith');
    document.getElementById('__modal_print__')?.remove();

    component.modalType = 'peer360';
    component.modalPeerReviews = [{ answers: [{ questionId: 'pq1', response: 'Helpful' }] }];
    component.modalQuestions = [{ questionId: 'pq1', text: 'Peer feedback?' }];
    component.exportModal();
    expect(document.getElementById('__modal_print__')).not.toBeNull();
    document.getElementById('__modal_print__')?.remove();

    component.ngOnDestroy();
  });

  it('marks a single row published and polls returned publish notification job', () => {
    const { component } = buildComponent();
    const row = firstRow(component);
    http.post.and.returnValue(of({
      notificationJobs: [{
        jobId: 'PUBLISHED#cycle-1#emp-1',
        taskType: 'REVIEW_PUBLISHED',
        status: 'PENDING',
        recipientUserId: 'emp-1',
        materialized: false,
      }],
    }));

    component.publish(row);

    expect(row.publishStatus).toBe('PUBLISHED');
    expect(row.managerReviewStatus).toBe('PUBLISHED');
    expect(toastMessages(component)).toContain('Published Jane Smith.');
    expect(toastMessages(component)).toContain('Publish notification queued for Jane Smith.');
    expect(notificationService.getJobStatus).toHaveBeenCalledWith('PUBLISHED#cycle-1#emp-1');

    component.ngOnDestroy();
  });

  it('marks a single row published without polling when response has no notification jobs', () => {
    const { component } = buildComponent();
    const row = firstRow(component);
    http.post.and.returnValue(of({ message: 'Bundle published successfully' }));

    component.publish(row);

    expect(row.publishStatus).toBe('PUBLISHED');
    expect(toastMessages(component)).toContain('Published Jane Smith.');
    expect(notificationService.getJobStatus).not.toHaveBeenCalled();

    component.ngOnDestroy();
  });

  it('does not mark a row published or poll when publish fails', () => {
    const { component } = buildComponent();
    const row = firstRow(component);
    http.post.and.returnValue(of(null));

    component.publish(row);

    expect(row.publishStatus).toBe('NOT_PUBLISHED');
    expect(notificationService.getJobStatus).not.toHaveBeenCalled();
    expect(toastMessages(component)).not.toContain('Published Jane Smith.');

    component.ngOnDestroy();
  });

  it('bulk publish marks successful rows and polls only successful publish notification jobs', () => {
    const { component } = buildComponent([], [manager, employee, secondEmployee]);
    const rows = component.managerGroups[0].employees as any[];
    component.selectedIds = new Set(rows.map(row => row.employeeId));
    http.post.and.returnValues(
      of({
        notificationJobs: [{
          jobId: 'PUBLISHED#cycle-1#emp-1',
          taskType: 'REVIEW_PUBLISHED',
          status: 'PENDING',
          recipientUserId: 'emp-1',
        }],
      }),
      of(null)
    );

    component.publishSelected();

    expect(rows[0].publishStatus).toBe('PUBLISHED');
    expect(rows[1].publishStatus).toBe('NOT_PUBLISHED');
    expect(component.selectedIds.has('emp-1')).toBeFalse();
    expect(component.selectedIds.has('emp-3')).toBeTrue();
    expect(toastMessages(component)).toContain('Published 1 review bundle.');
    expect(notificationService.getJobStatus).toHaveBeenCalledWith('PUBLISHED#cycle-1#emp-1');

    component.ngOnDestroy();
  });

  it('bulk publish handles successful responses without notification jobs', () => {
    const { component } = buildComponent([], [manager, employee]);
    const row = firstRow(component);
    component.selectedIds = new Set([row.employeeId]);
    http.post.and.returnValue(of({ message: 'Published without notification jobs' }));

    component.publishSelected();

    expect(row.publishStatus).toBe('PUBLISHED');
    expect(toastMessages(component)).toContain('Published 1 review bundle.');
    expect(notificationService.getJobStatus).not.toHaveBeenCalled();

    component.ngOnDestroy();
  });

  it('shows sent toast with employee name when publish notification completes', () => {
    const { component } = buildComponent();
    const row = firstRow(component);
    http.post.and.returnValue(of({
      notificationJobs: [{ jobId: 'PUBLISHED#cycle-1#emp-1', recipientUserId: 'emp-1' }],
    }));
    notificationService.getJobStatus.and.returnValue(of({
      jobId: 'PUBLISHED#cycle-1#emp-1',
      taskType: 'REVIEW_PUBLISHED',
      status: 'COMPLETED',
      materialized: true,
    }));

    component.publish(row);

    expect(toastMessages(component)).toContain('Publish notification sent to Jane Smith.');

    component.ngOnDestroy();
  });

  it('shows skipped toast with employee name when publish notification skips', () => {
    const { component } = buildComponent();
    const row = firstRow(component);
    http.post.and.returnValue(of({
      notificationJobs: [{ jobId: 'PUBLISHED#cycle-1#emp-1', recipientUserId: 'emp-1' }],
    }));
    notificationService.getJobStatus.and.returnValue(of({
      jobId: 'PUBLISHED#cycle-1#emp-1',
      taskType: 'REVIEW_PUBLISHED',
      status: 'SKIPPED',
      materialized: true,
    }));

    component.publish(row);

    expect(toastMessages(component)).toContain('Publish notification skipped for Jane Smith.');

    component.ngOnDestroy();
  });

  it('shows error toast with employee name when publish notification fails', () => {
    const { component } = buildComponent();
    const row = firstRow(component);
    http.post.and.returnValue(of({
      notificationJobs: [{ jobId: 'PUBLISHED#cycle-1#emp-1', recipientUserId: 'emp-1' }],
    }));
    notificationService.getJobStatus.and.returnValue(of({
      jobId: 'PUBLISHED#cycle-1#emp-1',
      taskType: 'REVIEW_PUBLISHED',
      status: 'FAILED',
      materialized: true,
    }));

    component.publish(row);

    expect(toastMessages(component)).toContain('Publish notification could not be sent to Jane Smith.');

    component.ngOnDestroy();
  });

  it('groups and caps bulk publish delivery outcome names', () => {
    const users = [
      manager,
      employee,
      secondEmployee,
      { ...secondEmployee, userId: 'emp-4', firstName: 'Mina', lastName: 'Shah' },
      { ...secondEmployee, userId: 'emp-5', firstName: 'Owen', lastName: 'Park' },
      { ...secondEmployee, userId: 'emp-6', firstName: 'Pat', lastName: 'Chen' },
    ];
    const { component } = buildComponent([], users);
    const rows = component.managerGroups[0].employees as any[];
    component.selectedIds = new Set(rows.map(row => row.employeeId));
    http.post.and.returnValues(...rows.map(row => of({
      notificationJobs: [{ jobId: `PUBLISHED#cycle-1#${row.employeeId}`, recipientUserId: row.employeeId }],
    })));
    notificationService.getJobStatus.and.returnValue(of({
      taskType: 'REVIEW_PUBLISHED',
      status: 'COMPLETED',
      materialized: true,
    }));

    component.publishSelected();

    expect(toastMessages(component)).toContain(
      'Publish notifications sent to Jane Smith, Alex Kim, Mina Shah, and 2 others.'
    );

    component.ngOnDestroy();
  });
});
