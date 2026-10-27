import { fakeAsync, TestBed, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting
} from '@angular/common/http/testing';

import { environment } from '../../environment/environment';
import {
  AiReviewInsightsResponse,
  AiReviewInsightsStatusResponse,
  AiReviewResponse,
  AiReviewService,
  DisciplineInfo,
  ManagerJobStatusResponse,
  Peer360Digest,
  QuestionSuggestRequest,
} from './ai-review.service';
import { ManagerGoldenReview } from '../models/review.model';

describe('AiReviewService', () => {
  let service: AiReviewService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        AiReviewService,
        provideHttpClient(),
        provideHttpClientTesting()
      ]
    });

    service = TestBed.inject(AiReviewService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should trigger analysis and poll until completed', fakeAsync(() => {
    const questions = [
      {
        questionId: 'q1',
        questionText: 'Describe your technical contributions.',
        answer: 'I led the API migration.'
      }
    ];
    const response: AiReviewResponse = {
      suggestions: [
        {
          questionId: 'q1',
          questionText: 'Describe your technical contributions.',
          verdict: 'NEEDS_IMPROVEMENT',
          suggestions: ['Add a stakeholder demo example.']
        }
      ]
    };

    let actual: AiReviewResponse | undefined;
    service.analyzeReview(questions, 'Engineer', 'Software & DevOps Engineering').subscribe(result => {
      actual = result;
      expect(result).toEqual(response);
      expect(result.suggestions[0].verdict).toBe('NEEDS_IMPROVEMENT');
    });

    const req = httpMock.expectOne(`${environment.apiUrl}/ai-review/analyze`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      questions,
      seniority: 'Engineer',
      discipline: 'Software & DevOps Engineering'
    });
    req.flush({ jobId: 'job-1', status: 'PENDING' });

    tick(0);
    const pendingReq = httpMock.expectOne(`${environment.apiUrl}/ai-review/analyze/job-1/status`);
    expect(pendingReq.request.method).toBe('GET');
    pendingReq.flush({ jobId: 'job-1', status: 'PENDING' });

    tick(1000);
    const completedReq = httpMock.expectOne(`${environment.apiUrl}/ai-review/analyze/job-1/status`);
    completedReq.flush({ jobId: 'job-1', status: 'COMPLETED', result: response });

    expect(actual).toEqual(response);
  }));

  it('should poll through PROCESSING before COMPLETED', fakeAsync(() => {
    const result: AiReviewResponse = { suggestions: [] };
    let actual: AiReviewResponse | undefined;

    service.analyzeReview([], 'Engineer', 'Software & DevOps Engineering').subscribe(r => {
      actual = r;
    });

    httpMock.expectOne(`${environment.apiUrl}/ai-review/analyze`)
      .flush({ jobId: 'job-2', status: 'PENDING' });

    tick(0);
    httpMock.expectOne(`${environment.apiUrl}/ai-review/analyze/job-2/status`)
      .flush({ jobId: 'job-2', status: 'PROCESSING' });

    tick(1000);
    httpMock.expectOne(`${environment.apiUrl}/ai-review/analyze/job-2/status`)
      .flush({ jobId: 'job-2', status: 'COMPLETED', result });

    expect(actual).toEqual(result);
  }));

  it('should GET disciplines from /ai-review/disciplines and cache the result', () => {
    const disciplines: DisciplineInfo[] = [
      {
        discipline: 'Software & DevOps Engineering',
        levels: [{ key: 'Engineer', displayName: 'Engineer' }]
      }
    ];

    service.getDisciplines().subscribe(result => {
      expect(result).toEqual(disciplines);
      expect(result[0].levels[0].key).toBe('Engineer');
    });

    const req = httpMock.expectOne(`${environment.apiUrl}/ai-review/disciplines`);
    expect(req.request.method).toBe('GET');
    req.flush(disciplines);

    // shareReplay caches for the SPA session — subsequent calls must not re-fetch.
    let cached: DisciplineInfo[] | undefined;
    service.getDisciplines().subscribe(r => { cached = r; });
    expect(cached).toEqual(disciplines);
    httpMock.expectNone(`${environment.apiUrl}/ai-review/disciplines`);
  });

  it('should POST insights trigger to /ai-review/insights and return job payload', () => {
    const questions = [
      {
        questionId: 'q1',
        questionText: 'Describe your technical contributions.',
        answer: 'I led the API migration.'
      }
    ];
    const job = { jobId: 'insight-job-1', status: 'PENDING' as const };

    service.startInsights(questions, 'Engineer', 'Software & DevOps Engineering').subscribe(result => {
      expect(result).toEqual(job);
    });

    const req = httpMock.expectOne(`${environment.apiUrl}/ai-review/insights`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      questions,
      seniority: 'Engineer',
      discipline: 'Software & DevOps Engineering'
    });
    req.flush(job);
  });

  it('should propagate trigger errors', () => {
    let failed = false;

    service.analyzeReview([], 'Engineer', 'Software & DevOps Engineering').subscribe({
      next: () => fail('expected analyze error'),
      error: () => { failed = true; }
    });

    httpMock.expectOne(`${environment.apiUrl}/ai-review/analyze`)
      .flush({ message: 'AI service unavailable' }, { status: 503, statusText: 'Service Unavailable' });

    expect(failed).toBeTrue();
  });

  it('should propagate failed polling status', fakeAsync(() => {
    let failed = false;

    service.analyzeReview([], 'Engineer', 'Software & DevOps Engineering').subscribe({
      next: () => fail('expected polling error'),
      error: () => { failed = true; }
    });

    httpMock.expectOne(`${environment.apiUrl}/ai-review/analyze`)
      .flush({ jobId: 'job-3', status: 'PENDING' });

    tick(0);
    httpMock.expectOne(`${environment.apiUrl}/ai-review/analyze/job-3/status`)
      .flush({ jobId: 'job-3', status: 'FAILED', errorMessage: 'AI service unavailable' });

    expect(failed).toBeTrue();
  }));

  it('should error when analysis completes without a result', fakeAsync(() => {
    let message = '';

    service.pollAnalysis('job-empty').subscribe({
      next: () => fail('expected missing-result error'),
      error: err => { message = err.message; },
    });

    tick(0);
    httpMock.expectOne(`${environment.apiUrl}/ai-review/analyze/job-empty/status`)
      .flush({ jobId: 'job-empty', status: 'COMPLETED' });

    // A completed job with no payload must route the UI to its retry/fallback state.
    expect(message).toContain('no result');
  }));

  it('should poll insights until completed and emit the insight payload', fakeAsync(() => {
    const result: AiReviewInsightsResponse = {
      summary: 'Strong delivery.',
      goldenMirror: 'Clear impact with [c1].',
      citations: { c1: { source: 'self', reviewId: 'self-1', questionId: 'q1' } },
    };
    let actual: AiReviewInsightsStatusResponse | undefined;

    service.pollInsights('insights-1').subscribe(r => actual = r);

    tick(0);
    httpMock.expectOne(`${environment.apiUrl}/ai-review/insights/insights-1/status`)
      .flush({ jobId: 'insights-1', status: 'COMPLETED', result });

    expect(actual?.result).toEqual(result);
  }));

  it('should error when insights completes without a result', fakeAsync(() => {
    let message = '';

    service.pollInsights('insights-empty').subscribe({
      next: () => fail('expected missing-result error'),
      error: err => { message = err.message; },
    });

    tick(0);
    httpMock.expectOne(`${environment.apiUrl}/ai-review/insights/insights-empty/status`)
      .flush({ jobId: 'insights-empty', status: 'COMPLETED' });

    // This guards the code-review note: null insights are a failure, not "empty success".
    expect(message).toContain('no result');
  }));

  it('should surface failed insights and default failure messages', fakeAsync(() => {
    let insightsFailed = '';
    let analysisDefault = '';
    let managerDefault = '';

    service.pollInsights('insights-failed').subscribe({
      next: () => fail('expected insights failure'),
      error: err => { insightsFailed = err.message; },
    });
    tick(0);
    httpMock.expectOne(`${environment.apiUrl}/ai-review/insights/insights-failed/status`)
      .flush({ jobId: 'insights-failed', status: 'FAILED', errorMessage: 'Insights quota exceeded' });

    service.pollAnalysis('analysis-failed').subscribe({
      next: () => fail('expected analysis failure'),
      error: err => { analysisDefault = err.message; },
    });
    tick(0);
    httpMock.expectOne(`${environment.apiUrl}/ai-review/analyze/analysis-failed/status`)
      .flush({ jobId: 'analysis-failed', status: 'FAILED' });

    service.pollManagerGolden('manager-failed').subscribe({
      next: () => fail('expected manager failure'),
      error: err => { managerDefault = err.message; },
    });
    tick(0);
    httpMock.expectOne(`${environment.apiUrl}/ai-review/manager/jobs/manager-failed/status`)
      .flush({ status: 'FAILED' });

    // These branches provide useful fallback labels when the backend omits errorMessage.
    expect(insightsFailed).toBe('Insights quota exceeded');
    expect(analysisDefault).toBe('AI suggestions failed');
    expect(managerDefault).toBe('manager golden failed');
  }));

  it('should enqueue manager golden generation for a review', () => {
    const job = { jobId: 'golden-job-1', status: 'PENDING' as const };

    service.startManagerGolden('mgr-review-1').subscribe(result => expect(result).toEqual(job));

    const req = httpMock.expectOne(`${environment.apiUrl}/ai-review/manager/reviews/mgr-review-1/golden`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({});
    req.flush(job);
  });

  it('should enqueue manager question suggestions with the full request body', () => {
    const request: QuestionSuggestRequest = {
      questionId: 'q1',
      questionText: 'How did they perform?',
      managerCurrentAnswer: 'Solid delivery.',
      discipline: 'Software',
      seniority: 'Senior',
      employeeReview: {
        answers: [{ questionId: 'q-self', questionText: 'Impact?', response: 'Migrated API.' }],
        overallComment: 'Strong cycle.',
        overallRating: '4',
      },
      peerDigest: {
        themes: ['delivery'],
        consensus: ['clear ownership'],
        dissent: [],
        overallSentiment: 'positive',
      },
    };

    service.startManagerQuestionSuggest('mgr-review-1', request).subscribe(result => {
      expect(result.jobId).toBe('suggest-job-1');
    });

    const req = httpMock.expectOne(`${environment.apiUrl}/ai-review/manager/reviews/mgr-review-1/question-suggest`);
    expect(req.request.method).toBe('POST');
    // Manager suggestion depends on rich context, so verify the service does not trim it.
    expect(req.request.body).toEqual(request);
    req.flush({ jobId: 'suggest-job-1', status: 'PENDING' });
  });

  it('should poll manager golden jobs through the loose manager-jobs endpoint', fakeAsync(() => {
    const golden: ManagerGoldenReview = {
      technologyRating: 4,
      technologyComments: 'Strong technical impact.',
      consultingRating: 3,
      consultingComments: 'Good stakeholder management.',
      companyValuesComments: 'Collaborative.',
      developmentOpportunities: 'More delegation.',
      otherComments: 'N/A',
      overallRating: 4,
    };
    let actual: ManagerJobStatusResponse<ManagerGoldenReview> | undefined;

    service.pollManagerGolden('golden-job-1').subscribe(result => actual = result);

    tick(0);
    const req = httpMock.expectOne(`${environment.apiUrl}/ai-review/manager/jobs/golden-job-1/status`);
    expect(req.request.method).toBe('GET');
    req.flush({ status: 'COMPLETED', result: golden });

    expect(actual?.result).toEqual(golden);
  }));

  it('should surface manager polling failures with backend message or endpoint label', fakeAsync(() => {
    let failed = '';
    let missingResult = '';

    service.pollManagerQuestionSuggest('suggest-failed').subscribe({
      next: () => fail('expected manager suggestion failure'),
      error: err => { failed = err.message; },
    });
    tick(0);
    httpMock.expectOne(`${environment.apiUrl}/ai-review/manager/jobs/suggest-failed/status`)
      .flush({ status: 'FAILED', errorMessage: 'Gemini quota exceeded' });

    service.pollPeer360Digest('digest-empty').subscribe({
      next: () => fail('expected peer digest missing result failure'),
      error: err => { missingResult = err.message; },
    });
    tick(0);
    httpMock.expectOne(`${environment.apiUrl}/ai-review/manager/jobs/digest-empty/status`)
      .flush({ status: 'COMPLETED' });

    expect(failed).toBe('Gemini quota exceeded');
    expect(missingResult).toContain('peer-360 digest returned no result');
  }));

  it('should enqueue and poll peer360 digest jobs with encoded path segments', fakeAsync(() => {
    const digest: Peer360Digest = {
      themes: [{ theme: 'Delivery', summary: 'Consistent ownership.' }],
      consensus: [{ point: 'Communicates clearly.', reviewIds: ['peer-1'] }],
      dissent: [],
      overallSentiment: 'positive',
    };
    let actual: ManagerJobStatusResponse<Peer360Digest> | undefined;

    service.startPeer360Digest('cycle/1', 'subject A').subscribe(result => {
      expect(result.jobId).toBe('digest-job-1');
    });

    const startReq = httpMock.expectOne(
      `${environment.apiUrl}/ai-review/manager/cycles/cycle%2F1/subjects/subject%20A/peer360-digest`
    );
    expect(startReq.request.method).toBe('POST');
    expect(startReq.request.body).toEqual({});
    startReq.flush({ jobId: 'digest-job-1', status: 'PENDING' });

    service.pollPeer360Digest('digest-job-1').subscribe(result => actual = result);
    tick(0);
    httpMock.expectOne(`${environment.apiUrl}/ai-review/manager/jobs/digest-job-1/status`)
      .flush({ status: 'COMPLETED', result: digest });

    expect(actual?.result).toEqual(digest);
  }));
});
