import { ComponentFixture, fakeAsync, TestBed, tick } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import { of, throwError, Subject } from 'rxjs';

import { AuthService } from '../../core/auth/auth.service';
import { EmployeeService, ReviewCycle } from '../../services/employees.services';
import { AiReviewResponse, AiReviewService } from '../../services/ai-review.service';
import { ReviewFormComponent } from './review-form.component';
import { Question, Review } from '../../models/review.model';

describe('ReviewFormComponent AI review', () => {
  let fixture: ComponentFixture<ReviewFormComponent>;
  let component: ReviewFormComponent;
  let employeeService: jasmine.SpyObj<EmployeeService>;
  let aiReviewService: jasmine.SpyObj<AiReviewService>;
  let router: jasmine.SpyObj<Router>;
  let routeParams: Record<string, string>;
  let queryParams: Record<string, string>;
  let authPayload: { sub?: string } | null;

  // 25-word fixture — above the 10-word AI gate. Use this in any test that
  // expects buildQuestionPairs() to include the answer.
  const LONG_ANSWER =
    'I improved API reliability by adding retry logic, exponential backoff, and circuit breakers across the payment service. Incidents dropped by twenty percent during the cycle.';

  const questions: Question[] = [
    {
      questionId: 'q1',
      text: 'Describe your technical contributions.',
      type: 'text',
      required: true
    },
    {
      questionId: 'q2',
      text: 'Rate yourself.',
      type: 'rating',
      scale: 5
    }
  ];

  const cycle: ReviewCycle = {
    cycleId: 'cycle-1',
    cycleTitle: 'FY Review',
    startDate: '2026-01-01',
    dueDate: '2026-06-30',
    status: 'ACTIVE',
    milestones: [
      {
        milestoneId: 'milestone-1',
        name: 'Self Review',
        assignedRoles: ['EMPLOYEE'],
        startDate: '2026-01-01',
        endDate: '2026-02-01',
        status: 'OPEN',
        templateSnapshot: {
          templateId: 'template-1',
          questions
        }
      }
    ]
  };

  beforeEach(async () => {
    routeParams = { type: 'self' };
    queryParams = { cycleId: 'cycle-1', milestoneId: 'milestone-1' };
    authPayload = { sub: 'user-1' };
    employeeService = jasmine.createSpyObj<EmployeeService>('EmployeeService', [
      'getUserById',
      'getCycles',
      'getMyReviews',
      'getMySubjectReviews',
      'getReviewsBySubject',
      'getTemplateById',
      'createReview',
      'updateReview',
      'getUserPublic'
    ]);
    aiReviewService = jasmine.createSpyObj<AiReviewService>('AiReviewService', [
      'startAnalysis',
      'pollAnalysis',
      'startInsights',
      'getDisciplines'
    ]);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);

    employeeService.getUserById.and.returnValue(of({
      userId: 'user-1',
      email: 'employee@example.com',
      firstName: 'Eve',
      lastName: 'Employee',
      role: 'EMPLOYEE',
      position: 'Engineer',
      status: 'Active',
      reportingTo: null,
      seniority: 'Engineer',
      discipline: 'Software & DevOps Engineering'
    }));
    employeeService.getCycles.and.returnValue(of([cycle]));
    employeeService.getMyReviews.and.returnValue(of([]));
    employeeService.getMySubjectReviews.and.returnValue(of([]));
    employeeService.getUserPublic.and.returnValue(of(null as any));

    await TestBed.configureTestingModule({
      imports: [ReviewFormComponent],
      providers: [
        { provide: EmployeeService, useValue: employeeService },
        { provide: AiReviewService, useValue: aiReviewService },
        {
          provide: AuthService,
          useValue: {
            getTokenPayload: () => authPayload
          }
        },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              get paramMap() {
                return convertToParamMap(routeParams);
              },
              get queryParamMap() {
                return convertToParamMap(queryParams);
              }
            }
          }
        },
        { provide: Router, useValue: router }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(ReviewFormComponent);
    component = fixture.componentInstance;
  });

  it('titleFor names the peer subject once resolved (so the reviewer knows who it is for)', () => {
    component.reviewType = 'PEER_360';
    // Before the /public lookup resolves, the bare label is shown.
    expect(component.titleFor()).toBe('Peer 360 Review');
    // After resolution the subject is named in the header.
    component.peerSubjectName = 'Jordan Lee';
    expect(component.titleFor()).toBe('Peer 360 Review — Jordan Lee');
  });

  it('titleFor leaves SELF / MANAGER labels unchanged', () => {
    component.reviewType = 'SELF';
    expect(component.titleFor()).toBe('Self Review');
    component.reviewType = 'MANAGER';
    expect(component.titleFor()).toBe('Manager Review');
  });

  it('should not call AI analysis when no text answer is present', () => {
    component.questions = questions;
    component.responses = { q1: '', q2: 3 };
    (component as any).userSeniority = 'Engineer';
    (component as any).userDiscipline = 'Software & DevOps Engineering';

    component.runAiAnalysis();

    expect(aiReviewService.startAnalysis).not.toHaveBeenCalled();
    expect(component.aiError).toBe('Please write at least 10 words in a required text answer before getting suggestions.');
  });

  it('should not call AI analysis when required text answers are below the 10-word minimum', () => {
    component.questions = questions;
    component.responses = { q1: 'I improved API reliability.', q2: 3 }; // 4 words, required
    (component as any).userSeniority = 'Engineer';
    (component as any).userDiscipline = 'Software & DevOps Engineering';

    component.runAiAnalysis();

    expect(aiReviewService.startAnalysis).not.toHaveBeenCalled();
    expect(component.aiError).toBe('Please write at least 10 words in a required text answer before getting suggestions.');
  });

  it('should include optional text answers regardless of word count and skip required ones below the threshold', () => {
    // Pins the optional-exemption rule from buildQuestionPairs: required
    // questions must clear 10 words; optional questions ride along with
    // any non-empty content. Without this, the AI button would stay
    // blocked even when the user wrote a perfectly valid short paragraph
    // in an optional field.
    const mixed: Question[] = [
      { questionId: 'qReq', text: 'Required Q', type: 'text', required: true },
      { questionId: 'qOpt', text: 'Optional Q', type: 'text', required: false }
    ];
    component.questions = mixed;
    component.responses = { qReq: 'three words here', qOpt: 'brief but typed' }; // both under 10
    (component as any).userSeniority = 'Engineer';
    (component as any).userDiscipline = 'Software & DevOps Engineering';

    aiReviewService.startAnalysis.and.returnValue(of({ jobId: 'job-opt', status: 'PENDING' }));
    aiReviewService.pollAnalysis.and.returnValue(of({ suggestions: [] }));

    component.runAiAnalysis();

    expect(aiReviewService.startAnalysis).toHaveBeenCalled();
    const pairs = aiReviewService.startAnalysis.calls.mostRecent().args[0] as
      { questionId: string; questionText: string; answer: string }[];
    expect(pairs.length).toBe(1);
    expect(pairs[0].questionId).toBe('qOpt');
    expect(component.aiError).toBeFalsy();
  });

  it('should require seniority and discipline before AI analysis', () => {
    component.questions = questions;
    component.responses = { q1: LONG_ANSWER, q2: 3 };
    (component as any).userSeniority = '';
    (component as any).userDiscipline = '';

    component.runAiAnalysis();

    expect(aiReviewService.startAnalysis).not.toHaveBeenCalled();
    expect(component.aiError).toBe('Ask an admin to set your seniority and discipline in Employee Management before using suggestions.');
  });

  it('should call AI analysis with text answers and render returned suggestion bullets', () => {
    const response: AiReviewResponse = {
      suggestions: [
        {
          questionId: 'q1',
          questionText: 'Describe your technical contributions.',
          verdict: 'NEEDS_IMPROVEMENT',
          suggestions: [
            'Add a specific demo example.',
            'What measurable result did the API reliability work produce?'
          ]
        }
      ]
    };
    aiReviewService.startAnalysis.and.returnValue(of({ jobId: 'job-1', status: 'PENDING' }));
    aiReviewService.pollAnalysis.and.returnValue(of(response));
    fixture.detectChanges();
    component.loading = false;
    component.questions = questions;
    component.responses = { q1: LONG_ANSWER, q2: 3 };
    (component as any).userSeniority = 'Engineer';
    (component as any).userDiscipline = 'Software & DevOps Engineering';

    component.runAiAnalysis();
    fixture.detectChanges();

    expect(aiReviewService.startAnalysis).toHaveBeenCalledOnceWith(
      [
        {
          questionId: 'q1',
          questionText: 'Describe your technical contributions.',
          answer: LONG_ANSWER
        }
      ],
      'Engineer',
      'Software & DevOps Engineering'
    );
    expect(aiReviewService.pollAnalysis).toHaveBeenCalledOnceWith('job-1');
    expect(component.aiResult).toEqual(response);
    expect(component.totalAiItems).toBe(2);
    expect(fixture.nativeElement.textContent).toContain('A few things to consider');
    expect(fixture.nativeElement.textContent).toContain('Add a specific demo example.');
    expect(fixture.nativeElement.textContent).toContain('What measurable result did the API reliability work produce?');
  });

  it('should render STRONG feedback praise under a "Looking strong" badge', () => {
    const response: AiReviewResponse = {
      suggestions: [
        {
          questionId: 'q1',
          questionText: 'Describe your technical contributions.',
          verdict: 'STRONG',
          suggestions: ['Strong use of specific metrics and outcomes.']
        }
      ]
    };
    aiReviewService.startAnalysis.and.returnValue(of({ jobId: 'job-1', status: 'PENDING' }));
    aiReviewService.pollAnalysis.and.returnValue(of(response));
    fixture.detectChanges();
    component.loading = false;
    component.questions = questions;
    component.responses = { q1: LONG_ANSWER, q2: 3 };
    (component as any).userSeniority = 'Engineer';
    (component as any).userDiscipline = 'Software & DevOps Engineering';

    component.runAiAnalysis();
    fixture.detectChanges();

    // totalAiItems counts actionable (NEEDS_IMPROVEMENT) items only — STRONG praise is not "to-do"
    expect(component.totalAiItems).toBe(0);
    expect(fixture.nativeElement.textContent).toContain('Looking strong');
    expect(fixture.nativeElement.textContent).toContain('Strong use of specific metrics and outcomes.');
  });

  it('should ONLY attach feedback to its matching questionId — no positional fallback', () => {
    // Previously getQuestionFeedback fell back to a positional lookup when
    // the AI response questionId didn't match a local question. That coupling
    // attached the wrong feedback to the wrong question when the AI skipped
    // any input. Pins the strict-match-only contract.
    const mismatchedQuestions: Question[] = [
      {
        questionId: 'template-question-1',
        text: 'What did you deliver this cycle?',
        type: 'text',
        required: true
      },
      {
        questionId: 'template-question-2',
        text: 'What technical challenges did you solve?',
        type: 'text',
        required: true
      }
    ];
    const response: AiReviewResponse = {
      suggestions: [
        {
          questionId: 'q1',
          questionText: 'Describe your technical contributions.',
          verdict: 'NEEDS_IMPROVEMENT',
          suggestions: ['Add one concrete project example.']
        },
        {
          questionId: 'q2',
          questionText: 'Describe your stakeholder collaboration.',
          verdict: 'NEEDS_IMPROVEMENT',
          suggestions: ['Include a measurable outcome.']
        }
      ]
    };
    aiReviewService.startAnalysis.and.returnValue(of({ jobId: 'job-1', status: 'PENDING' }));
    aiReviewService.pollAnalysis.and.returnValue(of(response));
    fixture.detectChanges();
    component.loading = false;
    component.questions = mismatchedQuestions;
    component.responses = {
      'template-question-1': LONG_ANSWER,
      'template-question-2': LONG_ANSWER
    };
    (component as any).userSeniority = 'Engineer';
    (component as any).userDiscipline = 'Software & DevOps Engineering';

    component.runAiAnalysis();
    fixture.detectChanges();

    // Neither response item's questionId (q1, q2) matches the local
    // template-question-1/2. Strict matching → no feedback attaches anywhere.
    expect(component.getQuestionFeedback(mismatchedQuestions[0])).toBeNull();
    expect(component.getQuestionFeedback(mismatchedQuestions[1])).toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('Add one concrete project example.');
    expect(fixture.nativeElement.textContent).not.toContain('Include a measurable outcome.');
  });

  it('answerState should be in-progress for text answers below the AI word threshold', () => {
    // Sidebar visual contract: a text answer doesn't earn the green dot
    // until it clears MIN_WORDS_FOR_AI. Pre-fix the heuristic was 30 chars
    // OR sentence punctuation, which marked "test test test test test"
    // (35 chars) as fully answered.
    component.questions = questions; // q1 = text required, q2 = rating
    component.responses = { q1: 'test test test test test test test', q2: 3 }; // 7 words

    expect(component.answerState(questions[0])).toBe('in-progress');
    expect(component.isAnswered(questions[0])).toBeFalse();
    expect(component.belowAiThreshold(questions[0])).toBeTrue();
    expect(component.wordsTyped(questions[0])).toBe(7);
  });

  it('answerState should be answered once text clears the AI word threshold', () => {
    component.questions = questions;
    component.responses = { q1: LONG_ANSWER, q2: 3 }; // 25 words

    expect(component.answerState(questions[0])).toBe('answered');
    expect(component.isAnswered(questions[0])).toBeTrue();
    expect(component.belowAiThreshold(questions[0])).toBeFalse();
  });

  it('should disable suggestions after successful analysis and re-enable after editing a text answer', () => {
    const response: AiReviewResponse = {
      suggestions: [
        {
          questionId: 'q1',
          questionText: 'Describe your technical contributions.',
          verdict: 'NEEDS_IMPROVEMENT',
          suggestions: ['Add a measurable result.']
        }
      ]
    };
    aiReviewService.startAnalysis.and.returnValue(of({ jobId: 'job-1', status: 'PENDING' }));
    aiReviewService.pollAnalysis.and.returnValue(of(response));
    fixture.detectChanges();
    component.loading = false;
    component.questions = questions;
    component.responses = { q1: LONG_ANSWER, q2: 3 };
    (component as any).userSeniority = 'Engineer';
    (component as any).userDiscipline = 'Software & DevOps Engineering';

    component.runAiAnalysis();

    expect(component.aiButtonDisabled).toBeTrue();

    component.responses['q1'] = LONG_ANSWER + ' Additional context added in a later edit.';

    expect(component.aiButtonDisabled).toBeFalse();
  });

  it('should not start another AI analysis while one is already loading', () => {
    component.aiLoading = true;
    component.questions = questions;
    component.responses = { q1: 'I improved API reliability.' };
    (component as any).userSeniority = 'Engineer';
    (component as any).userDiscipline = 'Software & DevOps Engineering';

    component.runAiAnalysis();

    expect(aiReviewService.startAnalysis).not.toHaveBeenCalled();
  });

  it('should render an AI error when analysis fails', () => {
    aiReviewService.startAnalysis.and.returnValue(throwError(() => new Error('unavailable')));
    fixture.detectChanges();
    component.loading = false;
    component.questions = questions;
    component.responses = { q1: LONG_ANSWER };
    (component as any).userSeniority = 'Engineer';
    (component as any).userDiscipline = 'Software & DevOps Engineering';

    component.runAiAnalysis();
    fixture.detectChanges();

    expect(component.aiLoading).toBeFalse();
    expect(component.aiError).toBe('Suggestions are temporarily unavailable. Please try again later.');
    expect(component.aiError).toBeTruthy();
  });

  it('should persist the analyze jobId on the review record after a successful run', () => {
    const existingReview: Review = {
      reviewId: 'review-1',
      cycleId: 'cycle-1',
      milestoneId: 'milestone-1',
      templateId: 'template-1',
      userId: 'user-1',
      subjectUserId: 'user-1',
      authorUserId: 'user-1',
      reviewType: 'SELF',
      status: 'DRAFT',
      answers: []
    };
    const response: AiReviewResponse = {
      suggestions: [
        {
          questionId: 'q1',
          questionText: 'Describe your technical contributions.',
          verdict: 'NEEDS_IMPROVEMENT',
          suggestions: ['Add a measurable outcome.']
        }
      ]
    };
    aiReviewService.startAnalysis.and.returnValue(of({ jobId: 'analyze-job-1', status: 'PENDING' }));
    aiReviewService.pollAnalysis.and.returnValue(of(response));
    employeeService.updateReview.and.returnValue(of({ ...existingReview, analyzeJobId: 'analyze-job-1' }));
    component.review = existingReview;
    component.questions = questions;
    component.responses = { q1: LONG_ANSWER, q2: 3 };
    (component as any).userSeniority = 'Engineer';
    (component as any).userDiscipline = 'Software & DevOps Engineering';

    component.runAiAnalysis();

    expect(employeeService.updateReview).toHaveBeenCalledTimes(1);
    const sentReview = employeeService.updateReview.calls.argsFor(0)[0];
    expect(sentReview.reviewId).toBe('review-1');
    expect(sentReview.analyzeJobId).toBe('analyze-job-1');
    expect(component.review?.analyzeJobId).toBe('analyze-job-1');
    expect(component.aiResult).toEqual(response);
  });

  it('should still render suggestions when persistence fails (best-effort)', () => {
    const existingReview: Review = {
      reviewId: 'review-1',
      cycleId: 'cycle-1',
      milestoneId: 'milestone-1',
      templateId: 'template-1',
      userId: 'user-1',
      subjectUserId: 'user-1',
      authorUserId: 'user-1',
      reviewType: 'SELF',
      status: 'DRAFT',
      answers: []
    };
    const response: AiReviewResponse = {
      suggestions: [
        {
          questionId: 'q1',
          questionText: 'Describe your technical contributions.',
          verdict: 'STRONG',
          suggestions: ['Solid metrics.']
        }
      ]
    };
    aiReviewService.startAnalysis.and.returnValue(of({ jobId: 'analyze-job-1', status: 'PENDING' }));
    aiReviewService.pollAnalysis.and.returnValue(of(response));
    employeeService.updateReview.and.returnValue(throwError(() => new Error('save failed')));
    component.review = existingReview;
    component.questions = questions;
    component.responses = { q1: LONG_ANSWER, q2: 3 };
    (component as any).userSeniority = 'Engineer';
    (component as any).userDiscipline = 'Software & DevOps Engineering';

    component.runAiAnalysis();

    expect(component.aiResult).toEqual(response);
    expect(component.aiError).toBe('');
    expect(component.review?.analyzeJobId).toBe('analyze-job-1');
  });

  it('should hydrate aiResult on init when the existing review has an analyzeJobId', () => {
    const response: AiReviewResponse = {
      suggestions: [
        {
          questionId: 'q1',
          questionText: 'Describe your technical contributions.',
          verdict: 'NEEDS_IMPROVEMENT',
          suggestions: ['Add a measurable outcome.']
        }
      ]
    };
    const existingReview: Review = {
      reviewId: 'review-1',
      cycleId: 'cycle-1',
      milestoneId: 'milestone-1',
      templateId: 'template-1',
      userId: 'user-1',
      subjectUserId: 'user-1',
      authorUserId: 'user-1',
      reviewType: 'SELF',
      status: 'DRAFT',
      analyzeJobId: 'prior-job-1',
      answers: [{ questionId: 'q1', response: LONG_ANSWER }]
    };
    employeeService.getMyReviews.and.returnValue(of([existingReview]));
    aiReviewService.pollAnalysis.and.returnValue(of(response));

    fixture.detectChanges();

    expect(aiReviewService.pollAnalysis).toHaveBeenCalledOnceWith('prior-job-1');
    expect(component.aiResult).toEqual(response);
    expect(component.aiError).toBe('');
    expect(component.aiLoading).toBeFalse();
    // Signature is pinned so the rehydrated state doesn't re-enable a fresh run.
    expect(component.aiButtonDisabled).toBeTrue();
  });

  it('should silently ignore a stale analyzeJobId on init (no error toast)', () => {
    const existingReview: Review = {
      reviewId: 'review-1',
      cycleId: 'cycle-1',
      milestoneId: 'milestone-1',
      templateId: 'template-1',
      userId: 'user-1',
      subjectUserId: 'user-1',
      authorUserId: 'user-1',
      reviewType: 'SELF',
      status: 'DRAFT',
      analyzeJobId: 'expired-job-1',
      answers: [{ questionId: 'q1', response: LONG_ANSWER }]
    };
    employeeService.getMyReviews.and.returnValue(of([existingReview]));
    aiReviewService.pollAnalysis.and.returnValue(throwError(() => new Error('not found')));

    fixture.detectChanges();

    expect(component.aiResult).toBeNull();
    expect(component.aiError).toBe('');
    expect(component.aiLoading).toBeFalse();
  });

  it('should update an existing review without setting submittedAt client-side', () => {
    const existingReview: Review = {
      reviewId: 'review-1',
      cycleId: 'cycle-1',
      milestoneId: 'milestone-1',
      templateId: 'template-1',
      userId: 'user-1',
      subjectUserId: 'user-1',
      authorUserId: 'user-1',
      reviewType: 'SELF',
      status: 'DRAFT',
      answers: []
    };
    employeeService.updateReview.and.returnValue(of(existingReview));
    aiReviewService.startInsights.and.returnValue(of({ jobId: 'insight-job-1', status: 'PENDING' }));
    component.review = existingReview;
    component.questions = questions;
    component.responses = { q1: LONG_ANSWER, q2: 4 };
    (component as any).userSeniority = 'Engineer';
    (component as any).userDiscipline = 'Software & DevOps Engineering';

    component.save('COMPLETED');

    expect(employeeService.updateReview).toHaveBeenCalledTimes(2);
    const firstSave = employeeService.updateReview.calls.argsFor(0)[0];
    expect(firstSave.status).toBe('COMPLETED');
    expect(firstSave.answers).toEqual([
      { questionId: 'q1', response: LONG_ANSWER },
      { questionId: 'q2', response: 4 }
    ]);
    expect(firstSave.submittedAt).toBeUndefined();
    expect(aiReviewService.startInsights).toHaveBeenCalledOnceWith(
      [
        {
          questionId: 'q1',
          questionText: 'Describe your technical contributions.',
          answer: LONG_ANSWER
        }
      ],
      'Engineer',
      'Software & DevOps Engineering'
    );
    const secondSave = employeeService.updateReview.calls.argsFor(1)[0];
    expect(secondSave.insightsJobId).toBe('insight-job-1');
    expect(router.navigate).toHaveBeenCalledWith(['/my-review']);
  });

  it('should keep submit successful when insights call fails', () => {
    const existingReview: Review = {
      reviewId: 'review-1',
      cycleId: 'cycle-1',
      milestoneId: 'milestone-1',
      templateId: 'template-1',
      userId: 'user-1',
      subjectUserId: 'user-1',
      authorUserId: 'user-1',
      reviewType: 'SELF',
      status: 'DRAFT',
      answers: []
    };
    employeeService.updateReview.and.returnValue(of(existingReview));
    aiReviewService.startInsights.and.returnValue(throwError(() => new Error('unavailable')));
    component.review = existingReview;
    component.questions = questions;
    component.responses = { q1: LONG_ANSWER, q2: 4 };
    (component as any).userSeniority = 'Engineer';
    (component as any).userDiscipline = 'Software & DevOps Engineering';

    component.save('COMPLETED');

    expect(employeeService.updateReview).toHaveBeenCalledTimes(1);
    expect(router.navigate).toHaveBeenCalledWith(['/my-review']);
  });

  it('should show an error when there is no active cycle', () => {
    // Important empty-state branch: the form cannot continue without an active review cycle.
    employeeService.getCycles.and.returnValue(of([]));

    fixture.detectChanges();

    expect(component.loading).toBeFalse();
    expect(component.errorMsg).toBe('No active cycle found.');
  });

  it('should use default route values and first cycle when optional params are missing', () => {
    // Missing URL params should still open the first active self-review milestone.
    routeParams = {};
    queryParams = {};
    authPayload = null;

    fixture.detectChanges();

    expect(employeeService.getUserById).not.toHaveBeenCalled();
    expect(employeeService.getMyReviews).toHaveBeenCalledWith('');
    expect(component.reviewType).toBe('SELF');
    expect(component.cycle?.cycleId).toBe('cycle-1');
    expect(component.milestoneId).toBe('milestone-1');
  });

  it('should load readonly subject reviews and find the milestone from existing template id', () => {
    // Admin readonly routes read by subject and should honor the review's stored template id.
    routeParams = { type: 'manager' };
    queryParams = {
      cycleId: 'cycle-1',
      milestoneId: 'missing-milestone',
      reviewId: 'legacy-review-id',
      subjectUserId: 'subject-1',
      readonly: 'true'
    };
    const managerQuestion: Question = {
      questionId: 'mq1',
      text: 'Manager feedback',
      type: 'text',
      required: true
    };
    employeeService.getCycles.and.returnValue(of([{
      ...cycle,
      milestones: [
        cycle.milestones![0],
        {
          milestoneId: 'manager-milestone',
          name: 'Manager Review',
          assignedRoles: ['MANAGER'],
          startDate: '2026-01-01',
          endDate: '2026-02-01',
          status: 'OPEN',
          templateSnapshot: {
            templateId: 'manager-template',
            questions: [managerQuestion]
          }
        } as any
      ]
    }]));
    employeeService.getReviewsBySubject.and.returnValue(of([{
      id: 'legacy-review-id',
      cycleId: 'cycle-1',
      milestoneId: 'manager-milestone',
      templateId: 'manager-template',
      userId: 'subject-1',
      subjectUserId: 'subject-1',
      authorUserId: 'manager-1',
      reviewType: 'MANAGER',
      status: 'COMPLETE'
    } as any]));

    fixture.detectChanges();

    expect(employeeService.getReviewsBySubject).toHaveBeenCalledWith('subject-1');
    expect(component.reviewType).toBe('MANAGER');
    expect(component.milestoneId).toBe('manager-milestone');
    expect(component.questions).toEqual([managerQuestion]);
    expect(component.review?.reviewId).toBe('legacy-review-id');
    expect(component.readonlyReview).toBeTrue();
  });

  it('loads the caller\'s own PUBLISHED manager review from the subject feed, read-only', () => {
    // Employee opening the manager feedback published about them: no subjectUserId
    // and no readonly query param. The manager review is authored by the manager,
    // so it lives in the subject feed (getMySubjectReviews), never in getMyReviews
    // (assigned-to-me). Without sourcing from the subject feed the employee would
    // get a blank, editable draft instead of their published feedback.
    routeParams = { type: 'manager' };
    queryParams = {
      cycleId: 'cycle-1',
      milestoneId: 'manager-milestone',
      reviewId: 'mgr-published'
    };
    const managerQuestion: Question = {
      questionId: 'mq1',
      text: 'Manager feedback',
      type: 'text',
      required: true
    };
    employeeService.getCycles.and.returnValue(of([{
      ...cycle,
      milestones: [
        cycle.milestones![0],
        {
          milestoneId: 'manager-milestone',
          name: 'Manager Review',
          assignedRoles: ['MANAGER'],
          startDate: '2026-01-01',
          endDate: '2099-02-01',
          status: 'OPEN',
          templateSnapshot: {
            templateId: 'manager-template',
            reviewType: 'MANAGER',
            questions: [managerQuestion]
          }
        } as any
      ]
    }]));
    employeeService.getMySubjectReviews.and.returnValue(of([{
      reviewId: 'mgr-published',
      cycleId: 'cycle-1',
      milestoneId: 'manager-milestone',
      templateId: 'manager-template',
      userId: 'mgr-9',
      subjectUserId: 'user-1',
      authorUserId: 'mgr-9',
      reviewType: 'MANAGER',
      status: 'PUBLISHED',
      answers: [{ questionId: 'mq1', response: 'Strong delivery this cycle.' }]
    } as any]));

    fixture.detectChanges();

    expect(employeeService.getMySubjectReviews).toHaveBeenCalled();
    expect(employeeService.getMyReviews).not.toHaveBeenCalled();
    expect(employeeService.getReviewsBySubject).not.toHaveBeenCalled();
    expect(component.reviewType).toBe('MANAGER');
    expect(component.review?.reviewId).toBe('mgr-published');
    expect(component.readonlyReview).withContext('employee must not edit their manager review').toBeTrue();
    expect(component.responses['mq1']).toBe('Strong delivery this cycle.');
  });

  it('should load questions from the cycle template when milestone snapshot is missing', () => {
    // Covers the fallback path used by older cycles that only store templateId on the cycle.
    employeeService.getCycles.and.returnValue(of([{
      ...cycle,
      templateId: 'template-fallback',
      milestones: [{ ...cycle.milestones![0], templateSnapshot: undefined as any }]
    }]));
    employeeService.getTemplateById.and.returnValue(of({
      templateId: 'template-fallback',
      name: 'Fallback template',
      questions
    } as any));

    fixture.detectChanges();

    expect(employeeService.getTemplateById).toHaveBeenCalledWith('template-fallback');
    expect(component.questions).toEqual(questions);
    expect(component.templateId).toBe('template-fallback');
    expect(component.loading).toBeFalse();
  });

  it('should show no-question errors for empty templates and cycles without template ids', () => {
    // Both no-question paths should stop loading with the same user-facing error.
    employeeService.getCycles.and.returnValue(of([{
      ...cycle,
      templateId: 'empty-template',
      milestones: [{ ...cycle.milestones![0], templateSnapshot: undefined as any }]
    }]));
    employeeService.getTemplateById.and.returnValue(of({ templateId: 'empty-template', questions: [] } as any));

    fixture.detectChanges();

    expect(component.errorMsg).toBe('No questions found for this review.');
    expect(component.loading).toBeFalse();
  });

  it('should restore the latest matching review and mark submitted reviews readonly', () => {
    // Existing answers must win over empty defaults, and submitted statuses lock the form.
    const existingReviews = [
      {
        reviewId: 'older-review',
        cycleId: 'cycle-1',
        milestoneId: 'milestone-1',
        templateId: 'template-1',
        userId: 'user-1',
        subjectUserId: 'user-1',
        authorUserId: 'user-1',
        reviewType: 'SELF',
        status: 'DRAFT',
        updatedAt: '2026-01-02T00:00:00Z',
        answers: [{ questionId: 'q1', response: 'Older answer' }]
      },
      {
        reviewId: 'latest-review',
        cycleId: 'cycle-1',
        milestoneId: 'milestone-1',
        templateId: 'template-1',
        userId: 'user-1',
        subjectUserId: 'user-1',
        authorUserId: 'user-1',
        reviewType: 'SELF',
        status: 'SUBMITTED',
        updatedAt: '2026-01-03T00:00:00Z',
        answers: [{ questionId: 'q1', response: 'Latest answer' }]
      }
    ] as any as Review[];
    component.cycle = cycle;
    component.questions = [
      ...questions,
      { questionId: 'q3', text: 'Pick skills.', type: 'CHECKBOX', options: ['A', 'B'] }
    ];
    component.milestoneId = 'milestone-1';
    component.templateId = 'template-1';
    component.reviewType = 'SELF';
    (component as any).userId = 'user-1';

    (component as any).initResponses(existingReviews, '');

    expect(component.review?.reviewId).toBe('latest-review');
    expect(component.readonlyReview).toBeTrue();
    expect(component.responses['q1']).toBe('Latest answer');
    expect(component.responses['q2']).toBe('');
    expect(component.responses['q3']).toEqual([]);
  });

  it('should handle multi-select and rating interactions while respecting readonly mode', () => {
    // These helpers drive the visible input state for checkbox and rating question types.
    component.responses = {};

    component.toggleMulti('q3', 'Teamwork');
    expect(component.responses['q3']).toEqual(['Teamwork']);
    expect(component.isChecked('q3', 'Teamwork')).toBeTrue();

    component.toggleMulti('q3', 'Teamwork');
    expect(component.responses['q3']).toEqual([]);

    component.setRating('q2', 4);
    expect(component.ratingValue('q2')).toBe(4);
    expect(component.ratingArray(0)).toEqual([1, 2, 3, 4, 5]);

    component.readonlyReview = true;
    component.toggleMulti('q3', 'Leadership');
    component.setRating('q2', 2);

    expect(component.responses['q3']).toEqual([]);
    expect(component.ratingValue('q2')).toBe(4);
  });

  it('should stop completed save and mark required unanswered questions', fakeAsync(() => {
    // Submit validation highlights missing required answers before any API write happens.
    const scrollSpy = spyOn(window, 'scrollTo').and.stub();
    component.review = {
      reviewId: 'review-1',
      cycleId: 'cycle-1',
      milestoneId: 'milestone-1',
      templateId: 'template-1',
      userId: 'user-1',
      subjectUserId: 'user-1',
      authorUserId: 'user-1',
      reviewType: 'SELF',
      status: 'DRAFT',
      answers: []
    };
    component.questions = questions;
    component.responses = { q1: '   ', q2: 5 };

    component.save('COMPLETED');
    tick(50);

    expect(component.validationErrors).toEqual([
      { index: 1, text: 'Describe your technical contributions.', id: 'q1' }
    ]);
    expect(component.missingQuestionIds.has('q1')).toBeTrue();
    expect(employeeService.updateReview).not.toHaveBeenCalled();
    expect(scrollSpy).toHaveBeenCalled();
  }));

  it('should create then update a new review when submitting completed answers', () => {
    // Direct submit for a new review needs create first, then update because backend creates drafts.
    employeeService.createReview.and.returnValue(of({ reviewId: 'created-review', id: 'created-review' } as any));
    employeeService.updateReview.and.returnValue(of({ reviewId: 'created-review', status: 'COMPLETED' } as any));
    component.review = {
      cycleId: 'cycle-1',
      milestoneId: 'milestone-1',
      templateId: 'template-1',
      userId: 'user-1',
      subjectUserId: 'user-1',
      authorUserId: 'user-1',
      reviewType: 'SELF',
      status: 'DRAFT',
      answers: []
    } as Review;
    component.questions = questions;
    component.responses = { q1: 'I improved reliability.', q2: 5 };
    (component as any).userSeniority = '';
    (component as any).userDiscipline = '';

    component.save('COMPLETED');

    expect(employeeService.createReview).toHaveBeenCalled();
    expect(employeeService.updateReview).toHaveBeenCalledWith(jasmine.objectContaining({
      reviewId: 'created-review',
      status: 'COMPLETED'
    }));
    expect(router.navigate).toHaveBeenCalledWith(['/my-review']);
  });

  it('should autosave editable drafts after the debounce window', fakeAsync(() => {
    // Autosave is delayed, so this verifies the real draft save path instead of only calling save().
    employeeService.updateReview.and.returnValue(of({ reviewId: 'review-1', status: 'DRAFT' } as any));
    component.review = {
      reviewId: 'review-1',
      cycleId: 'cycle-1',
      milestoneId: 'milestone-1',
      templateId: 'template-1',
      userId: 'user-1',
      subjectUserId: 'user-1',
      authorUserId: 'user-1',
      reviewType: 'SELF',
      status: 'DRAFT',
      answers: []
    };
    component.questions = questions;
    component.responses = { q1: 'Draft answer', q2: 3 };
    (component as any).initAutoSave();

    component.triggerAutoSave();
    tick(2500);

    expect(component.autoSaveStatus).toBe('saved');
    expect(component.autoSavedAt).toEqual(jasmine.any(Date));
    expect(employeeService.updateReview).toHaveBeenCalledWith(jasmine.objectContaining({
      status: 'DRAFT',
      answers: [
        { questionId: 'q1', response: 'Draft answer' },
        { questionId: 'q2', response: 3 }
      ]
    }));
  }));

  it('should expose branchy display helpers for answered state, titles and AI freshness', () => {
    // Small getters feed the sidebar/title/button states and are easy to break during template edits.
    component.questions = questions;
    component.responses = { q1: LONG_ANSWER, q2: null as any };

    expect(component.answeredCount).toBe(1);
    expect(component.progressPct).toBe(50);

    component.reviewType = 'MANAGER';
    expect(component.titleFor()).toBe('Manager Review');
    component.reviewType = 'PEER_360';
    expect(component.titleFor()).toBe('Peer 360 Review');

    component.reviewType = 'SELF';
    (component as any).lastAnalyzedSignature = (component as any).currentAiSignature;
    expect(component.isCurrentAnalysisFresh).toBeTrue();
    expect(component.showUpdateAnswerHint).toBeTrue();
  });

  it('should cover question type and answer helper branches', () => {
    // Type aliases come from templates, so lower/upper/fallback values should behave consistently.
    expect(component.isTextQuestion({ questionId: 't', text: 'Text', type: undefined as any })).toBeFalse();
    expect(component.isYesNoQuestion({ questionId: 'y', text: 'Yes no', type: 'YES_NO' })).toBeTrue();
    expect(component.isMultiSelectQuestion({ questionId: 'm', text: 'Multi', type: 'MULTI_SELECT' })).toBeTrue();
    expect(component.isSingleSelectQuestion({ questionId: 's', text: 'Single', type: 'SINGLE_SELECT' })).toBeTrue();
    expect((component as any).hasMeaningfulAnswer(null)).toBeFalse();
    expect((component as any).hasMeaningfulAnswer([])).toBeFalse();
    expect((component as any).hasMeaningfulAnswer(['one'])).toBeTrue();
    expect((component as any).hasMeaningfulAnswer(3)).toBeTrue();
  });

  it('should handle save guard, draft branch and API error branch', () => {
    // Save should no-op before init, draft should not start insights, and API errors should surface to UI.
    component.review = null;
    component.save('DRAFT');
    expect(employeeService.updateReview).not.toHaveBeenCalled();
    expect(() => (component as any).saveOrUpdate('DRAFT')).toThrowError('Review is not initialized.');

    employeeService.updateReview.and.returnValue(of({ reviewId: 'review-1', id: 'saved-id' } as any));
    component.review = {
      reviewId: 'review-1',
      cycleId: 'cycle-1',
      milestoneId: 'milestone-1',
      templateId: 'template-1',
      userId: 'user-1',
      subjectUserId: 'user-1',
      authorUserId: 'user-1',
      reviewType: 'SELF',
      status: 'DRAFT',
      answers: []
    };
    component.questions = questions;
    component.responses = { q1: undefined as any, q2: 2 };

    component.save('DRAFT');

    expect(aiReviewService.startInsights).not.toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['/my-review']);

    employeeService.updateReview.and.returnValue(throwError(() => new Error('failed')));
    component.save('DRAFT');

    expect(component.saving).toBeFalse();
    expect(component.errorMsg).toBe('Failed to save. Please try again.');
  });

  it('should skip insights when required data is missing', () => {
    // Insight generation is best-effort and should safely skip incomplete states.
    component.review = null;
    expect((component as any).startInsightsAndPersistJobId()).toBeTruthy();

    component.review = {
      cycleId: 'cycle-1',
      milestoneId: 'milestone-1',
      templateId: 'template-1',
      userId: 'user-1',
      subjectUserId: 'user-1',
      authorUserId: 'user-1',
      reviewType: 'SELF',
      status: 'DRAFT',
      answers: []
    } as Review;
    (component as any).userSeniority = 'Engineer';
    (component as any).userDiscipline = 'Software';
    expect((component as any).startInsightsAndPersistJobId()).toBeTruthy();

    component.review.reviewId = 'review-1';
    component.questions = [{ questionId: 'q2', text: 'Rate yourself.', type: 'rating' }];
    component.responses = { q2: 5 };
    expect((component as any).startInsightsAndPersistJobId()).toBeTruthy();
    expect(aiReviewService.startInsights).not.toHaveBeenCalled();
  });

  it('should expose AI feedback and rail visibility edge branches', () => {
    // Suggestions with blank text are filtered, while rail visibility still shows empty successful results.
    component.aiResult = {
      suggestions: [
        {
          questionId: 'q1',
          questionText: 'Describe your technical contributions.',
          verdict: 'NEEDS_IMPROVEMENT',
          suggestions: ['  ', 'Add numbers.']
        },
        {
          questionId: 'q2',
          questionText: 'Rate yourself.',
          verdict: 'STRONG'
        } as any
      ]
    };

    expect(component.questionFeedback[0].suggestions).toEqual(['Add numbers.']);
    expect(component.questionFeedback[1].suggestions).toEqual([]);
    expect(component.railVisible).toBeFalse();

    component.aiResult = { suggestions: [{ questionId: 'q1', questionText: 'Q1', verdict: 'STRONG' }] as any };
    expect(component.railVisible).toBeTrue();

    component.aiResult = null;
    component.aiError = 'Temporarily unavailable';
    expect(component.railVisible).toBeTrue();
  });

  it('should cover date, scroll, missing-state and title utility branches', () => {
    // Utility branches feed deadline banners, sidebar scrolling and autosave labels.
    const element = document.createElement('div');
    spyOn(element, 'scrollIntoView');
    spyOn(document, 'getElementById').and.returnValue(element);

    expect((component as any).isMilestoneExpired()).toBeFalse();
    expect((component as any).isMilestoneExpired('bad-date')).toBeFalse();
    expect((component as any).isMilestoneExpired('01/01/2000')).toBeTrue();

    component.scrollToQuestion('q1');
    expect(element.scrollIntoView).toHaveBeenCalled();

    component.validationErrors = [{ index: 1, text: 'Question', id: 'q1' }];
    component.missingQuestionIds = new Set(['q1']);
    component.clearMissing('q1');
    expect(component.validationErrors).toEqual([]);
    expect(component.missingQuestionIds.size).toBe(0);

    component.questions = [];
    expect(component.progressPct).toBe(0);
    component.responses = { list: ['x'] as any };
    expect(component.isAnswered({ questionId: 'list', text: 'List', type: 'MULTI_SELECT' })).toBeTrue();

    spyOn(Date, 'now').and.returnValues(
      new Date('2026-01-01T00:00:05Z').getTime(),
      new Date('2026-01-01T00:00:30Z').getTime(),
      new Date('2026-01-01T00:02:00Z').getTime()
    );
    component.autoSavedAt = new Date('2026-01-01T00:00:00Z');
    expect(component.autoSavedAgo).toBe('just now');
    expect(component.autoSavedAgo).toBe('30s ago');
    expect(component.autoSavedAgo).toBe('2m ago');

    component.cancel();
    expect(router.navigate).toHaveBeenCalledWith(['/my-review']);
  });

  it('should match milestones by snapshot type and role-like names', () => {
    // Route fallback chooses a milestone by review type when there is no exact milestone id.
    expect((component as any).milestoneMatchesType({ templateSnapshot: { reviewType: 'MANAGER' } }, 'MANAGER')).toBeTrue();
    expect((component as any).milestoneMatchesType({ name: 'Peer 360 feedback' }, 'PEER_360')).toBeTrue();
    expect((component as any).milestoneMatchesType({ name: 'Manager review' }, 'MANAGER')).toBeTrue();
    expect((component as any).milestoneMatchesType({ name: 'Self reflection' }, 'SELF')).toBeTrue();
    expect((component as any).milestoneMatchesType({ name: 'Other' }, 'SELF')).toBeFalse();
  });
});
