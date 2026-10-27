import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { of, throwError } from 'rxjs';
import { ManagerReviewFormComponent } from './manager-review-form.component';
import { AuthService } from '../../core/auth/auth.service';
import { EmployeeService, ManagerGateStatus, ReviewCycle } from '../../services/employees.services';
import { AiReviewService, AiReviewInsightsResponse, Peer360Digest } from '../../services/ai-review.service';
import { Peer360AssignmentService } from '../../services/peer360-assignment.service';
import { GoldenCitation, Question, Review } from '../../models/review.model';

/**
 * Manager review form is 1400+ lines — this spec targets the highest-risk
 * surfaces explicitly called out in the test brief:
 *
 *  1. Server-authoritative canSubmit gate (replaces legacy ?canSubmit= query param)
 *  2. retryEmployeeInsights — INSIGHTS retry path with isSubjectsManager PUT-rights
 *
 * Broader coverage of the resize / tabs / question suggestion paths is
 * intentionally deferred to a separate pass.
 */
describe('ManagerReviewFormComponent', () => {
  let component: ManagerReviewFormComponent;
  let fixture: ComponentFixture<ManagerReviewFormComponent>;
  let employeeService: jasmine.SpyObj<EmployeeService>;
  let aiReviewService: jasmine.SpyObj<AiReviewService>;
  let peer360Service: jasmine.SpyObj<Peer360AssignmentService>;
  let authService: jasmine.SpyObj<AuthService>;
  let router: jasmine.SpyObj<Router>;
  let queryParamMap: jasmine.Spy;

  const managerQuestions: Question[] = [
    { questionId: 'mq1', text: 'Manager impact?', type: 'textarea' as any, required: true, category: 'Impact' },
    { questionId: 'mq2', text: 'Manager rating?', type: 'rating' as any, required: true, category: 'Impact', scale: 5 },
    { questionId: 'mq3', text: 'Manager strengths?', type: 'multi_select' as any, required: false, options: ['Ownership', 'Mentoring'] },
  ];

  const selfQuestions: Question[] = [
    { questionId: 'sq1', text: 'Self impact?', type: 'textarea' as any },
    { questionId: 'sq2', text: 'Self blockers?', type: 'textarea' as any },
  ];

  const peerQuestions: Question[] = [
    { questionId: 'pq1', text: 'Peer collaboration?', type: 'textarea' as any },
  ];

  function buildGate(partial: Partial<ManagerGateStatus> = {}): ManagerGateStatus {
    return {
      open: false,
      cycleId: 'c-1',
      subjectUserId: 'subj-1',
      selfReview: { status: 'COMPLETED', reviewId: 'self-1' },
      peer360: { required: true, totalAssigned: 2, completedCount: 0, incompleteAuthorUserIds: ['p-1', 'p-2'] },
      reasons: [],
      ...partial,
    };
  }

  function buildCycle(overrides: Partial<ReviewCycle> = {}): ReviewCycle {
    return {
      cycleId: 'c-1',
      cycleTitle: 'FY26 Review',
      startDate: '2026-01-01',
      dueDate: '2026-03-31',
      status: 'ACTIVE',
      milestones: [
        {
          milestoneId: 'self-ms',
          name: 'Self',
          assignedRoles: ['EMPLOYEE'],
          startDate: '2026-01-01',
          endDate: '2026-01-31',
          status: 'OPEN',
          templateSnapshot: { templateId: 'self-template', reviewType: 'SELF', questions: selfQuestions },
        },
        {
          milestoneId: 'peer-ms',
          name: 'Peer',
          assignedRoles: ['EMPLOYEE'],
          startDate: '2026-01-01',
          endDate: '2026-02-15',
          status: 'OPEN',
          templateSnapshot: { templateId: 'peer-template', reviewType: 'PEER_360', questions: peerQuestions },
        },
        {
          milestoneId: 'mgr-ms',
          name: 'Manager',
          assignedRoles: ['MANAGER'],
          startDate: '2026-02-01',
          endDate: '2026-03-31',
          status: 'OPEN',
          templateSnapshot: { templateId: 'mgr-template', reviewType: 'MANAGER', questions: managerQuestions },
        },
      ],
      ...overrides,
    } as ReviewCycle;
  }

  function selfReview(overrides: Partial<Review> = {}): Review {
    return {
      reviewId: 'self-review-1',
      cycleId: 'c-1',
      userId: 'subj-1',
      subjectUserId: 'subj-1',
      authorUserId: 'subj-1',
      reviewType: 'SELF',
      status: 'COMPLETED',
      answers: [
        { questionId: 'sq1', response: 'Delivered the platform migration.' },
        { questionId: 'sq2', response: ['Dependency risk', 'Scope clarity'] },
      ],
      ...overrides,
    } as Review;
  }

  function managerReview(overrides: Partial<Review> = {}): Review {
    return {
      reviewId: 'mgr-review-1',
      cycleId: 'c-1',
      milestoneId: 'mgr-ms',
      templateId: 'mgr-template',
      userId: 'mgr-1',
      subjectUserId: 'subj-1',
      authorUserId: 'mgr-1',
      reviewType: 'MANAGER',
      status: 'DRAFT',
      answers: [
        { questionId: 'mq1', response: 'Strong delivery.' },
        { questionId: 'mq2', response: 4 },
      ],
      ...overrides,
    } as Review;
  }

  function setupComponent(queryParams: Record<string, string> = {}): void {
    queryParamMap = jasmine.createSpy('get').and.callFake((k: string) => queryParams[k] ?? null);

    // Untyped spy — the component touches a wide surface of EmployeeService
    // methods during ngOnInit and listing every one defeats the test's focus.
    const employeeServiceSpy = jasmine.createSpyObj('EmployeeService', [
      'getEmployees', 'clearCache', 'preload', 'addEmployee', 'updateEmployee', 'deleteEmployee',
      'getUserById', 'getMyTeam', 'getAllUsers', 'getTeamMembers',
      'getCycles', 'getMyReviews', 'getReviewsByCycle', 'getReviewsBySubject',
      'createReview', 'updateReview', 'getTemplateById', 'getManagerGate',
    ]) as jasmine.SpyObj<EmployeeService>;
    employeeServiceSpy.getManagerGate.and.returnValue(of(buildGate()));
    employeeServiceSpy.getCycles.and.returnValue(of([]));
    employeeServiceSpy.getUserById.and.returnValue(of(null as any));
    employeeServiceSpy.getReviewsBySubject.and.returnValue(of([]));
    employeeServiceSpy.getMyReviews.and.returnValue(of([]));
    employeeServiceSpy.getTemplateById.and.returnValue(of({} as any));
    employeeServiceSpy.createReview.and.returnValue(of({ reviewId: 'created-review-1', version: 1 } as any));
    employeeServiceSpy.updateReview.and.returnValue(of({ reviewId: 'updated-review-1', version: 1 } as any));

    const aiServiceSpy = jasmine.createSpyObj<AiReviewService>('AiReviewService', [
      'startInsights', 'pollInsights', 'getDisciplines',
      'startPeer360Digest', 'pollPeer360Digest', 'getLatestPeer360Digest',
      'startManagerGolden', 'pollManagerGolden',
      'startManagerQuestionSuggest', 'pollManagerQuestionSuggest',
    ]);
    aiServiceSpy.getDisciplines.and.returnValue(of([]));
    aiServiceSpy.startPeer360Digest.and.returnValue(of({ jobId: 'peer-job-1', status: 'PENDING' as const }));
    aiServiceSpy.pollPeer360Digest.and.returnValue(of({
      status: 'COMPLETED' as const,
      result: { themes: [], consensus: [], dissent: [], overallSentiment: 'mixed' },
    }));
    aiServiceSpy.startManagerGolden.and.returnValue(of({ jobId: 'golden-job-1', status: 'PENDING' as const }));
    aiServiceSpy.pollManagerGolden.and.returnValue(of({
      status: 'COMPLETED' as const,
      result: { overallSummary: 'Golden summary', citations: {} } as any,
    }));
    aiServiceSpy.startManagerQuestionSuggest.and.returnValue(of({ jobId: 'suggest-job-1', status: 'PENDING' as const }));
    aiServiceSpy.pollManagerQuestionSuggest.and.returnValue(of({ suggestion: 'Add clearer evidence.', citations: {} } as any));
    // Default: no cached digest resolves (Option A). Tests that exercise reuse
    // override this per-case. Safe for every load path that eagerly resolves.
    aiServiceSpy.getLatestPeer360Digest.and.returnValue(of(null));

    const peer360ServiceSpy = jasmine.createSpyObj<Peer360AssignmentService>('Peer360AssignmentService', ['list']);
    peer360ServiceSpy.list.and.returnValue(of([]));

    const authServiceSpy = jasmine.createSpyObj<AuthService>('AuthService', ['getTokenPayload']);
    authServiceSpy.getTokenPayload.and.returnValue({
      sub: 'mgr-1', email: 'mgr@example.com', 'cognito:groups': ['MANAGER'],
    });

    router = jasmine.createSpyObj<Router>('Router', ['navigate']);

    TestBed.configureTestingModule({
      imports: [ManagerReviewFormComponent],
      providers: [
        { provide: EmployeeService, useValue: employeeServiceSpy },
        { provide: AiReviewService, useValue: aiServiceSpy },
        { provide: Peer360AssignmentService, useValue: peer360ServiceSpy },
        { provide: AuthService, useValue: authServiceSpy },
        { provide: Router, useValue: router },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: { get: queryParamMap } } },
        },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });

    employeeService = employeeServiceSpy;
    aiReviewService = aiServiceSpy;
    peer360Service = peer360ServiceSpy;
    authService = authServiceSpy;

    fixture = TestBed.createComponent(ManagerReviewFormComponent);
    component = fixture.componentInstance;
  }

  function setupLoadedManagerForm(opts: {
    reviews?: Review[];
    gate?: ManagerGateStatus;
    query?: Record<string, string>;
    cycle?: ReviewCycle;
  } = {}): void {
    setupComponent({
      cycleId: 'c-1',
      milestoneId: 'mgr-ms',
      subjectUserId: 'subj-1',
      ...(opts.query ?? {}),
    });
    employeeService.getManagerGate.and.returnValue(of(opts.gate ?? buildGate({ open: true, reasons: [] })));
    employeeService.getCycles.and.returnValue(of([opts.cycle ?? buildCycle()]));
    employeeService.getReviewsBySubject.and.returnValue(of(opts.reviews ?? [selfReview(), managerReview()]));
    employeeService.getTemplateById.and.returnValue(of({ templateId: 'mgr-template', questions: managerQuestions } as any));
    employeeService.getUserById.and.callFake((id: string) => of({
      userId: id,
      firstName: id === 'mgr-1' ? 'Mina' : 'Sam',
      lastName: id === 'mgr-1' ? 'Manager' : 'Subject',
      seniority: 'Senior',
      discipline: 'Software',
      email: `${id}@example.com`,
    } as any));
    peer360Service.list.and.returnValue(of([
      {
        reviewId: 'peer-1',
        cycleId: 'c-1',
        userId: 'peer-a',
        subjectUserId: 'subj-1',
        authorUserId: 'peer-a',
        reviewType: 'PEER_360',
        status: 'COMPLETED',
        answers: [{ questionId: 'pq1', response: 'Collaborates across teams.' }],
      },
      {
        reviewId: 'peer-draft',
        cycleId: 'c-1',
        userId: 'peer-b',
        subjectUserId: 'subj-1',
        authorUserId: 'peer-b',
        reviewType: 'PEER_360',
        status: 'DRAFT',
        answers: [{ questionId: 'pq1', response: 'Should stay hidden.' }],
      },
    ] as Review[]));
    fixture.detectChanges();
  }

  it('clears cooldown timers on destroy so they stop ticking after navigation (NEW-9)', fakeAsync(() => {
    setupComponent();
    (component as any).startCooldown('q1', 5);
    expect(component.cooldownRemaining['q1']).toBe(5);

    tick(1000);
    expect(component.cooldownRemaining['q1']).toBe(4); // counting down

    component.ngOnDestroy();
    tick(5000); // would have counted to 0 (and deleted the key) if not cleared

    // Frozen at 4 — the interval was cleared on destroy, so no ticks fired.
    expect(component.cooldownRemaining['q1']).toBe(4);
  }));

  describe('employee self-review discovery', () => {
    // Pre-fix: the find filter required status === 'COMPLETED' only. After
    // a bundle publish the SELF review's status flips COMPLETED → PUBLISHED,
    // so the manager-side left panel rendered "Employee hasn't submitted"
    // even though the status pill (which uses isSelfReviewDone) correctly
    // said the self-review was done. These tests pin the broader filter.

    function setupWithSubjectReviews(reviews: Partial<Review>[]): void {
      setupComponent({ cycleId: 'c-1', subjectUserId: 'subj-1', milestoneId: 'm-1' });
      employeeService.getCycles.and.returnValue(of([{
        cycleId: 'c-1', cycleTitle: 'Test', startDate: '01/01/2026', dueDate: '30/06/2026',
        status: 'ACTIVE', milestones: [],
      } as any]));
      employeeService.getReviewsBySubject.and.returnValue(of(reviews as Review[]));
    }

    it('finds the SELF review when status is COMPLETED', () => {
      setupWithSubjectReviews([{
        reviewId: 'self-c', cycleId: 'c-1',
        subjectUserId: 'subj-1', authorUserId: 'subj-1',
        reviewType: 'SELF', status: 'COMPLETED',
      }]);

      fixture.detectChanges();

      expect(component.employeeReview).not.toBeNull();
      expect(component.employeeReview?.reviewId).toBe('self-c');
    });

    it('finds the SELF review when status is PUBLISHED (post bundle-publish)', () => {
      setupWithSubjectReviews([{
        reviewId: 'self-p', cycleId: 'c-1',
        subjectUserId: 'subj-1', authorUserId: 'subj-1',
        reviewType: 'SELF', status: 'PUBLISHED',
      }]);

      fixture.detectChanges();

      expect(component.employeeReview).not.toBeNull();
      expect(component.employeeReview?.reviewId).toBe('self-p');
    });

    it('leaves employeeReview null when only a DRAFT SELF exists', () => {
      setupWithSubjectReviews([{
        reviewId: 'self-d', cycleId: 'c-1',
        subjectUserId: 'subj-1', authorUserId: 'subj-1',
        reviewType: 'SELF', status: 'DRAFT',
      }]);

      fixture.detectChanges();

      expect(component.employeeReview).toBeNull();
    });
  });

  describe('server-authoritative canSubmit gate', () => {
    it('defaults canSubmit to false when no cycle/subject is in query params', () => {
      setupComponent({});

      fixture.detectChanges();

      expect(component.canSubmit).toBeFalse();
      expect(component.gateLoading).toBeFalse();
      expect(employeeService.getManagerGate).not.toHaveBeenCalled();
    });

    it('calls getManagerGate when cycleId + subjectUserId are present', () => {
      setupComponent({ cycleId: 'c-1', subjectUserId: 'subj-1' });

      fixture.detectChanges();

      expect(employeeService.getManagerGate).toHaveBeenCalledOnceWith('c-1', 'subj-1');
    });

    it('opens the gate when the backend reports open=true', () => {
      setupComponent({ cycleId: 'c-1', subjectUserId: 'subj-1' });
      employeeService.getManagerGate.and.returnValue(of(buildGate({
        open: true,
        peer360: { required: true, totalAssigned: 2, completedCount: 2, incompleteAuthorUserIds: [] },
      })));

      fixture.detectChanges();

      expect(component.canSubmit).toBeTrue();
      expect(component.gateLoading).toBeFalse();
      expect(component.gateStatus?.open).toBeTrue();
    });

    it('locks the gate when peer360 reviews are incomplete', () => {
      setupComponent({ cycleId: 'c-1', subjectUserId: 'subj-1' });
      employeeService.getManagerGate.and.returnValue(of(buildGate({
        open: false,
        peer360: { required: true, totalAssigned: 3, completedCount: 1, incompleteAuthorUserIds: ['p-2', 'p-3'] },
      })));

      fixture.detectChanges();

      expect(component.canSubmit).toBeFalse();
      expect(component.gateStatus?.peer360.incompleteAuthorUserIds.length).toBe(2);
    });

    it('keeps canSubmit=false when the gate request errors (fail-safe)', () => {
      setupComponent({ cycleId: 'c-1', subjectUserId: 'subj-1' });
      employeeService.getManagerGate.and.returnValue(throwError(() => new Error('500')));

      fixture.detectChanges();

      expect(component.canSubmit).toBeFalse();
      expect(component.gateLoading).toBeFalse();
    });
  });

  describe('advisory submit gate', () => {
    it('submits despite a closed gate when required questions are answered', () => {
      // Gate is advisory: an incomplete self-review / outstanding peer must NOT
      // block the manager from submitting (e.g. deadline pressure). Only the
      // manager's own unanswered required questions block.
      setupComponent({ cycleId: 'c-1', subjectUserId: 'subj-1' });
      employeeService.getManagerGate.and.returnValue(of(buildGate({
        open: false,
        peer360: { required: true, totalAssigned: 2, completedCount: 1, incompleteAuthorUserIds: ['p-2'] },
        reasons: [
          { type: 'self_incomplete' } as any,
          { type: 'peer360_incomplete', missingCount: 1 } as any,
        ],
      })));
      fixture.detectChanges();

      // canSubmit still mirrors the gate (informational only now).
      expect(component.canSubmit).toBeFalse();

      // Manager review with a reviewId so createThenUpdate just calls
      // updateReview; golden already enqueued so the post-submit branch is
      // skipped; no questions → no form blockers.
      component.review = {
        reviewId: 'mgr-1', cycleId: 'c-1', subjectUserId: 'subj-1',
        authorUserId: 'mgr-1', reviewType: 'MANAGER', status: 'DRAFT',
        managerGoldenJobId: 'g-1', answers: [],
      } as any;
      component.questions = [];
      employeeService.updateReview.and.returnValue(of(component.review as any));

      component.save('COMPLETED');

      // The advisory gate must not raise the blocker popup, and the submit
      // must proceed to persist.
      expect(component.showSubmitBlockerPopup).toBeFalse();
      expect(employeeService.updateReview).toHaveBeenCalled();
    });

    it('still blocks submit when a required manager question is unanswered', () => {
      // Form completeness is a separate, still-enforced concern.
      setupComponent({ cycleId: 'c-1', subjectUserId: 'subj-1' });
      employeeService.getManagerGate.and.returnValue(of(buildGate({ open: true })));
      fixture.detectChanges();

      component.review = {
        reviewId: 'mgr-1', cycleId: 'c-1', subjectUserId: 'subj-1',
        authorUserId: 'mgr-1', reviewType: 'MANAGER', status: 'DRAFT',
        managerGoldenJobId: 'g-1', answers: [],
      } as any;
      component.questions = [
        { questionId: 'q1', text: 'Assessment?', type: 'text', required: true } as any,
      ];
      component.responses = { q1: '' }; // required, unanswered
      employeeService.updateReview.and.returnValue(of(component.review as any));

      component.save('COMPLETED');

      expect(component.showSubmitBlockerPopup).toBeTrue();
      expect(employeeService.updateReview).not.toHaveBeenCalled();
    });
  });

  describe('retryEmployeeInsights', () => {
    function primeRetry(opts: { hasReviewId?: boolean; hasMetadata?: boolean; hasAnswers?: boolean } = {}) {
      setupComponent({ cycleId: 'c-1', subjectUserId: 'subj-1' });
      fixture.detectChanges();
      const reviewId = opts.hasReviewId === false ? undefined : 'self-review-1';
      component.employeeReview = {
        reviewId,
        cycleId: 'c-1',
        userId: 'subj-1',
        subjectUserId: 'subj-1',
        authorUserId: 'subj-1',
        reviewType: 'SELF',
        status: 'COMPLETED',
        answers: opts.hasAnswers === false
          ? []
          : [
              { questionId: 'q1', response: 'Strong delivery on the auth migration.' },
              { questionId: 'q2', response: '   ' }, // blank — should be filtered
              { questionId: 'q3', response: 'Mentored two junior engineers this cycle.' },
            ],
      } as unknown as Review;
      component.employeeQuestions = [
        { questionId: 'q1', text: 'Biggest impact this cycle?', type: 'textarea' as any },
        { questionId: 'q2', text: 'Blockers?', type: 'textarea' as any },
        { questionId: 'q3', text: 'Mentorship?', type: 'textarea' as any },
      ];
      (component as any).subjectSeniority = opts.hasMetadata === false ? '' : 'Senior';
      (component as any).subjectDiscipline = opts.hasMetadata === false ? '' : 'Software & DevOps Engineering';
    }

    it('is a no-op when already retrying', () => {
      primeRetry();
      component.insightsRetrying = true;

      component.retryEmployeeInsights();

      expect(aiReviewService.startInsights).not.toHaveBeenCalled();
    });

    it('bails silently when employeeReview has no reviewId', () => {
      primeRetry({ hasReviewId: false });

      component.retryEmployeeInsights();

      expect(aiReviewService.startInsights).not.toHaveBeenCalled();
      expect(component.insightsRetrying).toBeFalse();
    });

    it('marks insightsFailed when subject metadata is missing', () => {
      primeRetry({ hasMetadata: false });

      component.retryEmployeeInsights();

      expect(component.insightsFailed).toBeTrue();
      expect(aiReviewService.startInsights).not.toHaveBeenCalled();
    });

    it('marks insightsFailed when all answers are empty after filter', () => {
      primeRetry({ hasAnswers: false });

      component.retryEmployeeInsights();

      expect(component.insightsFailed).toBeTrue();
      expect(aiReviewService.startInsights).not.toHaveBeenCalled();
    });

    it('happy path: enqueues INSIGHTS, persists jobId via updateReview, polls, hydrates local state', () => {
      primeRetry();
      const newJob = { jobId: 'insights-new-1', status: 'PENDING' as const };
      aiReviewService.startInsights.and.returnValue(of(newJob));
      employeeService.updateReview.and.returnValue(of({
        ...component.employeeReview!,
        insightsJobId: 'insights-new-1',
      } as any));
      const pollResult: AiReviewInsightsResponse = {
        summary: 'Solid impact and mentorship.',
        goldenMirror: 'See [c1] and [c3].',
        citations: { c1: { source: 'self', reviewId: 'self-review-1', questionId: 'q1' } },
      };
      aiReviewService.pollInsights.and.returnValue(of({
        jobId: 'insights-new-1',
        status: 'COMPLETED' as const,
        result: pollResult,
      }));

      component.retryEmployeeInsights();

      // Filtered pairs: q2 is blank, so only q1 + q3 reach the AI call.
      expect(aiReviewService.startInsights).toHaveBeenCalledTimes(1);
      const [pairs, seniority, discipline] = aiReviewService.startInsights.calls.mostRecent().args;
      expect(pairs.map(p => p.questionId)).toEqual(['q1', 'q3']);
      expect(seniority).toBe('Senior');
      expect(discipline).toBe('Software & DevOps Engineering');

      // Persisted the new jobId so future visits poll the fresh job.
      expect(employeeService.updateReview).toHaveBeenCalledTimes(1);
      const persisted = employeeService.updateReview.calls.mostRecent().args[0];
      expect(persisted.insightsJobId).toBe('insights-new-1');

      // Polled and hydrated.
      expect(aiReviewService.pollInsights).toHaveBeenCalledOnceWith('insights-new-1');
      expect(component.employeeSummary).toBe('Solid impact and mentorship.');
      expect(component.employeeGoldenMirror).toBe('See [c1] and [c3].');
      expect(component.employeeGoldenCitations).toEqual(pollResult.citations!);
      expect(component.insightsLoading).toBeFalse();
      expect(component.insightsRetrying).toBeFalse();
      expect(component.insightsFailed).toBeFalse();
      expect(component.employeeReview?.insightsJobId).toBe('insights-new-1');
    });

    it('marks insightsFailed when startInsights fails', () => {
      primeRetry();
      aiReviewService.startInsights.and.returnValue(throwError(() => new Error('SQS down')));

      component.retryEmployeeInsights();

      expect(component.insightsFailed).toBeTrue();
      expect(component.insightsRetrying).toBeFalse();
      expect(component.insightsLoading).toBeFalse();
      expect(employeeService.updateReview).not.toHaveBeenCalled();
    });

    it('marks insightsFailed when polling fails', () => {
      primeRetry();
      aiReviewService.startInsights.and.returnValue(of({ jobId: 'j', status: 'PENDING' as const }));
      employeeService.updateReview.and.returnValue(of(component.employeeReview! as any));
      aiReviewService.pollInsights.and.returnValue(throwError(() => new Error('timeout')));

      component.retryEmployeeInsights();

      expect(component.insightsFailed).toBeTrue();
      expect(component.insightsLoading).toBeFalse();
    });

    it('still updates local state when the PUT for jobId persistence fails (best-effort)', () => {
      primeRetry();
      const newJob = { jobId: 'insights-new-2', status: 'PENDING' as const };
      aiReviewService.startInsights.and.returnValue(of(newJob));
      employeeService.updateReview.and.returnValue(throwError(() => new Error('403')));
      aiReviewService.pollInsights.and.returnValue(of({
        jobId: 'insights-new-2',
        status: 'COMPLETED' as const,
        result: { summary: 's', goldenMirror: 'gm' },
      }));

      component.retryEmployeeInsights();

      expect(aiReviewService.pollInsights).toHaveBeenCalledOnceWith('insights-new-2');
      expect(component.employeeReview?.insightsJobId).toBe('insights-new-2');
      expect(component.employeeSummary).toBe('s');
    });

    it('re-points insightsJobId on COMPLETED and recovers from a stale-version 409 (re-fetch + retry)', () => {
      // The orphaned-result bug: the persist must land on COMPLETED, and a stale
      // version must self-heal by re-fetching the current version and retrying.
      primeRetry();
      (component.employeeReview as any).version = 1; // stale page-load version
      aiReviewService.startInsights.and.returnValue(of({ jobId: 'insights-new-3', status: 'PENDING' as const }));
      aiReviewService.pollInsights.and.returnValue(of({
        jobId: 'insights-new-3', status: 'COMPLETED' as const,
        result: { summary: 's', goldenMirror: 'gm' },
      }));
      // First PUT (stale v1) → 409; re-fetch yields fresh v4; retry PUT → success (server bumps to v5).
      employeeService.updateReview.and.returnValues(
        throwError(() => ({ status: 409 })),
        of({ ...(component.employeeReview as any), insightsJobId: 'insights-new-3', version: 5 } as any),
      );
      employeeService.getReviewsBySubject.and.returnValue(of([
        { reviewId: 'self-review-1', version: 4 } as any,
      ]));

      component.retryEmployeeInsights();

      expect(employeeService.updateReview).toHaveBeenCalledTimes(2);
      const calls = employeeService.updateReview.calls.all();
      expect((calls[0].args[0] as any).version).toBe(1);          // first attempt: stale
      expect((calls[1].args[0] as any).version).toBe(4);          // retry: re-fetched fresh version
      expect((calls[1].args[0] as any).insightsJobId).toBe('insights-new-3');
      expect(employeeService.getReviewsBySubject).toHaveBeenCalled();
      expect(component.employeeReview?.insightsJobId).toBe('insights-new-3');
      expect(component.employeeReview?.version).toBe(5);          // synced from server
      expect(component.insightsFailed).toBeFalse();
    });

    it('does not persist insightsJobId when the retry job fails', () => {
      // Persist happens only on COMPLETED — a FAILED retry must not touch the pointer.
      primeRetry();
      aiReviewService.startInsights.and.returnValue(of({ jobId: 'insights-new-4', status: 'PENDING' as const }));
      aiReviewService.pollInsights.and.returnValue(throwError(() => new Error('MALFORMED')));

      component.retryEmployeeInsights();

      expect(component.insightsFailed).toBeTrue();
      expect(employeeService.updateReview).not.toHaveBeenCalled();
    });
  });

  // ── peer digest + manager golden: orphan-fix parity with retryEmployeeInsights.
  //    Persist must re-point on COMPLETED and self-heal a stale-version 409 by
  //    re-fetching the current version and retrying — and golden must persist on
  //    COMPLETED (not at job-start, which orphaned a result behind a FAILED job).
  describe('peer digest + manager golden persistence (orphan-fix parity)', () => {
    function primeManagerReview(): void {
      setupComponent({ cycleId: 'c-1', subjectUserId: 'subj-1' });
      fixture.detectChanges();
      (component as any).subjectUserId = 'subj-1';
      component.cycle = { cycleId: 'c-1', milestones: [] } as any;
      component.review = {
        reviewId: 'mgr-1', cycleId: 'c-1', subjectUserId: 'subj-1',
        authorUserId: 'mgr-1', reviewType: 'MANAGER', status: 'DRAFT', version: 1,
      } as unknown as Review;
      // Option A: the peer digest is a (subject, cycle) artifact resolved
      // server-side — it no longer pins a pointer on any review row. employeeReview
      // is primed only because other shared setup expects it present.
      component.employeeReview = {
        reviewId: 'self-1', cycleId: 'c-1', subjectUserId: 'subj-1',
        authorUserId: 'subj-1', reviewType: 'SELF', status: 'COMPLETED', version: 1,
      } as unknown as Review;
    }

    it('digest: does NOT persist any review-row pointer on COMPLETED (Option A — backend indexes by subject+cycle)', () => {
      primeManagerReview();
      aiReviewService.startPeer360Digest.and.returnValue(of({ jobId: 'digest-1', status: 'PENDING' as const }));
      aiReviewService.pollPeer360Digest.and.returnValue(
        of({ jobId: 'digest-1', status: 'COMPLETED' as const, result: { summary: 'ok' } as any }));

      component.generatePeer360Digest();

      // No review-row write: the new job is stamped (subject, cycle) and indexed
      // server-side (createForDigest → Peer360DigestIndex), so there is nothing to
      // PUT on a review row. A later visit reuses it via getLatestPeer360Digest.
      expect(employeeService.updateReview).not.toHaveBeenCalled();
      expect(component.peer360DigestState).toBe('ready');
      expect(component.peer360Digest).toEqual({ summary: 'ok' } as any);
    });

    it('golden: persists managerGoldenJobId on COMPLETED (not at start) and recovers from a stale-version 409', () => {
      primeManagerReview();
      aiReviewService.startManagerGolden.and.returnValue(of({ jobId: 'golden-1', status: 'PENDING' as const }));
      aiReviewService.pollManagerGolden.and.returnValue(
        of({ jobId: 'golden-1', status: 'COMPLETED' as const, result: {} as any }));
      employeeService.updateReview.and.returnValues(
        throwError(() => ({ status: 409 })),
        of({ ...(component.review as any), managerGoldenJobId: 'golden-1', version: 5 } as any));
      employeeService.getReviewsBySubject.and.returnValue(of([{ reviewId: 'mgr-1', version: 4 } as any]));

      (component as any).runManagerGolden('mgr-1');

      expect(employeeService.updateReview).toHaveBeenCalledTimes(2);
      const calls = employeeService.updateReview.calls.all();
      expect((calls[1].args[0] as any).version).toBe(4);
      expect((calls[1].args[0] as any).managerGoldenJobId).toBe('golden-1');
      expect(component.review?.managerGoldenJobId).toBe('golden-1');
      expect(component.review?.version).toBe(5);
    });

    it('golden: does NOT persist managerGoldenJobId when the job fails (persist is on COMPLETED only)', () => {
      primeManagerReview();
      aiReviewService.startManagerGolden.and.returnValue(of({ jobId: 'golden-2', status: 'PENDING' as const }));
      aiReviewService.pollManagerGolden.and.returnValue(throwError(() => new Error('MALFORMED')));

      (component as any).runManagerGolden('mgr-1');

      expect(employeeService.updateReview).not.toHaveBeenCalled();
      expect(component.goldenFailed).toBeTrue();
    });
  });

  // ── peer digest follows the manual-button rule (Option B′) ────────────────
  //   On digest-tab open the digest must NOT auto-fire Gemini when there's no
  //   reusable cached job — it shows a manual Generate (idle) / Retry (failed)
  //   affordance, exactly like golden/insights. This stops the "fresh Gemini
  //   call on every refresh" when no digest pointer is persisted (no manager
  //   review row to hold it). The reuse path (cached COMPLETED job) is unchanged.
  describe('peer digest manual-button rule (no auto re-fire on refresh)', () => {
    function prime(digestJobId?: string): void {
      setupComponent({ cycleId: 'c-1', subjectUserId: 'subj-1' });
      fixture.detectChanges();
      (component as any).subjectUserId = 'subj-1';
      component.cycle = { cycleId: 'c-1', milestones: [] } as any;
      // Option A: reuse resolves the latest (subject, cycle) digest server-side.
      // `undefined` models "no completed digest" (resolve → null → manual button).
      aiReviewService.getLatestPeer360Digest.and.returnValue(of(digestJobId ? { jobId: digestJobId } : null));
      component.employeeReview = {
        reviewId: 'self-1', cycleId: 'c-1', subjectUserId: 'subj-1', reviewType: 'SELF',
        status: 'COMPLETED', version: 1,
      } as unknown as Review;
      component.peer360DigestState = 'idle';
      // Stubs so the (pre-fix) auto-generate path runs to completion — the clean
      // discriminator is whether startPeer360Digest was called at all.
      aiReviewService.startPeer360Digest.and.returnValue(of({ jobId: 'should-not-fire', status: 'PENDING' as const }));
    }

    it('no cached digest → does NOT auto-fire startPeer360Digest; stays idle (manual Generate)', () => {
      prime(undefined);
      aiReviewService.pollPeer360Digest.and.returnValue(
        of({ jobId: 'x', status: 'COMPLETED' as const, result: {} as any }));

      component.setLeftTab('peer360_digest');

      expect(aiReviewService.startPeer360Digest).not.toHaveBeenCalled();
      expect(component.peer360DigestState).toBe('idle');
    });

    it('cached digest FAILED on poll → does NOT auto-regenerate; shows failed (manual Retry)', () => {
      prime('digest-old');
      aiReviewService.pollPeer360Digest.and.returnValue(throwError(() => new Error('FAILED')));

      component.setLeftTab('peer360_digest');

      expect(aiReviewService.pollPeer360Digest).toHaveBeenCalledWith('digest-old');
      expect(aiReviewService.startPeer360Digest).not.toHaveBeenCalled();
      expect(component.peer360DigestState).toBe('failed');
    });

    it('cached digest COMPLETED → reuses it (polls, renders ready, no startPeer360Digest)', () => {
      prime('digest-done');
      aiReviewService.pollPeer360Digest.and.returnValue(
        of({ jobId: 'digest-done', status: 'COMPLETED' as const, result: {} as any }));

      component.setLeftTab('peer360_digest');

      expect(aiReviewService.pollPeer360Digest).toHaveBeenCalledWith('digest-done');
      expect(aiReviewService.startPeer360Digest).not.toHaveBeenCalled();
      expect(component.peer360DigestState).toBe('ready');
    });

    it('reuseCachedDigestIfAny eagerly reuses a cached digest on review load (mirror insights) — no startPeer360Digest', () => {
      prime('digest-done');
      aiReviewService.pollPeer360Digest.and.returnValue(
        of({ jobId: 'digest-done', status: 'COMPLETED' as const, result: {} as any }));

      (component as any).reuseCachedDigestIfAny();

      expect(aiReviewService.pollPeer360Digest).toHaveBeenCalledWith('digest-done');
      expect(aiReviewService.startPeer360Digest).not.toHaveBeenCalled();
      expect(component.peer360DigestState).toBe('ready');
    });

    it('reuseCachedDigestIfAny is a no-op when there is no cached digest (stays idle, no fire)', () => {
      prime(undefined);

      (component as any).reuseCachedDigestIfAny();

      expect(aiReviewService.pollPeer360Digest).not.toHaveBeenCalled();
      expect(aiReviewService.startPeer360Digest).not.toHaveBeenCalled();
      expect(component.peer360DigestState).toBe('idle');
    });
  });

  describe('manager AI narrative generation gate (mandatory review)', () => {
    // BUG: clicking "Generate AI Narrative" on an untouched manager review did
    // nothing — no spinner, no error. fetchManagerGolden() bailed on a bare
    // `if (!reviewId) return` because an unsaved review has no reviewId. The
    // manager review is mandatory before generation: gate the button on having
    // content, and when content exists but the row isn't persisted yet (the
    // 2.5s auto-save debounce), save a draft first instead of silently no-op-ing.
    function primeGolden(opts: { reviewId?: string; content?: boolean } = {}): void {
      setupComponent({ cycleId: 'c-1', subjectUserId: 'subj-1' });
      fixture.detectChanges();
      component.questions = [
        { questionId: 'q1', text: 'Overall assessment?', type: 'text', required: true } as any,
      ];
      component.responses = { q1: opts.content === false ? '' : 'Strong delivery this cycle.' };
      component.review = {
        reviewId: opts.reviewId,
        cycleId: 'c-1', subjectUserId: 'subj-1', authorUserId: 'mgr-1',
        reviewType: 'MANAGER', status: 'DRAFT', answers: [],
      } as any;
    }

    it('hasManagerReviewContent is false for an untouched review, true once an answer is entered', () => {
      primeGolden({ content: false });
      expect(component.hasManagerReviewContent).toBeFalse();

      component.responses['q1'] = 'Great work on the migration.';
      expect(component.hasManagerReviewContent).toBeTrue();
    });

    it('does nothing (no silent backend call) when the manager review is empty', () => {
      primeGolden({ reviewId: 'mgr-1', content: false });

      component.fetchManagerGolden();

      expect(aiReviewService.startManagerGolden).not.toHaveBeenCalled();
      expect(employeeService.createReview).not.toHaveBeenCalled();
      expect(component.goldenLoading).toBeFalse();
    });

    it('generates directly when the review has content and a reviewId', () => {
      primeGolden({ reviewId: 'mgr-1' });
      aiReviewService.startManagerGolden.and.returnValue(of({ jobId: 'g-1', status: 'PENDING' as const }));
      aiReviewService.pollManagerGolden.and.returnValue(of({
        jobId: 'g-1', status: 'COMPLETED' as const,
        result: { technologyComments: 'Solid.' },
      } as any));
      employeeService.updateReview.and.returnValue(of({ reviewId: 'mgr-1', managerGoldenJobId: 'g-1', version: 2 } as any));

      component.fetchManagerGolden();

      expect(employeeService.createReview).not.toHaveBeenCalled();
      expect(aiReviewService.startManagerGolden).toHaveBeenCalledOnceWith('mgr-1');
      expect(component.parsedGoldenReview).toEqual({ technologyComments: 'Solid.' } as any);
      expect(component.goldenLoading).toBeFalse();
    });

    it('persists a draft to mint a reviewId, then generates, when content exists but the review is unsaved', () => {
      primeGolden({ reviewId: undefined });
      employeeService.createReview.and.returnValue(of({ reviewId: 'mgr-new', version: 1 } as any));
      employeeService.updateReview.and.returnValue(of({ reviewId: 'mgr-new', version: 2 } as any));
      aiReviewService.startManagerGolden.and.returnValue(of({ jobId: 'g-2', status: 'PENDING' as const }));
      aiReviewService.pollManagerGolden.and.returnValue(of({
        jobId: 'g-2', status: 'COMPLETED' as const,
        result: { technologyComments: 'From freshly-saved draft.' },
      } as any));

      component.fetchManagerGolden();

      expect(employeeService.createReview).toHaveBeenCalledTimes(1);
      expect(aiReviewService.startManagerGolden).toHaveBeenCalledOnceWith('mgr-new');
      expect(component.parsedGoldenReview).toEqual({ technologyComments: 'From freshly-saved draft.' } as any);
      expect(component.goldenFailed).toBeFalse();
    });
  });

  describe('manager form load and navigation helpers', () => {
    // Full happy-path init proves the component selects the manager milestone,
    // maps existing answers, and hides unfinished peer-360 drafts from the left pane.
    it('loads the manager template, existing draft, employee answers, and completed peer reviews', () => {
      setupLoadedManagerForm();

      expect(component.loading).toBeFalse();
      expect(component.cycle?.cycleTitle).toBe('FY26 Review');
      expect(component.milestoneId).toBe('mgr-ms');
      expect(component.templateId).toBe('mgr-template');
      expect(component.questions.map(q => q.questionId)).toEqual(['mq1', 'mq2', 'mq3']);
      expect(component.responses['mq1']).toBe('Strong delivery.');
      expect(component.responses['mq3']).toEqual([]);
      expect(component.employeeQuestions.map(q => q.questionId)).toEqual(['sq1', 'sq2']);
      expect(component.peer360Questions.map(q => q.questionId)).toEqual(['pq1']);
      expect(component.peer360Reviews.map(r => r.reviewId)).toEqual(['peer-1']);
      expect(component.groupedQuestions[0].category).toBe('Impact');
    });

    it('creates a new draft when no manager review exists', () => {
      setupLoadedManagerForm({ reviews: [selfReview()] });
      expect(component.review?.reviewType).toBe('MANAGER');
      expect(component.review?.status).toBe('DRAFT');
      expect(component.review?.reviewId).toBeUndefined();
    });

    it('marks completed manager reviews as view-only', () => {
      setupLoadedManagerForm({ reviews: [selfReview(), managerReview({ status: 'COMPLETED' })] });
      expect(component.viewOnly).toBeTrue();
    });

    // Navigation should save a draft between steps, but never run past bounds.
    it('moves between questions and silently saves drafts when advancing', () => {
      setupLoadedManagerForm();
      spyOn(window, 'scrollTo');
      employeeService.updateReview.and.returnValue(of({ ...component.review!, version: 2 } as any));

      component.nextStep();
      expect(component.currentStep).toBe(1);
      expect(employeeService.updateReview).toHaveBeenCalled();

      component.prevStep();
      expect(component.currentStep).toBe(0);

      component.currentStep = component.questions.length - 1;
      component.nextStep();
      expect(component.currentStep).toBe(component.questions.length - 1);
    });

    // Input helpers are small, but they feed required validation and display state.
    it('handles multi-select, ratings, progress, initials, and answer display fallbacks', () => {
      setupLoadedManagerForm();

      component.toggleMulti('mq3', 'Ownership');
      expect(component.isChecked('mq3', 'Ownership')).toBeTrue();
      component.toggleMulti('mq3', 'Ownership');
      expect(component.isChecked('mq3', 'Ownership')).toBeFalse();

      component.setRating('mq2', 5);
      expect(component.responses['mq2']).toBe(5);
      expect(component.ratingArray(3)).toEqual([1, 2, 3]);
      expect(component.progressPercent()).toBe(33);
      expect(component.isLastStep()).toBeFalse();
      expect(component.employeeInitials).toBe('SS');
      expect(component.getEmployeeAnswer('sq2')).toBe('Dependency risk, Scope clarity');
      expect(component.getEmployeeAnswer('missing')).toBe('—');
      expect(component.getPeerQuestionText('pq1', 0)).toBe('Peer collaboration?');
      expect(component.getPeerQuestionText('missing', 1)).toBe('Question 2');
      expect(component.getResponseDisplay('mq3')).toBe('—');
    });
  });

  describe('submit and gate business rules', () => {
    // Submit is blocked by the server gate first, then by required answers.
    it('shows submit blockers from gate reasons and missing required questions', () => {
      setupLoadedManagerForm({
        gate: buildGate({
          open: false,
          reasons: [
            { type: 'peer360_unassigned' } as any,
            { type: 'peer360_incomplete', missingCount: 2 } as any,
          ],
        }),
      });
      component.responses['mq1'] = '';
      component.responses['mq2'] = 0;

      component.save('COMPLETED');

      expect(component.showSubmitBlockerPopup).toBeTrue();
      expect(component.submitBlockers).toContain('2 required questions not answered.');
      expect(component.missingQuestionIds.has('mq1')).toBeTrue();
      expect(employeeService.updateReview).not.toHaveBeenCalled();
    });

    it('saves completed reviews, starts golden generation, and navigates to my team', () => {
      setupLoadedManagerForm();
      employeeService.updateReview.and.returnValue(of({ ...component.review!, reviewId: 'mgr-review-1', version: 3 } as any));
      aiReviewService.startManagerGolden.and.returnValue(of({ jobId: 'golden-job-2', status: 'PENDING' as const }));

      component.save('COMPLETED');

      expect(component.review?.status).toBe('COMPLETED');
      expect(component.review?.submittedAt).toBeTruthy();
      expect(aiReviewService.startManagerGolden).toHaveBeenCalledOnceWith('mgr-review-1');
      expect(router.navigate).toHaveBeenCalledOnceWith(['/my-team']);
    });

    it('saves drafts without required-answer validation and reports save errors', () => {
      setupLoadedManagerForm();
      component.responses['mq1'] = '';
      employeeService.updateReview.and.returnValue(throwError(() => new Error('save failed')));

      component.save('DRAFT');

      expect(component.showSubmitBlockerPopup).toBeFalse();
      expect(component.saving).toBeFalse();
      expect(component.errorMsg).toBe('Failed to save. Please try again.');
    });

    // Gate messages are the text managers see when submit is locked.
    it('returns precise gate block reasons and self-review completion state', () => {
      setupLoadedManagerForm();

      component.canSubmit = true;
      expect(component.gateBlockReason()).toBe('');

      component.canSubmit = false;
      component.gateLoading = true;
      expect(component.gateBlockReason()).toContain('Checking submit eligibility');

      component.gateLoading = false;
      component.gateStatus = buildGate({ reasons: [{ type: 'self_missing' } as any] });
      component.employeeReview = selfReview({ status: 'DRAFT' });
      expect(component.gateBlockReason()).toBe('Employee has not started their self-review.');
      expect(component.isSelfReviewDone()).toBeFalse();

      component.gateStatus = buildGate({ reasons: [{ type: 'peer360_incomplete', missingCount: 3 } as any] });
      expect(component.gateBlockReason()).toBe('3 360 reviewer(s) have not completed yet.');
      expect(component.isSelfReviewDone()).toBeTrue();
    });
  });

  describe('peer digest, golden narrative, and citations', () => {
    // Peer digest is lazy: opening the AI tab starts/polls once; the backend indexes by subject+cycle.
    it('generates a peer360 digest without persisting a review-row job id', () => {
      setupLoadedManagerForm();
      const digest: Peer360Digest = {
        themes: [{ theme: 'Collaboration', summary: 'Strong' }],
        consensus: [],
        dissent: [],
        overallSentiment: 'positive',
      };
      aiReviewService.startPeer360Digest.and.returnValue(of({ jobId: 'peer-job-2', status: 'PENDING' as const }));
      aiReviewService.pollPeer360Digest.and.returnValue(of({
        status: 'COMPLETED' as const,
        result: digest,
      }));
      employeeService.updateReview.and.returnValue(of({ ...component.review!, peer360DigestJobId: 'peer-job-2', version: 4 } as any));

      component.generatePeer360Digest();

      expect(aiReviewService.startPeer360Digest).toHaveBeenCalledOnceWith('c-1', 'subj-1');
      expect(component.peer360DigestState).toBe('ready');
      expect(component.peer360Digest).toBe(digest);
      expect(employeeService.updateReview).not.toHaveBeenCalled();
    });

    it('polls a cached peer digest job and falls back to regenerate on error', () => {
      setupLoadedManagerForm({ reviews: [selfReview(), managerReview()] });
      aiReviewService.getLatestPeer360Digest.and.returnValue(of({ jobId: 'cached-peer-job' }));
      aiReviewService.pollPeer360Digest.and.returnValue(throwError(() => new Error('expired')));

      component.setLeftTab('peer360_digest');

      expect(aiReviewService.pollPeer360Digest).toHaveBeenCalledWith('cached-peer-job');
      expect(aiReviewService.startPeer360Digest).not.toHaveBeenCalled();
      expect(component.peer360DigestState).toBe('failed');
    });

    it('fetches and persists manager golden narratives, with failed polling surfaced', () => {
      setupLoadedManagerForm();
      aiReviewService.startManagerGolden.and.returnValue(of({ jobId: 'golden-job-3', status: 'PENDING' as const }));
      aiReviewService.pollManagerGolden.and.returnValue(of({
        status: 'COMPLETED' as const,
        result: { technologyComments: 'Ready narrative', citations: {} } as any,
      }));
      employeeService.updateReview.and.returnValue(of({ ...component.review!, managerGoldenJobId: 'golden-job-3', version: 5 } as any));

      component.fetchManagerGolden();

      expect(component.parsedGoldenReview?.technologyComments).toBe('Ready narrative');
      expect(component.review?.managerGoldenJobId).toBe('golden-job-3');

      aiReviewService.pollManagerGolden.and.returnValue(throwError(() => new Error('timeout')));
      component.regenerateGolden();
      expect(component.goldenFailed).toBeTrue();
      expect(component.goldenLoading).toBeFalse();
    });

    // Citation helpers recover from imperfect AI references by matching quote text.
    it('segments narratives and resolves citation jumps to self or peer evidence', () => {
      setupLoadedManagerForm();
      spyOn<any>(component, 'scrollHighlightIntoView').and.stub();
      component.parsedGoldenReview = {
        technologyComments: 'Use [c1] and [c2].',
        citations: {
          c1: { source: 'self', reviewId: 'self-review-1', questionId: 'wrong', quote: 'platform migration' },
          c2: { source: 'peer_360', reviewId: 'wrong-peer', questionId: 'wrong', quote: 'across teams', authorUserId: 'peer-a' },
        },
      } as any;

      const segments = component.goldenSegments('Use [c1] and [c9].');
      expect(segments.map(s => s.text)).toEqual(['Use ', '1', ' and ', '9', '.']);
      expect(segments[1].cite?.source).toBe('self');
      expect(segments[3].cite).toBeNull();

      component.jumpToCitation(component.parsedGoldenReview!.citations!['c1'], 1);
      expect(component.leftTab).toBe('self_raw');
      expect(component.highlightedCitation?.questionId).toBe('sq1');
      expect(component.isHighlightedSelf('sq1')).toBeTrue();

      component.jumpToCitation(component.parsedGoldenReview!.citations!['c2'], 2);
      expect(component.leftTab).toBe('peer360_raw');
      expect(component.highlightedCitation?.reviewId).toBe('peer-1');
      expect(component.isHighlightedPeer('peer-1', 'pq1')).toBeTrue();
      expect(component.isHighlightedPeerCard('peer-1')).toBeTrue();
      expect(component.highlightSegments('Alpha Beta Gamma', 'beta')).toEqual([
        { text: 'Alpha ', mark: false },
        { text: 'Beta', mark: true },
        { text: ' Gamma', mark: false },
      ]);
    });
  });

  describe('question suggestions and text helpers', () => {
    // Suggestions require self-review completion and enough manager text.
    it('handles suggestion generation, good-answer dismissal, errors, and citation segments', () => {
      setupLoadedManagerForm();
      component.responses['mq1'] = 'Needs more specific evidence.';

      expect(component.canRequestSuggestion('mq1')).toBeTrue();
      expect(component.isAnswerSubstantial('mq1')).toBeFalse();

      aiReviewService.pollManagerQuestionSuggest.and.returnValue(of({
        suggestion: 'Add a metric [c1].',
        citations: { c1: { source: 'self', reviewId: 'self-review-1', questionId: 'sq1', quote: 'migration' } },
      } as any));
      component.generateSuggestion(managerQuestions[0]);
      expect(component.questionSuggestions['mq1']).toBe('Add a metric [c1].');
      expect(component.suggestionSegments('mq1').map(s => s.text)).toEqual(['Add a metric ', 'c1', '.']);

      component.jumpToSuggestionCitation(component.questionSuggestionCitations['mq1']['c1'], 'c1');
      expect(component.leftTab).toBe('self_raw');
      expect(component.activeSuggestionCitationKey).toBe('c1');

      aiReviewService.pollManagerQuestionSuggest.and.returnValue(of({ suggestion: 'Looks great.', isGoodAnswer: true } as any));
      component.generateSuggestion(managerQuestions[0]);
      expect(component.isDismissed('mq1')).toBeTrue();

      aiReviewService.pollManagerQuestionSuggest.and.returnValue(of({} as any));
      component.generateSuggestion(managerQuestions[0]);
      expect(component.questionSuggestionError['mq1']).toContain('Could not generate');
    });

    it('clears stale suggestions on answer edits and parses display text helpers', () => {
      setupLoadedManagerForm();
      component.questionSuggestions['mq1'] = 'Old suggestion';
      component.questionSuggestionCitations['mq1'] = { c1: { source: 'self' } as any };
      component.dismissedSuggestions.add('mq1');
      component.parsedGoldenReview = { technologyComments: 'stale', citations: {} } as any;

      component.onTextAnswerChange('mq1');

      expect(component.questionSuggestions['mq1']).toBe('');
      expect(component.questionSuggestionCitations['mq1']).toBeUndefined();
      expect(component.dismissedSuggestions.has('mq1')).toBeFalse();
      expect(component.parsedGoldenReview).toBeNull();
      expect(component.isGoodAnswer('This answer looks strong')).toBeTrue();
      expect(component.isGoodAnswer('x'.repeat(180) + ' looks great')).toBeFalse();
      expect(component.getSummaryLines('• One\n- Two\n* Three')).toEqual(['One', 'Two', 'Three']);
      expect(component.getGoldenSections('IMPACT\nDelivered well.\n\nPlain paragraph.')).toEqual([
        { title: 'IMPACT', text: 'Delivered well.' },
        { title: '', text: 'Plain paragraph.' },
      ]);
    });

    it('routes cancel based on admin/view-only state and formats citation tooltips', () => {
      setupLoadedManagerForm();

      expect(component.citationTooltip({ source: 'peer_360', authorUserId: 'peer-abcdef', quote: 'Great work' } as GoldenCitation))
        .toContain('Peer (peer-abc');
      expect(component.citationTooltip({ source: 'self', quote: 'Owned it' } as GoldenCitation)).toContain('Self review');

      component.cancel();
      expect(router.navigate).toHaveBeenCalledWith(['/my-team']);

      router.navigate.calls.reset();
      component.viewOnly = true;
      component.cancel();
      expect(router.navigate).toHaveBeenCalledWith(['/my-team']);
    });
  });

  describe('autosave, tabs, export, and remaining important fallbacks', () => {
    // Autosave turns NOT_STARTED into DRAFT and treats save failures as a local status only.
    it('performs autosave success, failure, and guard branches', () => {
      setupLoadedManagerForm({ reviews: [selfReview(), managerReview({ status: 'NOT_STARTED' })] });
      employeeService.updateReview.and.returnValue(of({ ...component.review!, reviewId: 'mgr-review-1', version: 9 } as any));

      (component as any).performAutoSave();

      expect(component.review?.status).toBe('DRAFT');
      expect(component.autoSaveStatus).toBe('saved');
      expect(component.autoSavedAt).toEqual(jasmine.any(Date));

      employeeService.updateReview.and.returnValue(throwError(() => new Error('offline')));
      (component as any).performAutoSave();
      expect(component.autoSaveStatus).toBe('error');

      employeeService.updateReview.calls.reset();
      component.viewOnly = true;
      component.onAnswerChange();
      (component as any).performAutoSave();
      expect(employeeService.updateReview).not.toHaveBeenCalled();
    });

    it('reports autosave age labels', () => {
      setupLoadedManagerForm();

      component.autoSavedAt = null;
      expect(component.autoSavedAgo).toBe('');
      component.autoSavedAt = new Date(Date.now() - 5_000);
      expect(component.autoSavedAgo).toBe('just now');
      component.autoSavedAt = new Date(Date.now() - 30_000);
      expect(component.autoSavedAgo).toMatch(/s ago/);
      component.autoSavedAt = new Date(Date.now() - 120_000);
      expect(component.autoSavedAgo).toBe('2m ago');
    });

    // Tab helpers preserve each side's raw/AI mode and clear citation highlights on manual navigation.
    it('keeps left tab mode memory in sync across self and peer sections', () => {
      setupLoadedManagerForm();
      component.highlightedCitation = { source: 'self', reviewId: 'self-review-1', questionId: 'sq1' };
      component.activeCitationNum = 1;

      expect(component.mainTab).toBe('self');
      expect(component.leftMode).toBe('ai');

      component.setLeftMode('raw');
      expect(component.leftTab).toBe('self_raw');
      expect(component.highlightedCitation).toBeNull();
      expect(component.activeCitationNum).toBeNull();

      component.setMainTab('peer');
      expect(component.leftTab).toBe('peer360_raw');
      component.setLeftMode('ai');
      expect(component.leftTab).toBe('peer360_digest');
      expect(component.mainTab).toBe('peer');
      expect(component.leftMode).toBe('ai');
    });

    it('handles peer digest guard and error branches', () => {
      setupLoadedManagerForm();
      component.cycle = null;

      component.generatePeer360Digest();
      expect(component.peer360DigestState).toBe('idle');

      component.cycle = buildCycle();
      aiReviewService.pollPeer360Digest.and.returnValue(throwError(() => new Error('digest failed')));
      component.generatePeer360Digest();

      expect(component.peer360DigestState).toBe('failed');
      expect(component.peer360DigestError).toBe('digest failed');
    });

    it('switches to the AI tab and polls cached golden only once', () => {
      setupLoadedManagerForm({ reviews: [selfReview(), managerReview({ managerGoldenJobId: 'cached-golden' })] });
      aiReviewService.pollManagerGolden.and.returnValue(of({
        status: 'COMPLETED' as const,
        result: { technologyComments: 'Cached golden', citations: {} } as any,
      }));

      component.switchToAiTab();

      expect(component.rightTab).toBe('ai');
      expect(aiReviewService.pollManagerGolden).toHaveBeenCalledOnceWith('cached-golden');
      expect(component.parsedGoldenReview?.technologyComments).toBe('Cached golden');

      component.switchToAiTab();
      expect(aiReviewService.pollManagerGolden).toHaveBeenCalledTimes(1);
    });

    it('surfaces cached golden polling failures without firing a new job', () => {
      setupLoadedManagerForm({ reviews: [selfReview(), managerReview({ managerGoldenJobId: 'cached-golden' })] });
      aiReviewService.pollManagerGolden.and.returnValue(throwError(() => new Error('failed')));

      component.openPreview();

      expect(component.showPreview).toBeTrue();
      expect(component.goldenFailed).toBeTrue();
      expect(aiReviewService.startManagerGolden).not.toHaveBeenCalled();
    });

    // Suggestion citations can target peer digest cards without going through golden citation resolution.
    it('jumps to peer digest suggestion citations and matches highlighted digest text', () => {
      setupLoadedManagerForm();
      const detect = spyOn((component as any).cdr, 'detectChanges').and.stub();

      component.jumpToSuggestionCitation({
        source: 'peer_digest',
        quote: 'collaboration theme',
        section: 'Collaboration',
      } as any, 'c7');

      expect(component.leftTab).toBe('peer360_digest');
      expect(component.activeSuggestionCitationKey).toBe('c7');
      expect(component.highlightedDigestCitation?.section).toBe('Collaboration');
      expect(component.isDigestCitationMatch('The collaboration theme is strong.')).toBeTrue();
      expect(component.isDigestCitationMatch('Unrelated')).toBeFalse();
      expect(detect).toHaveBeenCalled();
    });

    it('exports the loaded review to a hidden print iframe and cleans it up on destroy', () => {
      setupLoadedManagerForm();
      component.employeeName = 'Sam Subject';
      component.responses['mq1'] = 'Printable answer';
      component.responses['mq2'] = 4;
      component.parsedGoldenReview = {
        technologyRating: 4,
        technologyComments: 'Strong engineering delivery.',
        consultingRating: 3,
        consultingComments: 'Good client communication.',
        companyValuesComments: 'Ownership.',
        developmentOpportunities: 'Delegate more.',
        otherComments: 'Keep momentum.',
        overallRating: 4,
        citations: {},
      };

      component.exportToPdf();

      const iframe = document.getElementById('__print_iframe__') as HTMLIFrameElement | null;
      expect(iframe).not.toBeNull();
      expect(document.title).toContain('Sam_Subject_Manager_Review_FY26_Review');

      component.ngOnDestroy();
      expect(document.getElementById('__print_iframe__')).toBeNull();
    });

    it('does not export when no questions are loaded and formats non-empty arrays', () => {
      setupLoadedManagerForm();
      component.groupedQuestions = [];
      component.responses['mq3'] = ['Ownership', 'Mentoring'];

      component.exportToPdf();

      expect(component.getResponseDisplay('mq3')).toBe('Ownership, Mentoring');
    });
  });

  describe('load failure and creation edge cases', () => {
    // Init failure branches should stop loading with a manager-readable message.
    it('shows a clear error when no active cycle is available', () => {
      setupComponent({ cycleId: 'c-1', milestoneId: 'mgr-ms', subjectUserId: 'subj-1' });
      employeeService.getCycles.and.returnValue(of([]));
      employeeService.getReviewsBySubject.and.returnValue(of([]));

      fixture.detectChanges();

      expect(component.loading).toBeFalse();
      expect(component.errorMsg).toBe('No active cycle found.');
    });

    it('shows a clear error when the cycle has no manager milestone', () => {
      setupLoadedManagerForm({
        cycle: buildCycle({ milestones: [buildCycle().milestones![0]] as any }),
      });

      expect(component.loading).toBeFalse();
      expect(component.errorMsg).toBe('No manager milestone found for this cycle.');
    });

    it('shows a clear error when the manager milestone has no template snapshot', () => {
      const cycle = buildCycle({
        milestones: [
          {
            milestoneId: 'mgr-ms',
            name: 'Manager',
            assignedRoles: ['MANAGER'],
            startDate: '2026-02-01',
            endDate: '2026-03-31',
            status: 'OPEN',
          } as any,
        ],
      });

      setupLoadedManagerForm({ cycle });

      expect(component.loading).toBeFalse();
      expect(component.errorMsg).toBe('No template attached to the manager milestone.');
    });

    it('keeps the form usable but reports when the manager template has no questions', () => {
      const cycle = buildCycle({
        milestones: [
          {
            milestoneId: 'mgr-ms',
            name: 'Manager',
            assignedRoles: ['MANAGER'],
            startDate: '2026-02-01',
            endDate: '2026-03-31',
            status: 'OPEN',
            templateSnapshot: { templateId: 'mgr-template', reviewType: 'MANAGER', questions: [] },
          } as any,
        ],
      });
      setupComponent({ cycleId: 'c-1', milestoneId: 'mgr-ms', subjectUserId: 'subj-1' });
      employeeService.getManagerGate.and.returnValue(of(buildGate({ open: true })));
      employeeService.getCycles.and.returnValue(of([cycle]));
      employeeService.getReviewsBySubject.and.returnValue(of([selfReview()]));
      employeeService.getTemplateById.and.returnValue(of({ templateId: 'mgr-template', questions: [] } as any));

      fixture.detectChanges();

      expect(component.loading).toBeFalse();
      expect(component.errorMsg).toBe('No questions found in manager template.');
      expect(component.review?.status).toBe('DRAFT');
    });

    it('uses create-then-update when saving a brand-new manager review', () => {
      setupLoadedManagerForm({ reviews: [selfReview()] });
      employeeService.createReview.and.returnValue(of({ reviewId: 'created-manager', version: 1 } as any));
      employeeService.updateReview.and.returnValue(of({ ...component.review!, reviewId: 'created-manager', version: 2 } as any));

      component.save('DRAFT');

      expect(employeeService.createReview).toHaveBeenCalled();
      expect(employeeService.updateReview).toHaveBeenCalled();
      expect(component.review?.reviewId).toBe('created-manager');
      expect(router.navigate).toHaveBeenCalledWith(['/my-team']);
    });

    it('loads employee insights from an existing self-review job', () => {
      setupComponent({ cycleId: 'c-1', milestoneId: 'mgr-ms', subjectUserId: 'subj-1' });
      employeeService.getManagerGate.and.returnValue(of(buildGate({ open: true })));
      employeeService.getCycles.and.returnValue(of([buildCycle()]));
      employeeService.getReviewsBySubject.and.returnValue(of([selfReview({ insightsJobId: 'insights-existing' }), managerReview()]));
      employeeService.getTemplateById.and.returnValue(of({ templateId: 'mgr-template', questions: managerQuestions } as any));
      aiReviewService.pollInsights.and.returnValue(of({
        jobId: 'insights-existing',
        status: 'COMPLETED' as const,
        result: {
          summary: 'Existing summary',
          goldenMirror: 'Existing mirror',
          citations: { c1: { source: 'self', reviewId: 'self-review-1', questionId: 'sq1' } },
        },
      }));

      fixture.detectChanges();

      expect(aiReviewService.pollInsights).toHaveBeenCalledWith('insights-existing');
      expect(component.employeeSummary).toBe('Existing summary');
    });

    it('marks employee insights polling failures as non-blocking', () => {
      setupComponent({ cycleId: 'c-1', milestoneId: 'mgr-ms', subjectUserId: 'subj-1' });
      employeeService.getManagerGate.and.returnValue(of(buildGate({ open: true })));
      employeeService.getCycles.and.returnValue(of([buildCycle()]));
      employeeService.getReviewsBySubject.and.returnValue(of([selfReview({ insightsJobId: 'insights-existing' }), managerReview()]));
      employeeService.getTemplateById.and.returnValue(of({ templateId: 'mgr-template', questions: managerQuestions } as any));
      aiReviewService.pollInsights.and.returnValue(throwError(() => new Error('insights failed')));

      fixture.detectChanges();

      expect(component.insightsFailed).toBeTrue();
    });
  });

  describe('suggestion edge cases', () => {
    // New manager reviews must be saved once before the async suggestion endpoint can run.
    it('saves a new draft before requesting a question suggestion', () => {
      setupLoadedManagerForm({ reviews: [selfReview()] });
      component.responses['mq1'] = 'Some manager draft answer';
      employeeService.createReview.and.returnValue(of({ reviewId: 'created-for-suggest', version: 1 } as any));
      employeeService.updateReview.and.returnValue(of({ ...component.review!, reviewId: 'created-for-suggest', version: 2 } as any));

      component.generateSuggestion(managerQuestions[0]);

      expect(employeeService.createReview).toHaveBeenCalled();
      expect(aiReviewService.startManagerQuestionSuggest).toHaveBeenCalledWith('created-for-suggest', jasmine.objectContaining({
        questionId: 'mq1',
        managerCurrentAnswer: 'Some manager draft answer',
      }));
    });

    it('reports when a new draft still has no review id for suggestions', () => {
      setupLoadedManagerForm({ reviews: [selfReview()] });
      component.responses['mq1'] = 'Some manager draft answer';
      employeeService.createReview.and.returnValue(of({} as any));
      employeeService.updateReview.and.returnValue(of({} as any));

      component.generateSuggestion(managerQuestions[0]);

      expect(component.questionSuggestionError['mq1']).toBe('Save a draft first.');
      expect(component.questionSuggestionLoading['mq1']).toBeFalse();
    });

    it('starts cooldown on 429 suggestion responses and clears dismissed suggestions manually', () => {
      jasmine.clock().install();
      try {
        setupLoadedManagerForm();
        component.responses['mq1'] = 'Some manager draft answer';
        aiReviewService.pollManagerQuestionSuggest.and.returnValue(throwError(() => ({ status: 429 })));

        component.generateSuggestion(managerQuestions[0]);

        expect(component.cooldownRemaining['mq1']).toBe(60);
        jasmine.clock().tick(60_000);
        expect(component.cooldownRemaining['mq1']).toBeUndefined();

        component.questionSuggestions['mq1'] = 'Suggestion';
        component.questionSuggestionCitations['mq1'] = { c1: { source: 'self' } as any };
        component.questionSuggestionError['mq1'] = 'Error';
        component.dismissSuggestion('mq1');

        expect(component.questionSuggestions['mq1']).toBeUndefined();
        expect(component.questionSuggestionCitations['mq1']).toBeUndefined();
        expect(component.questionSuggestionError['mq1']).toBeUndefined();
        expect(component.dismissedSuggestions.has('mq1')).toBeTrue();
      } finally {
        jasmine.clock().uninstall();
      }
    });

    it('uses peer digest context when asking for suggestions after digest is ready', () => {
      setupLoadedManagerForm();
      component.responses['mq1'] = 'Some manager draft answer';
      component.peer360DigestState = 'ready';
      component.peer360Digest = {
        themes: [{ theme: 'Collaboration', summary: 'Strong cross-team work' }],
        consensus: [{ point: 'Reliable delivery' }],
        dissent: [{ point: 'Could delegate more' }],
        overallSentiment: 'positive',
      } as any;

      component.generateSuggestion(managerQuestions[0]);

      const payload = aiReviewService.startManagerQuestionSuggest.calls.mostRecent().args[1] as any;
      expect(payload.peerDigest.themes).toEqual(['Collaboration: Strong cross-team work']);
      expect(payload.peerDigest.consensus).toEqual(['Reliable delivery']);
      expect(payload.peerDigest.dissent).toEqual(['Could delegate more']);
      expect(payload.peerDigest.overallSentiment).toBe('positive');
    });
  });
});
