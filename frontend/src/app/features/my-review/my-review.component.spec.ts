import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { of, throwError } from 'rxjs';

import { AuthService } from '../../core/auth/auth.service';
import { EmployeeService, ReviewCycle } from '../../services/employees.services';
import { Question, Review } from '../../models/review.model';
import { MyReviewComponent } from './my-review.component';

describe('MyReviewComponent', () => {
  let employeeService: jasmine.SpyObj<EmployeeService>;
  let authService: jasmine.SpyObj<AuthService>;
  let router: jasmine.SpyObj<Router>;

  const selfQuestions: Question[] = [
    { questionId: 'sq1', text: 'Self impact?', type: 'textarea' as any },
    { questionId: 'sq2', text: 'Self rating?', type: 'rating' as any, scale: 5 },
  ];
  const managerQuestions: Question[] = [
    { questionId: 'mq1', text: 'Manager comments?', type: 'textarea' as any },
  ];
  const peerQuestions: Question[] = [
    { questionId: 'pq1', text: 'Peer collaboration?', type: 'textarea' as any },
  ];

  function cycle(overrides: Partial<ReviewCycle> = {}): ReviewCycle {
    return {
      cycleId: 'cycle-active',
      cycleTitle: 'FY26 Active Review',
      status: 'ACTIVE',
      startDate: '2026-01-01',
      dueDate: '2099-03-31',
      updatedAt: '2026-02-01T00:00:00Z',
      milestones: [
        {
          milestoneId: 'self-ms',
          name: 'Self Assessment',
          assignedRoles: ['EMPLOYEE'],
          startDate: '2026-01-01',
          endDate: '2099-02-15',
          status: 'OPEN',
          templateSnapshot: { templateId: 'tpl-self', reviewType: 'SELF', questions: selfQuestions },
        },
        {
          milestoneId: 'peer-ms',
          name: 'Peer 360 Feedback',
          assignedRoles: ['EMPLOYEE'],
          startDate: '2026-01-01',
          endDate: '2099-02-28',
          status: 'OPEN',
          templateSnapshot: { templateId: 'tpl-peer', reviewType: 'PEER_360', questions: peerQuestions },
        },
        {
          milestoneId: 'manager-ms',
          name: 'Manager Review',
          assignedRoles: ['MANAGER'],
          startDate: '2026-02-01',
          endDate: '2099-03-31',
          status: 'OPEN',
          templateSnapshot: { templateId: 'tpl-manager', reviewType: 'MANAGER', questions: managerQuestions } as any,
        },
      ],
      ...overrides,
    } as ReviewCycle;
  }

  function review(overrides: Partial<Review> = {}): Review {
    return {
      reviewId: 'self-review-1',
      cycleId: 'cycle-active',
      userId: 'user-1',
      subjectUserId: 'user-1',
      authorUserId: 'user-1',
      reviewType: 'SELF',
      status: 'DRAFT',
      submittedAt: '2026-02-01',
      answers: [{ questionId: 'sq1', response: 'Delivered the platform migration.' }],
      ...overrides,
    } as Review;
  }

  function buildComponent(opts: {
    cycles?: ReviewCycle[];
    myReviews?: Review[];
    subjectReviews?: Review[];
    employees?: any[];
    isAdmin?: boolean;
    employeeType?: string;
    failApis?: boolean;
  } = {}) {
    employeeService = jasmine.createSpyObj<EmployeeService>('EmployeeService', [
      'getCycles',
      'getMyReviews',
      'getMySubjectReviews',
      'getEmployees',
      'getUserPublic',
    ]);
    authService = jasmine.createSpyObj<AuthService>('AuthService', [
      'getTokenPayload',
      'isAdmin',
      'getUserEmployeeType',
    ]);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);

    employeeService.getCycles.and.returnValue(
      opts.failApis ? throwError(() => new Error('cycles failed')) : of(opts.cycles ?? [])
    );
    employeeService.getMyReviews.and.returnValue(
      opts.failApis ? throwError(() => new Error('reviews failed')) : of(opts.myReviews ?? [])
    );
    employeeService.getMySubjectReviews.and.returnValue(
      opts.failApis ? throwError(() => new Error('subject reviews failed')) : of(opts.subjectReviews ?? [])
    );
    employeeService.getEmployees.and.returnValue(
      opts.failApis ? throwError(() => new Error('employees failed')) : of(opts.employees ?? [])
    );
    employeeService.getUserPublic.and.callFake(((id: string) => {
      if (opts.failApis) return throwError(() => new Error('public user failed'));
      const found = (opts.employees ?? []).find((e: any) => e.userId === id || String(e.id ?? '') === String(id));
      if (!found?.userId || !found?.firstName || !found?.lastName) return of(null);
      return of({
        userId: found.userId,
        firstName: found.firstName,
        lastName: found.lastName,
        role: found.role ?? 'EMPLOYEE',
      });
    }) as any);
    authService.getTokenPayload.and.returnValue({ sub: 'user-1' } as any);
    authService.isAdmin.and.returnValue(opts.isAdmin ?? false);
    authService.getUserEmployeeType.and.returnValue(opts.employeeType ?? 'EMPLOYEE');

    TestBed.configureTestingModule({
      imports: [MyReviewComponent],
      providers: [
        { provide: EmployeeService, useValue: employeeService },
        { provide: AuthService, useValue: authService },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: convertToParamMap({}),
              queryParamMap: convertToParamMap({}),
            },
          },
        },
        { provide: Router, useValue: router },
      ],
    });

    const fixture = TestBed.createComponent(MyReviewComponent);
    fixture.detectChanges();
    return { fixture, component: fixture.componentInstance };
  }

  // Init builds the active-cycle picker, current action rows, user name map, and past history.
  it('loads active cycles, sorts newest first, builds rows, and renders completed history', () => {
    const older = cycle({ cycleId: 'cycle-old', cycleTitle: 'Old Active', updatedAt: '2026-01-01T00:00:00Z' });
    const newer = cycle();
    const past = cycle({ cycleId: 'cycle-past', cycleTitle: 'Past Review', status: 'COMPLETED' as any });
    const completedSelf = review({
      reviewId: 'self-past',
      cycleId: 'cycle-past',
      status: 'COMPLETED',
      submittedAt: '2026-01-15',
      employeeReview: [{ questionId: 'overall_rating', answer: 'Outstanding' }] as any,
    } as any);
    const managerPast = review({
      reviewId: 'manager-past',
      cycleId: 'cycle-past',
      userId: 'mgr-1',
      authorUserId: 'mgr-1',
      subjectUserId: 'user-1',
      reviewType: 'MANAGER',
      status: 'PUBLISHED',
      submittedAt: '2026-01-20',
    });

    const { component } = buildComponent({
      cycles: [older, newer, past],
      myReviews: [review()],
      subjectReviews: [completedSelf, managerPast],
      employees: [
        { userId: 'user-1', firstName: 'Uma', lastName: 'User' },
        { userId: 'subject-2', firstName: 'Pat', lastName: 'Peer' },
      ],
    });

    expect(component.loading).toBeFalse();
    expect(component.availableActiveCycles.map(c => c.cycleId)).toEqual(['cycle-active', 'cycle-old']);
    expect(component.selectedCycleId).toBe('cycle-active');
    expect(component.actionRows.map(r => r.reviewType)).toContain('SELF');
    expect(component.selfReviewStat).toBe('Draft');
    expect(component.reviewHistory.length).toBe(1);
    expect(component.reviewHistory[0].managerReviewStatus).toBe('Completed');
    expect(component.hasManagerReview(component.reviewHistory[0])).toBeTrue();
  });

  it('falls back to an empty loaded state when startup APIs fail', () => {
    const { component } = buildComponent({ failApis: true });

    expect(component.loading).toBeFalse();
    expect(component.availableActiveCycles).toEqual([]);
    expect(component.actionRows).toEqual([]);
    expect(component.reviewHistory).toEqual([]);
  });

  // Action rows are the main business surface: self actions, the published manager
  // review surfaced from the subject feed, and peer assignments for the active cycle.
  it('builds self, published-manager, and peer360 assignment action rows', () => {
    const active = cycle();
    const peerAssignment = review({
      reviewId: 'peer-assignment',
      reviewType: 'PEER_360',
      status: 'IN_PROGRESS',
      userId: 'subject-2',
      subjectUserId: 'subject-2',
      authorUserId: 'user-1',
      templateId: 'tpl-peer',
    });
    const publishedManager = review({
      reviewId: 'manager-published',
      userId: 'mgr-1',
      authorUserId: 'mgr-1',
      subjectUserId: 'user-1',
      reviewType: 'MANAGER',
      status: 'PUBLISHED',
    });

    const { component } = buildComponent({
      cycles: [active],
      myReviews: [review(), peerAssignment],
      subjectReviews: [publishedManager],
      employees: [{ userId: 'subject-2', firstName: 'Pat', lastName: 'Peer' }],
    });

    expect(component.actionRows.length).toBe(3);
    const peerRow = component.actionRows.find(r => r.reviewType === 'PEER_360')!;
    expect(peerRow.title).toBe('Peer Review - Pat Peer');
    expect(peerRow.status).toBe('IN_PROGRESS');
    expect(component.actionLabel(peerRow)).toBe('Continue Peer Review');
    expect(component.actionBtnClass(peerRow)).toBe('dark');

    // The PUBLISHED manager review (authored by the manager, so present only in the
    // subject feed) is surfaced as a read-only, viewable row and flips the card to
    // "Completed". Before the fix the row was missing and the card stuck on "Pending".
    const managerRow = component.actionRows.find(r => r.reviewType === 'MANAGER')!;
    expect(managerRow).withContext('published manager review must surface for the active cycle').toBeTruthy();
    expect(managerRow.status).toBe('PUBLISHED');
    expect(managerRow.existingReview?.reviewId).toBe('manager-published');
    expect(component.canView(managerRow)).toBeTrue();
    expect(component.actionLabel(managerRow)).toBe('View Manager Review');
    expect(component.managerReviewStat).toBe('Completed');
    expect(component.managerReviewStatClass).toBe('stat-green');
  });

  // Publishing is the visibility gate: a COMPLETED-but-unpublished manager review
  // is in the subject feed too, but must stay hidden from the employee until the
  // admin/manager publishes it.
  it('does not surface a manager review that is COMPLETED but not yet PUBLISHED (no pre-publish leak)', () => {
    const completedManager = review({
      reviewId: 'manager-completed',
      userId: 'mgr-1',
      authorUserId: 'mgr-1',
      subjectUserId: 'user-1',
      reviewType: 'MANAGER',
      status: 'COMPLETED',
    });

    const { component } = buildComponent({
      cycles: [cycle()],
      myReviews: [review()],
      subjectReviews: [completedManager],
    });

    expect(component.actionRows.find(r => r.reviewType === 'MANAGER')).toBeUndefined();
    expect(component.managerReviewStat).toBe('Pending');
    expect(component.managerReviewStatClass).toBe('stat-muted');
  });

  it('switches cycles without refetching and rebuilds action rows for the selected cycle', () => {
    const cycleA = cycle({ cycleId: 'cycle-a', updatedAt: '2026-01-01T00:00:00Z' });
    const cycleB = cycle({ cycleId: 'cycle-b', updatedAt: '2026-02-01T00:00:00Z' });
    const { component } = buildComponent({
      cycles: [cycleA, cycleB],
      myReviews: [review({ cycleId: 'cycle-a', status: 'COMPLETED' })],
    });

    expect(component.selectedCycleId).toBe('cycle-b');
    expect(component.selfReviewStat).toBe('Not Started');

    component.onCycleChange('cycle-a');

    expect(component.activeCycle?.cycleId).toBe('cycle-a');
    expect(component.selfReviewStat).toBe('Completed');
    expect(employeeService.getCycles).toHaveBeenCalledTimes(1);
  });

  // Navigation must respect read-only manager reviews, editable reviews, locked rows, and expired milestones.
  it('navigates to the correct review form for editable and read-only rows', () => {
    const { component } = buildComponent({ cycles: [cycle()], myReviews: [review()] });
    const selfRow = component.actionRows.find(r => r.reviewType === 'SELF')!;
    component.openReview(selfRow);
    expect(router.navigate).toHaveBeenCalledWith(['/my-review/fill', 'self'], {
      queryParams: jasmine.objectContaining({ cycleId: 'cycle-active', milestoneId: 'self-ms', reviewId: 'self-review-1' }),
    });

    const managerRow = {
      ...selfRow,
      reviewType: 'MANAGER' as const,
      status: 'PUBLISHED' as const,
      existingReview: review({ reviewId: 'mgr-published', reviewType: 'MANAGER' }),
    };
    component.openReview(managerRow as any);
    expect(router.navigate).toHaveBeenCalledWith(['/my-review/fill', 'manager'], {
      queryParams: jasmine.objectContaining({
        cycleId: 'cycle-active',
        milestoneId: 'self-ms',
        reviewId: 'mgr-published',
      }),
    });
  });

  it('does not navigate for locked or expired rows and labels them clearly', () => {
    const expiredCycle = cycle({
      milestones: [
        {
          ...cycle().milestones![0],
          endDate: '2000-01-01',
        },
      ] as any,
    });
    const { component } = buildComponent({ cycles: [expiredCycle] });
    const expiredRow = component.actionRows[0];
    router.navigate.calls.reset();

    expect(component.canAct(expiredRow)).toBeFalse();
    expect(component.actionLabel(expiredRow)).toBe('Deadline Passed');
    expect(component.actionBtnClass(expiredRow)).toBe('muted');
    component.openReview(expiredRow);
    expect(router.navigate).not.toHaveBeenCalled();

    const lockedRow = { ...expiredRow, status: 'LOCKED' as const, milestone: { ...expiredRow.milestone, endDate: '2099-01-01' } };
    expect(component.actionLabel(lockedRow as any)).toBe('Awaiting Review');
    component.openReview(lockedRow as any);
    expect(router.navigate).not.toHaveBeenCalled();
  });

  // History modal chooses self vs manager records and exposes answer/detail helpers used by the template.
  it('opens and closes history details for self and manager reviews', () => {
    const selfPast = review({ cycleId: 'past', status: 'COMPLETED', managerReview: [{ questionId: 'summary', answer: 'Good growth' }] as any } as any);
    const managerPast = review({ cycleId: 'past', reviewType: 'MANAGER', status: 'PUBLISHED', subjectUserId: 'user-1' });
    const { component } = buildComponent({
      cycles: [cycle({ cycleId: 'past', status: 'COMPLETED' as any })],
      subjectReviews: [selfPast, managerPast],
    });
    const item = component.reviewHistory[0];

    component.openHistoryDetails(item);
    expect(component.selectedModalLabel).toBe('Self Review');
    expect(component.selectedModalReview).toBe(item.review);

    component.openHistoryDetails(item, 'manager');
    expect(component.selectedModalLabel).toBe('Manager Review');
    expect(component.selectedModalReview).toBe(item.managerReview!);

    component.closeHistoryDetails();
    expect(component.showHistoryDetails).toBeFalse();
    expect(component.selectedHistory).toBeNull();
  });

  it('returns question metadata, history answers, detail fields, and rating classes', () => {
    const pastCycle = cycle({ cycleId: 'past', status: 'COMPLETED' as any });
    const selfPast = review({
      cycleId: 'past',
      status: 'COMPLETED',
      answers: [
        { questionId: 'sq1', response: ['A', 'B'] },
        { questionId: 'sq2', response: 4 },
      ],
      adminReview: [
        { questionId: 'overall_rating', answer: 'Outstanding' },
        { questionId: 'reviewed_by', answer: 'Admin One' },
        { questionId: 'summary', answer: 'Summary text' },
        { questionId: 'strengths', answer: ['Ownership'] },
      ] as any,
    } as any);
    const { component } = buildComponent({ cycles: [pastCycle], subjectReviews: [selfPast] });
    const item = component.reviewHistory[0];

    expect(component.getQuestionText(item, 'sq1')).toBe('Self impact?');
    expect(component.isRatingAnswer(item, 'sq2')).toBeTrue();
    expect(component.getRatingScale(item, 'sq2')).toBe(5);
    expect(component.ratingArrayFor(3)).toEqual([1, 2, 3]);
    expect(component.getHistoryQuestions(item).map(q => q.questionId)).toContain('sq1');
    expect(component.getHistoryAnswer(item, 'sq1')).toBe('A, B');
    expect(component.getHistoryAnswer(item, 'missing')).toBe('Not answered');
    expect(component.getDetailOverallRating(item)).toBe('Outstanding');
    expect(component.getDetailReviewedBy(item)).toBe('Admin One');
    expect(component.getDetailSummary(item)).toBe('Summary text');
    expect(component.getDetailList(item, 'strengths')).toEqual(['Ownership']);
    expect(component.getRatingClass('Needs Improvement')).toBe('rating-orange');
    expect(component.getRatingClass('Outstanding')).toBe('rating-green');
    expect(component.getRatingClass('Unknown')).toBe('rating-default');
    expect(component.getHistoryStatusClass('Completed')).toBe('published');
    expect(component.getHistoryStatusClass('Pending')).toBe('pending');
  });

  // Export uses the same metadata helpers as the modal and must clean up an existing iframe.
  it('exports history to a hidden print iframe and handles null export safely', () => {
    const pastCycle = cycle({ cycleId: 'past', status: 'COMPLETED' as any });
    const selfPast = review({ cycleId: 'past', status: 'COMPLETED', answers: [{ questionId: 'sq2', response: 5 }] });
    const { component } = buildComponent({ cycles: [pastCycle], subjectReviews: [selfPast] });
    const item = component.reviewHistory[0];

    component.exportHistoryToPdf(null);
    expect(document.getElementById('__print_iframe__')).toBeNull();

    component.exportHistoryToPdf(item);

    const iframe = document.getElementById('__print_iframe__') as HTMLIFrameElement | null;
    expect(iframe).not.toBeNull();
    expect(document.title).toContain('Self_Review');
    iframe?.remove();
  });

  // Smaller helpers still drive visible labels and fallback text in the page.
  it('covers status, date, toast, popup, and peer subject fallback helpers', () => {
    jasmine.clock().install();
    try {
      const { component } = buildComponent();

      component.showToast('Saved');
      expect(component.toastVisible).toBeTrue();
      jasmine.clock().tick(3000);
      expect(component.toastVisible).toBeFalse();

      component.showPendingPopup('Wait for manager review');
      expect(component.pendingPopupVisible).toBeTrue();
      component.closePendingPopup();
      expect(component.pendingPopupVisible).toBeFalse();

      expect(component.subjectName(undefined)).toBe('Team Member');
      expect(component.displayActionStatus('IN_PROGRESS')).toBe('In Progress');
      expect(component.displayActionStatus('CUSTOM')).toBe('CUSTOM');
      expect(component.formatDate('2026-01-15')).toContain('2026');
      expect(component.formatDate('not-a-date')).toBe('—');
      expect(component.formatPeriod(cycle())).toContain('2099');
      expect(component.formatPeriod(undefined)).toBe('—');
    } finally {
      jasmine.clock().uninstall();
    }
  });

  // Private mapping helpers are covered because they determine row type/status and history visibility.
  it('maps review types, statuses, dates, and history predicates consistently', () => {
    const { component } = buildComponent();

    expect((component as any).milestoneToReviewType('Manager Review')).toBe('MANAGER');
    expect((component as any).milestoneToReviewType('360 Feedback')).toBe('PEER_360');
    expect((component as any).milestoneToReviewType('HR Review')).toBe('PEER_360');
    expect((component as any).milestoneToReviewType('Self Assessment')).toBe('SELF');
    expect((component as any).titleFor('PEER_360')).toBe('Peer 360 Review');
    expect((component as any).subtitleFor('PEER_360')).toBe('Completed by a teammate (360 feedback)');

    expect((component as any).normalizeActionStatus('PUBLISHED')).toBe('PUBLISHED');
    expect((component as any).normalizeActionStatus('COMPLETE')).toBe('COMPLETED');
    expect((component as any).normalizeActionStatus('SUBMITTED')).toBe('COMPLETED');
    expect((component as any).normalizeActionStatus('PENDING')).toBe('PENDING');
    expect((component as any).normalizeActionStatus('unknown')).toBe('NOT_STARTED');

    expect((component as any).displayStatus('PUBLISHED')).toBe('Completed');
    expect((component as any).displayStatus('DRAFT')).toBe('Draft');
    expect((component as any).displayStatus(null)).toBe('Pending');
    expect((component as any).isHistoryReview(review({ status: 'COMPLETED' }), 'user-1')).toBeTrue();
    expect((component as any).isHistoryReview(review({ status: 'DRAFT' }), 'user-1')).toBeFalse();
    expect((component as any).isHistoryReview(review({ userId: 'other', subjectUserId: 'other', status: 'COMPLETED' }), 'user-1')).toBeFalse();

    expect((component as any).parseDate('15/03/2026').getFullYear()).toBe(2026);
    expect((component as any).daysBetween(new Date('2026-01-01'), new Date('2026-01-03'))).toBe(2);
    expect((component as any).daysBetween(new Date('2026-01-01'), new Date('bad'))).toBe(0);
  });

  it('handles manager/self review status fallbacks used by history rows', () => {
    const { component } = buildComponent();

    expect((component as any).resolveSelfReviewStatus({ employeeReview: [{ questionId: 'q', answer: 'a' }] })).toBe('Completed');
    expect((component as any).resolveSelfReviewStatus({ reviewType: 'SELF', status: 'DRAFT' })).toBe('Draft');
    expect((component as any).resolveSelfReviewStatus({ reviewType: 'MANAGER', status: 'PUBLISHED' })).toBe('Completed');
    expect((component as any).resolveSelfReviewTime({ submittedTime: 'a', submittedAt: 'b' })).toBe('a');
    expect((component as any).resolveSelfReviewTime({ updatedAt: 'u' })).toBe('u');

    expect((component as any).resolveManagerReviewStatus({ managerReview: [{ questionId: 'q', answer: 'a' }] })).toBe('Published');
    expect((component as any).resolveManagerReviewStatus({ adminReview: [{ questionId: 'q', answer: 'a' }] })).toBe('Published');
    expect((component as any).resolveManagerReviewStatus({ reviewType: 'PEER_360' })).toBe('Published');
    expect((component as any).resolveManagerReviewStatus({ reviewType: 'SELF' })).toBe('Pending');
    expect((component as any).resolveManagerReviewTime({ reviewType: 'SELF', submittedAt: 'x' })).toBeNull();
    expect((component as any).resolveManagerReviewTime({ reviewType: 'MANAGER', submittedAt: 'x' })).toBe('x');
    expect((component as any).resolveSortTime({ createdAt: 'c' })).toBe('c');
  });

  it('builds peer360 rows with subject and milestone fallbacks', () => {
    const { component } = buildComponent({ employees: [{ id: 7, email: 'fallback@example.com' }] });
    const active = cycle({
      milestones: [
        { milestoneId: 'generic', name: 'Generic 360', assignedRoles: ['EMPLOYEE'], endDate: '2099-01-01' } as any,
      ],
    });
    const rows = (component as any).buildPeer360Rows(active, [
      { reviewId: 'no-author', reviewType: 'PEER_360', cycleId: 'cycle-active', status: 'SUBMITTED' },
      { reviewId: 'other-author', reviewType: 'PEER_360', cycleId: 'cycle-active', userId: '8', authorUserId: 'someone' },
    ], 'user-1');

    expect(rows.length).toBe(1);
    expect(rows[0].peer360SubjectName).toBe('Team Member');
    expect(rows[0].milestone.milestoneId).toBe('generic');
    expect(rows[0].status).toBe('COMPLETED');
  });

  it('uses stat fallbacks and view button classes when rows are missing or completed', () => {
    const { component } = buildComponent();
    expect(component.selfReviewStat).toBe('Not Started');
    expect(component.selfReviewStatClass).toBe('stat-orange');
    expect(component.managerReviewStat).toBe('Pending');
    expect(component.managerReviewStatClass).toBe('stat-muted');

    component.actionRows = [
      { reviewType: 'SELF', title: 'Self', subtitle: '', iconClass: 'self', milestone: cycle().milestones![0], status: 'COMPLETED', daysRemaining: 0 },
      { reviewType: 'MANAGER', title: 'Manager', subtitle: '', iconClass: 'manager', milestone: cycle().milestones![2], status: 'COMPLETED', daysRemaining: 0 },
    ] as any;

    expect(component.selfReviewStatClass).toBe('stat-green');
    expect(component.managerReviewStat).toBe('Completed');
    expect(component.managerReviewStatClass).toBe('stat-green');
    expect(component.canView(component.actionRows[0])).toBeTrue();
    expect(component.actionLabel(component.actionRows[0])).toBe('View Self Review');
    expect(component.actionBtnClass(component.actionRows[0])).toBe('view');
  });

  it('formats empty history/detail helper fallbacks', () => {
    const { component } = buildComponent();

    expect(component.getQuestionMeta(null, 'q')).toBeNull();
    expect(component.getQuestionText(null, 'q')).toBe('');
    expect(component.isRatingAnswer(null, 'q')).toBeFalse();
    expect(component.getRatingScale(null, 'q')).toBe(5);
    expect(component.getHistoryQuestions(null)).toEqual([]);
    expect(component.getHistoryAnswer(null, 'q')).toBe('Not answered');
    expect(component.getDetailReview(null)).toEqual([]);
    expect(component.getAnswer(undefined, 'q')).toBeNull();
    expect(component.getDetailOverallRating(null)).toBe('—');
    expect(component.getDetailReviewedBy(null)).toBe('—');
    expect(component.getDetailSummary(null)).toBe('—');
    expect(component.getDetailList(null, 'x')).toEqual([]);
    expect(component.getDetailList({ review: { employeeReview: [{ questionId: 'x', answer: 'one' }] } } as any, 'x')).toEqual(['one']);
  });

  it('turns milestone status branches into locked/completed/not-started action rows', () => {
    const locked = cycle({
      milestones: [
        { ...cycle().milestones![0], milestoneId: 'locked-self', status: 'NOT_STARTED' },
        { ...cycle().milestones![0], milestoneId: 'done-self', name: 'Self Assessment', status: 'COMPLETE' },
      ] as any,
    });
    const { component } = buildComponent({ cycles: [locked] });

    expect(component.actionRows.map(r => r.status)).toEqual(['LOCKED', 'COMPLETED']);
    expect(component.actionLabel(component.actionRows[0])).toBe('Awaiting Review');
    expect(component.actionLabel(component.actionRows[1])).toBe('View Self Review');
  });

  it('covers action label and button class branches for common row states', () => {
    const { component } = buildComponent();
    const base = { reviewType: 'SELF', title: 'Self', subtitle: '', iconClass: 'self', milestone: cycle().milestones![0], daysRemaining: 1 } as any;

    expect(component.actionLabel({ ...base, status: 'NOT_STARTED' })).toBe('Start Self Review');
    expect(component.actionLabel({ ...base, status: 'DRAFT' })).toBe('Continue Self Review');
    expect(component.actionLabel({ ...base, status: 'IN_PROGRESS' })).toBe('Continue Self Review');
    expect(component.actionLabel({ ...base, status: 'PENDING' })).toBe('Pending');
    expect(component.actionBtnClass({ ...base, status: 'NOT_STARTED' })).toBe('primary');
    expect(component.actionBtnClass({ ...base, reviewType: 'MANAGER', status: 'NOT_STARTED' })).toBe('dark');
    expect(component.canAct({ ...base, status: 'COMPLETED' })).toBeFalse();
    expect(component.canView({ ...base, status: 'PUBLISHED' })).toBeTrue();
    expect(component.isMilestoneExpired({ endDate: '' } as any)).toBeFalse();
  });

  it('builds history from assigned reviews when subject self reviews are absent', () => {
    const past = cycle({ cycleId: 'past', status: 'COMPLETED' as any, dueDate: '2026-01-31' });
    const selfFromMine = review({ cycleId: 'past', status: 'SUBMITTED', submittedAt: '2026-01-22' } as any);
    const { component } = buildComponent({
      cycles: [past],
      myReviews: [selfFromMine],
      subjectReviews: [],
    });

    expect(component.reviewHistory.length).toBe(1);
    expect(component.reviewHistory[0].selfReviewStatus).toBe('Completed');
    expect(component.reviewHistory[0].managerReviewStatus).toBe('Pending');
    expect(component.hasManagerReview(component.reviewHistory[0])).toBeFalse();
  });

  it('finds cycles by legacy id and sorts history by the latest available timestamp', () => {
    const legacyCycle = cycle({ cycleId: 'not-used', id: 'legacy-cycle' } as any);
    const newer = review({ reviewId: 'newer', cycleId: 'legacy-cycle', status: 'COMPLETED', submittedAt: null, updatedAt: '2026-02-01' } as any);
    const older = review({ reviewId: 'older', cycleId: 'legacy-cycle', status: 'COMPLETED', submittedAt: null, createdAt: '2026-01-01' } as any);
    const { component } = buildComponent({
      cycles: [legacyCycle],
      subjectReviews: [older, newer],
    });

    expect(component.reviewHistory[0].review.reviewId).toBe('newer');
    expect(component.reviewHistory[0].cycle?.cycleTitle).toBe('FY26 Active Review');
  });

  it('exports manager history details and empty-answer histories', () => {
    const pastCycle = cycle({ cycleId: 'past', status: 'COMPLETED' as any });
    const selfPast = review({ cycleId: 'past', status: 'COMPLETED', answers: [] });
    const managerPast = review({
      cycleId: 'past',
      reviewId: 'manager-history',
      reviewType: 'MANAGER',
      status: 'PUBLISHED',
      subjectUserId: 'user-1',
      answers: [{ questionId: 'mq1', response: 'Manager answer' }],
    });
    const { component } = buildComponent({ cycles: [pastCycle], subjectReviews: [selfPast, managerPast] });
    const item = component.reviewHistory[0];

    component.openHistoryDetails(item, 'manager');
    component.exportHistoryToPdf(item);

    expect(document.getElementById('__print_iframe__')).not.toBeNull();
    expect(document.title).toContain('Manager_Review');
    document.getElementById('__print_iframe__')?.remove();

    component.openHistoryDetails(item, 'self');
    component.exportHistoryToPdf(item);
    expect(document.getElementById('__print_iframe__')).not.toBeNull();
    document.getElementById('__print_iframe__')?.remove();
  });

  it('derives current user role from admin and manager auth state', () => {
    const admin = buildComponent({ isAdmin: true });
    expect((admin.component as any).currentUserRole).toBe('ADMIN');

    TestBed.resetTestingModule();
    const manager = buildComponent({ employeeType: 'MANAGER' });
    expect((manager.component as any).currentUserRole).toBe('MANAGER');
  });

  it('handles cycle changes to unknown ids and peer-review navigation', () => {
    const peerAssignment = review({
      reviewId: 'peer-assignment',
      reviewType: 'PEER_360',
      status: 'NOT_STARTED',
      userId: 'subject-2',
      subjectUserId: 'subject-2',
      authorUserId: 'user-1',
      templateId: 'tpl-peer',
    });
    const { component } = buildComponent({ cycles: [cycle()], myReviews: [peerAssignment] });
    const peerRow = component.actionRows.find(r => r.reviewType === 'PEER_360')!;

    component.openReview(peerRow);
    expect(router.navigate).toHaveBeenCalledWith(['/my-review/fill', 'peer-360'], {
      queryParams: jasmine.objectContaining({ reviewId: 'peer-assignment', milestoneId: 'peer-ms' }),
    });

    component.onCycleChange('missing-cycle');
    expect(component.activeCycle).toBeNull();
    expect(component.actionRows).toEqual([]);
  });

  it('falls back to a generic peer subject name when public lookup has no display name', () => {
    const { component } = buildComponent({
      cycles: [cycle()],
      myReviews: [
        review({
          reviewId: 'peer-id-fallback',
          reviewType: 'PEER_360',
          status: 'DRAFT',
          userId: '7',
          subjectUserId: '7',
          authorUserId: 'user-1',
          templateId: 'tpl-peer',
        }),
      ],
      employees: [{ id: 7, email: 'fallback@example.com' }],
    });

    expect(component.subjectName('7')).toBe('Team Member');
    expect(component.actionRows.find(r => r.reviewType === 'PEER_360')?.peer360SubjectName).toBe('Team Member');
  });

  it('builds employee-assigned manager rows when the cycle exposes one to the user', () => {
    const managerForEmployeeCycle = cycle({
      milestones: [
        {
          milestoneId: 'employee-manager',
          name: 'Manager Review',
          assignedRoles: ['EMPLOYEE'],
          startDate: '2026-01-01',
          endDate: '2099-01-01',
          status: 'OPEN',
          templateSnapshot: { templateId: 'tpl-manager', reviewType: 'MANAGER', questions: managerQuestions } as any,
        },
      ],
    });
    const { component } = buildComponent({ cycles: [managerForEmployeeCycle] });

    expect(component.actionRows[0].reviewType).toBe('MANAGER');
    expect(component.actionRows[0].title).toBe('Manager Review');
    expect(component.actionRows[0].subtitle).toBe('Completed by your manager');
    expect(component.actionBtnClass(component.actionRows[0])).toBe('dark');
  });

  it('covers remaining history answer and formatting branches', () => {
    const { component } = buildComponent();
    const item = {
      cycle: cycle(),
      review: {
        answers: [
          { questionId: 'blank-array', response: [] },
          { questionId: 'blank-string', response: '   ' },
          { questionId: 'zero', response: 0 },
        ],
      },
    } as any;

    expect(component.getHistoryAnswer(item, 'blank-array')).toBe('Not answered');
    expect(component.getHistoryAnswer(item, 'blank-string')).toBe('Not answered');
    expect(component.getHistoryAnswer(item, 'zero')).toBe('0');
    expect(component.formatPeriod({ startDate: 'bad', dueDate: '2099-01-01' } as any)).toBe('—');
    expect(component.getRatingClass('Meets Expectations')).toBe('rating-blue');
    expect(component.getRatingClass('Developing')).toBe('rating-yellow');
    expect(component.getRatingClass('Unsatisfactory')).toBe('rating-red');
    expect(component.getHistoryStatusClass('Published')).toBe('published');
    expect(component.getHistoryStatusClass('Complete')).toBe('published');
  });

  it('removes an existing print iframe before exporting a new one', () => {
    const pastCycle = cycle({ cycleId: 'past', status: 'COMPLETED' as any });
    const selfPast = review({ cycleId: 'past', status: 'COMPLETED', answers: [] });
    const { component } = buildComponent({ cycles: [pastCycle], subjectReviews: [selfPast] });
    const oldIframe = document.createElement('iframe');
    oldIframe.id = '__print_iframe__';
    document.body.appendChild(oldIframe);

    component.exportHistoryToPdf(component.reviewHistory[0]);

    expect(document.querySelectorAll('#__print_iframe__').length).toBe(1);
    document.getElementById('__print_iframe__')?.remove();
  });
});
