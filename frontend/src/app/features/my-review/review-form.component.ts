import { Component, OnInit, OnDestroy, inject } from '@angular/core';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { SlicePipe } from '@angular/common';
import { forkJoin, Observable, of, switchMap, Subject } from 'rxjs';
import { catchError, tap, debounceTime, takeUntil } from 'rxjs/operators';

import { AuthService } from '../../core/auth/auth.service';
import { EmployeeService, ReviewCycle } from '../../services/employees.services';
import { AiReviewQuestionFeedback, AiReviewResponse, AiReviewService } from '../../services/ai-review.service';
import { Answer, Question, Review, ReviewType } from '../../models/review.model';
import { SkeletonComponent } from '../../shared/skeleton/skeleton.component';
import { AutosizeDirective } from '../../shared/autosize.directive';
import { escapeHtml } from '../../core/utils/escape-html';

type AnswerValue = Answer['response'];

@Component({
  selector: 'app-review-form',
  standalone: true,
  imports: [FormsModule, RouterModule, SkeletonComponent, SlicePipe, AutosizeDirective],
  templateUrl: './review-form.component.html',
  styleUrls: ['./review-form.component.css'],
})
export class ReviewFormComponent implements OnInit, OnDestroy {
  private employeeService = inject(EmployeeService);
  private aiReviewService = inject(AiReviewService);
  private authService = inject(AuthService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);

  cycle: ReviewCycle | null = null;
  review: Review | null = null;
  reviewType: ReviewType = 'SELF';

  /** For a PEER_360 fill, the display name of the colleague being reviewed.
   *  Resolved via the PII-safe /users/{id}/public projection so the employee
   *  always knows whose performance they're writing about. */
  peerSubjectName: string | null = null;

  questions: Question[] = [];
  responses: Record<string, AnswerValue> = {};

  milestoneId = '';
  templateId = '';

  saving = false;
  loading = true;
  errorMsg = '';
  readonlyReview = false;
  milestoneExpired = false;
  validationErrors: { index: number; text: string; id: string }[] = [];
  missingQuestionIds = new Set<string>();

  // ── Autosave ───────────────────────────────────────────────
  autoSaveStatus: '' | 'saving' | 'saved' | 'error' = '';
  autoSavedAt: Date | null = null;
  private autoSave$ = new Subject<void>();
  private destroy$  = new Subject<void>();

  // ── Review Suggestions ─────────────────────────────────────────
  aiLoading = false;
  aiError = '';
  aiResult: AiReviewResponse | null = null;
  private lastAnalyzedSignature = '';
  // The normalized answer text that each question's feedback was generated for.
  // Used to hide stale feedback once an answer is edited or cleared.
  private analyzedAnswers = new Map<string, string>();
  private userSeniority = '';
  private userDiscipline = '';
  // AI threshold: required text answers must clear MIN_WORDS_FOR_AI before
  // they're sent to /ai-review/analyze. Optional text answers are exempt —
  // anything non-empty rides along (see buildQuestionPairs). The user can
  // skip optional questions entirely and still get suggestions on whatever
  // they wrote in the required ones.
  private readonly MIN_WORDS_FOR_AI = 10;

  private userId = '';

  ngOnInit(): void {
    this.userId = this.authService.getTokenPayload()?.sub ?? '';

    if (this.userId) {
      this.employeeService.getUserById(this.userId).pipe(catchError(() => of(null))).subscribe(user => {
        this.userSeniority = user?.seniority ?? '';
        this.userDiscipline = user?.discipline ?? '';
      });
    }

    // URL types ('self' | 'manager' | 'peer-360' | 'peer_360') → ReviewType enum.
    const rawType = (this.route.snapshot.paramMap.get('type') || 'self').toUpperCase().replace(/-/g, '_');
    this.reviewType = (rawType === 'PEER_360' || rawType === 'MANAGER' ? rawType : 'SELF') as ReviewType;

    const cycleId = this.route.snapshot.queryParamMap.get('cycleId') ?? '';
    const milestoneId = this.route.snapshot.queryParamMap.get('milestoneId') ?? '';
    const reviewId = this.route.snapshot.queryParamMap.get('reviewId') ?? '';
    const subjectUserId = this.route.snapshot.queryParamMap.get('subjectUserId') ?? '';
    this.readonlyReview = this.route.snapshot.queryParamMap.get('readonly') === 'true';

    // An employee's own MANAGER review is feedback ABOUT them — it is never editable
    // here. (Managers AUTHOR manager reviews via ManagerReviewFormComponent, not this
    // form, which only serves the subject's own SELF / PEER_360 / MANAGER views.)
    if (this.reviewType === 'MANAGER') {
      this.readonlyReview = true;
    }

    this.milestoneId = milestoneId;

    // Admin viewing another employee's review — fetch by subject instead of by logged-in user.
    // For the caller's OWN manager review, fetch from the subject-indexed feed
    // (getMySubjectReviews): the manager review is authored by the manager, so it is
    // absent from the author-indexed assigned-to-me feed (getMyReviews) that backs the
    // SELF / PEER_360 reviews the caller authors themselves. Without this an employee
    // could not load the manager feedback published about them.
    const reviews$ = subjectUserId && this.readonlyReview
      ? this.employeeService.getReviewsBySubject(subjectUserId).pipe(catchError(() => of([])))
      : this.reviewType === 'MANAGER'
        ? this.employeeService.getMySubjectReviews().pipe(catchError(() => of([])))
        : this.employeeService.getMyReviews(this.userId).pipe(catchError(() => of([])));

    forkJoin({
      cycles: this.employeeService.getCycles('ACTIVE').pipe(catchError(() => of([]))),
      reviews: reviews$,
    }).subscribe(({ cycles, reviews }) => {
      this.cycle = cycles.find(c => c.cycleId === cycleId) ?? cycles[0] ?? null;

      if (!this.cycle) {
        this.errorMsg = 'No active cycle found.';
        this.loading = false;
        return;
      }

      // For an existing review (e.g. PEER_360 assignment), the row already knows which
      // template/milestone it was created against. Prefer that over a guessed first
      // milestone — otherwise a peer fills in the SELF milestone's questions.
      const existingForRoute = reviewId
        ? reviews.find(r => r.reviewId === reviewId || (r as any).id === reviewId)
        : null;
      const lookupTemplateId = existingForRoute?.templateId;

      // PEER_360 fill: surface WHO this review is about. The assignment row
      // carries subjectUserId; resolve it to a name via the public projection
      // (any authenticated caller). Without this the header just said
      // "Peer 360 Review" and the reviewer had no idea whose performance
      // they were assessing.
      if (this.reviewType === 'PEER_360') {
        const subjId =
          (existingForRoute as any)?.subjectUserId
          ?? (existingForRoute as any)?.userId
          ?? subjectUserId;
        if (subjId) {
          this.employeeService.getUserPublic(subjId)
            .pipe(catchError(() => of(null)))
            .subscribe(u => {
              const name = u ? `${u.firstName ?? ''} ${u.lastName ?? ''}`.trim() : '';
              this.peerSubjectName = name || null;
            });
        }
      }

      const milestone =
        this.cycle.milestones?.find(m => m.milestoneId === milestoneId) ??
        (lookupTemplateId
          ? this.cycle.milestones?.find(m => m.templateSnapshot?.templateId === lookupTemplateId)
          : undefined) ??
        this.cycle.milestones?.find(m => this.milestoneMatchesType(m, this.reviewType)) ??
        this.cycle.milestones?.[0];

      if (milestone?.templateSnapshot?.questions?.length) {
        this.questions = milestone.templateSnapshot.questions;
        this.milestoneId = milestone.milestoneId;
        this.templateId = milestone.templateSnapshot.templateId ?? this.cycle.templateId ?? '';
        this.milestoneExpired = this.isMilestoneExpired(milestone.endDate);
        this.initResponses(reviews, reviewId);
        return;
      }

      if (this.cycle.templateId) {
        this.employeeService
          .getTemplateById(this.cycle.templateId)
          .pipe(catchError(() => of(null)))
          .subscribe(template => {
            if (template?.questions?.length) {
              this.questions = template.questions;
              this.templateId = template.templateId ?? this.cycle?.templateId ?? '';
            } else {
              this.errorMsg = 'No questions found for this review.';
            }

            this.initResponses(reviews, reviewId);
          });

        return;
      }

      this.errorMsg = 'No questions found for this review.';
      this.loading = false;
    });
  }

  private initResponses(reviews: Review[], reviewId: string): void {
    const existing = this.findExistingReview(reviews, reviewId);

    this.responses = {};
    this.aiResult = null;
    this.analyzedAnswers.clear();
    this.aiError = '';
    this.lastAnalyzedSignature = '';

    if (existing) {
      this.review = {
        ...existing,
        reviewId: (existing as any).reviewId ?? (existing as any).id,
        id: (existing as any).id ?? (existing as any).reviewId,
      } as any;
      this.readonlyReview = this.readonlyReview || this.isSubmittedStatus(existing.status);

      for (const a of existing.answers ?? []) {
        this.responses[a.questionId] = a.response;
      }

      this.initialiseEmptyResponses();
      this.hydrateAiFromExisting(existing);
    } else {
      this.review = {
        cycleId: this.cycle!.cycleId,
        milestoneId: this.milestoneId,
        templateId: this.templateId,
        subjectUserId: this.userId,
        authorUserId: this.userId,
        userId: this.userId,
        reviewType: this.reviewType,
        status: 'DRAFT',
        answers: [],
      } as Review;

      this.initialiseEmptyResponses();
    }

    this.loading = false;
    this.initAutoSave();
  }

  private initAutoSave(): void {
    this.autoSave$
      .pipe(
        debounceTime(2500),
        takeUntil(this.destroy$),
      )
      .subscribe(() => {
        if (this.readonlyReview || this.milestoneExpired || !this.review) return;
        this.autoSaveStatus = 'saving';
        this.saveOrUpdate('DRAFT').subscribe({
          next: () => {
            this.autoSaveStatus = 'saved';
            this.autoSavedAt = new Date();
          },
          error: () => { this.autoSaveStatus = 'error'; },
        });
      });
  }

  triggerAutoSave(): void {
    if (!this.readonlyReview && !this.milestoneExpired) {
      this.autoSave$.next();
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  private findExistingReview(reviews: Review[], reviewId: string): Review | null {
    const allReviews = reviews ?? [];

    if (reviewId) {
      const byReviewId = allReviews.find(r =>
        r.reviewId === reviewId || (r as any).id === reviewId
      );

      if (byReviewId) {
        return byReviewId;
      }
    }

    const matches = allReviews.filter(r => {
      const reviewAny = r as any;

      return (
        r.cycleId === this.cycle?.cycleId &&
        reviewAny.milestoneId === this.milestoneId &&
        r.reviewType === this.reviewType &&
        (
          reviewAny.subjectUserId === this.userId ||
          reviewAny.userId === this.userId ||
          reviewAny.authorUserId === this.userId
        )
      );
    });

    if (matches.length === 0) {
      return null;
    }

    return matches.sort((a, b) => {
      const aTime = new Date((a as any).updatedAt ?? (a as any).createdAt ?? 0).getTime();
      const bTime = new Date((b as any).updatedAt ?? (b as any).createdAt ?? 0).getTime();

      return bTime - aTime;
    })[0];
  }

  private initialiseEmptyResponses(): void {
    for (const q of this.questions) {
      if (this.responses[q.questionId] === undefined) {
        this.responses[q.questionId] = this.isMultiSelectQuestion(q) ? [] : '';
      }
    }
  }

  toggleMulti(qId: string, option: string): void {
    if (this.readonlyReview) {
      return;
    }

    const arr: string[] = Array.isArray(this.responses[qId])
      ? [...(this.responses[qId] as string[])]
      : [];

    const idx = arr.indexOf(option);

    if (idx >= 0) {
      arr.splice(idx, 1);
    } else {
      arr.push(option);
    }

    this.responses[qId] = arr;
  }

  isChecked(qId: string, option: string): boolean {
    return Array.isArray(this.responses[qId]) && (this.responses[qId] as string[]).includes(option);
  }

  setRating(qId: string, value: number): void {
    if (this.readonlyReview) {
      return;
    }

    this.responses[qId] = value;
  }

  ratingValue(qId: string): number {
    const value = this.responses[qId];
    return typeof value === 'number' ? value : 0;
  }

  ratingArray(scale: number = 5): number[] {
    return Array.from({ length: scale || 5 }, (_, i) => i + 1);
  }

  isTextQuestion(q: Question): boolean {
    return this.normaliseType(q.type) === 'text';
  }

  isRatingQuestion(q: Question): boolean {
    return this.normaliseType(q.type) === 'rating';
  }

  isYesNoQuestion(q: Question): boolean {
    return this.normaliseType(q.type) === 'yes_no';
  }

  isMultiSelectQuestion(q: Question): boolean {
    const type = this.normaliseType(q.type);
    return type === 'multi_select' || type === 'checkbox';
  }

  isSingleSelectQuestion(q: Question): boolean {
    const type = this.normaliseType(q.type);
    return type === 'single_select' || type === 'radio';
  }

  private normaliseType(type: string | undefined): string {
    return (type ?? '').toLowerCase();
  }

  private hasMeaningfulAnswer(value: unknown): boolean {
    if (value === null || value === undefined) return false;
    if (typeof value === 'string') return value.trim().length > 0;
    if (Array.isArray(value)) return value.length > 0;
    return true;
  }

  private textWordCount(value: unknown): number {
    if (typeof value !== 'string') return 0;
    const trimmed = value.trim();
    if (!trimmed) return 0;
    return trimmed.split(/\s+/).length;
  }

  private meetsAiThreshold(value: unknown): boolean {
    return this.textWordCount(value) >= this.MIN_WORDS_FOR_AI;
  }

  private buildAnswers(): Answer[] {
    return this.questions.map(q => ({
      questionId: q.questionId,
      response: this.responses[q.questionId] ?? '',
    }));
  }

  private saveOrUpdate(targetStatus: 'DRAFT' | 'COMPLETED'): Observable<unknown> {
    if (!this.review) {
      throw new Error('Review is not initialized.');
    }

    this.review.status = targetStatus;
    this.review.answers = this.buildAnswers();

    const syncSaved = tap((saved: unknown) => {
      const s = saved as any;
      this.review = {
        ...this.review!,
        ...s,
        reviewId: s?.reviewId ?? s?.id ?? this.review!.reviewId,
        id: s?.id ?? (this.review as any).id ?? s?.reviewId,
      } as any;
    });

    const reviewIdentifier = this.review.reviewId ?? (this.review as any).id;

    if (reviewIdentifier) {
      return this.employeeService.updateReview({
        ...this.review,
        reviewId: reviewIdentifier,
      }).pipe(syncSaved);
    }

    // No reviewId yet — this is the very first save.
    // Backend create() always sets status=NOT_STARTED regardless of the payload,
    // so if the user submits directly (COMPLETED) without a prior autosave we must
    // follow up with an update to drive the status to the intended target.
    return this.employeeService.createReview(this.review).pipe(
      tap((created: unknown) => {
        const c = created as any;
        this.review = {
          ...this.review!,
          ...c,
          reviewId: c?.reviewId ?? c?.id,
          id: c?.id ?? c?.reviewId,
        } as any;
      }),
      switchMap(() => {
        const newId = this.review!.reviewId ?? (this.review as any).id;
        if (targetStatus === 'COMPLETED' && newId) {
          return this.employeeService.updateReview({
            ...this.review!,
            reviewId: newId,
          }).pipe(syncSaved);
        }
        return of(this.review);
      })
    );
  }

  save(status: 'DRAFT' | 'COMPLETED'): void {
    if (!this.review) return;

    if (status === 'COMPLETED') {
      const missing = this.questions
        .map((q, i) => ({ q, i }))
        .filter(({ q }) => {
          if (!q.required) return false;
          const v = this.responses[q.questionId];
          // Required text answers are blocked only when empty or obvious junk
          // ("N/A", a single character). Length beyond that is advisory, not a
          // hard gate. Non-text required answers just need a value.
          if (this.isTextQuestion(q)) return this.isMeaninglessText(v);
          return v === null || v === undefined ||
            (typeof v === 'string' && v.trim() === '') ||
            (Array.isArray(v) && v.length === 0);
        });

      if (missing.length > 0) {
        this.validationErrors = missing.map(({ q, i }) => ({
          index: i + 1,
          text: q.text,
          id: q.questionId,
        }));
        this.missingQuestionIds = new Set(missing.map(({ q }) => q.questionId));
        // Scroll all the way to the top so the validation banner is at the top of the screen
        setTimeout(() => {
          window.scrollTo({ top: 0, behavior: 'smooth' });
          document.querySelectorAll<HTMLElement>('.main-content, .content-area, .page-container, .review-page').forEach(el => {
            try { el.scrollTo({ top: 0, behavior: 'smooth' }); } catch { el.scrollTop = 0; }
          });
          document.documentElement.scrollTop = 0;
          document.body.scrollTop = 0;
        }, 50);
        return;
      }
    }

    this.validationErrors = [];
    this.missingQuestionIds = new Set();
    this.errorMsg = '';
    this.saving = true;

    this.saveOrUpdate(status).pipe(
      switchMap(() => {
        if (status !== 'COMPLETED') return of(null);
        return this.startInsightsAndPersistJobId();
      })
    ).subscribe({
      next: () => {
        this.saving = false;
        this.router.navigate(['/my-review']);
      },
      error: () => {
        this.saving = false;
        this.errorMsg = 'Failed to save. Please try again.';
      },
    });
  }

  private startInsightsAndPersistJobId(): Observable<unknown> {
    if (!this.review || !this.userSeniority || !this.userDiscipline) {
      return of(null);
    }

    const reviewIdentifier = this.review.reviewId ?? (this.review as any).id;
    if (!reviewIdentifier) {
      return of(null);
    }

    const pairs = this.buildQuestionPairs();
    if (pairs.length === 0) {
      return of(null);
    }

    return this.aiReviewService.startInsights(pairs, this.userSeniority, this.userDiscipline).pipe(
      // Minimal job-pointer body — see EmployeeService.updateReview: echoing the
      // full review (esp. status=PUBLISHED) back would 400 the PUT.
      switchMap(job => this.employeeService.updateReview({
        reviewId: reviewIdentifier,
        version: this.review!.version,
        insightsJobId: job.jobId
      })),
      tap((saved: unknown) => {
        const s = saved as any;
        this.review = {
          ...this.review!,
          ...s,
          insightsJobId: s?.insightsJobId ?? this.review!.insightsJobId
        } as any;
      }),
      // Insights persistence is best-effort; review submission remains successful.
      catchError(() => of(null))
    );
  }

  // ── Review Suggestions ─────────────────────────────────────────

  runAiAnalysis(): void {
    if (this.aiLoading) {
      return;
    }

    const pairs = this.buildQuestionPairs();
    if (pairs.length === 0) {
      this.aiError = `Please write at least ${this.MIN_WORDS_FOR_AI} words in a required text answer before getting suggestions.`;
      return;
    }
    if (!this.userSeniority || !this.userDiscipline) {
      this.aiError = 'Ask an admin to set your seniority and discipline in Employee Management before using suggestions.';
      return;
    }

    const signature = this.currentAiSignature;
    if (signature === this.lastAnalyzedSignature) {
      return;
    }

    this.aiLoading = true;
    this.aiError = '';
    this.aiResult = null;

    this.aiReviewService.startAnalysis(pairs, this.userSeniority, this.userDiscipline).pipe(
      switchMap(job => this.persistAnalyzeJobId(job.jobId).pipe(
        switchMap(() => this.aiReviewService.pollAnalysis(job.jobId))
      )),
      // Stop polling (and stop writing to this component) once it's destroyed —
      // pollAnalysis races a 300s timeout, so without this a mid-job navigation
      // leaves the 1s poll firing for up to 5 minutes. Mirrors the manager form.
      takeUntil(this.destroy$)
    ).subscribe({
      next: (result) => {
        this.aiResult = result;
        this.lastAnalyzedSignature = signature;
        this.snapshotAnalyzedAnswers(pairs);
        this.aiLoading = false;
      },
      error: () => {
        this.aiError = 'Suggestions are temporarily unavailable. Please try again later.';
        this.aiLoading = false;
      }
    });
  }

  private hydrateAiFromExisting(existing: Review): void {
    const jobId = existing.analyzeJobId;
    if (!jobId) return;
    this.aiLoading = true;
    // hydrate runs on every load of an already-analyzed review, so this poll
    // must tear down on destroy too — otherwise it leaks on routine navigation.
    this.aiReviewService.pollAnalysis(jobId).pipe(takeUntil(this.destroy$)).subscribe({
      next: (result) => {
        this.aiResult = result;
        // Pin the signature so the "Get Suggestions" button stays disabled
        // for the rehydrated answers — without this, the user could re-fire
        // Gemini for free immediately after a refresh.
        this.lastAnalyzedSignature = this.currentAiSignature;
        this.snapshotAnalyzedAnswers(this.buildQuestionPairs());
        this.aiLoading = false;
      },
      error: () => {
        // Stale or expired jobId (24h TTL on AiJobs) — silently fall back.
        this.aiResult = null;
        this.aiLoading = false;
      }
    });
  }

  private persistAnalyzeJobId(jobId: string): Observable<unknown> {
    if (!this.review) return of(null);
    const reviewIdentifier = this.review.reviewId ?? (this.review as any).id;
    if (!reviewIdentifier) {
      // Unsaved draft — keep pointer locally so this session can still refresh and resume.
      this.review = { ...this.review, analyzeJobId: jobId } as Review;
      return of(null);
    }
    return this.employeeService.updateReview({
      reviewId: reviewIdentifier,
      version: this.review.version,
      analyzeJobId: jobId
    }).pipe(
      tap((saved: unknown) => {
        const s = saved as any;
        this.review = {
          ...this.review!,
          ...s,
          analyzeJobId: s?.analyzeJobId ?? jobId
        } as Review;
      }),
      // Persistence is best-effort — keep local pointer so this session still benefits.
      catchError(() => {
        this.review = { ...this.review!, analyzeJobId: jobId } as Review;
        return of(null);
      })
    );
  }

  private buildQuestionPairs(): { questionId: string; questionText: string; answer: string }[] {
    return this.questions
      .filter(q => {
        if (!this.isTextQuestion(q)) return false;
        const v = this.responses[q.questionId];
        // Required text answers must clear the AI threshold so a half-typed
        // sentence doesn't trigger low-quality suggestions on a question the
        // user is committing to. Optional text answers ride along whenever
        // they have any content — short by definition, but worth analyzing
        // if the user bothered to write something.
        if (q.required) return this.meetsAiThreshold(v);
        return typeof v === 'string' && v.trim().length > 0;
      })
      .map(q => ({
        questionId: q.questionId,
        questionText: q.text,
        answer: this.responses[q.questionId] as string,
      }));
  }

  get questionFeedback(): AiReviewQuestionFeedback[] {
    return (this.aiResult?.suggestions ?? []).map(item => ({
      questionId: item.questionId,
      questionText: item.questionText,
      verdict: item.verdict,
      suggestions: (item.suggestions ?? []).filter(s => s.trim().length > 0),
    }));
  }

  getQuestionFeedback(question: Question): AiReviewQuestionFeedback | null {
    // Strict questionId match only. The previous fallback (positional lookup
    // into the text-only question list) would attach Q4's slot in the AI
    // response array to whatever question happened to sit at index 4 — when
    // the AI skipped a question, every later question rendered the WRONG
    // feedback. Match by id or nothing.
    const feedback = this.questionFeedback.find(item => item.questionId === question.questionId) ?? null;
    if (!feedback) return null;
    // Hide feedback once the answer it was generated for has been edited or
    // cleared — otherwise an emptied box still shows a stale "Looking strong".
    const current = this.normalizeTextAnswer(String(this.responses[question.questionId] ?? ''));
    return current === this.analyzedAnswers.get(question.questionId) ? feedback : null;
  }

  /** Records the exact answer text each question's feedback was generated for,
   *  so {@link getQuestionFeedback} can drop it the moment the answer changes. */
  private snapshotAnalyzedAnswers(pairs: { questionId: string; answer: string }[]): void {
    this.analyzedAnswers = new Map(
      pairs.map(p => [p.questionId, this.normalizeTextAnswer(p.answer)])
    );
  }

  get totalAiItems(): number {
    return this.questionFeedback
      .filter(item => item.verdict !== 'STRONG')
      .reduce((total, item) => total + item.suggestions.length, 0);
  }

  get aiButtonDisabled(): boolean {
    return this.saving || this.aiLoading || !this.hasAnalyzableAnswers || this.isCurrentAnalysisFresh;
  }

  get isCurrentAnalysisFresh(): boolean {
    const signature = this.currentAiSignature;
    return !!signature && signature === this.lastAnalyzedSignature;
  }

  get showUpdateAnswerHint(): boolean {
    return !this.aiLoading && this.isCurrentAnalysisFresh;
  }

  get aiButtonTooltip(): string {
    if (this.showUpdateAnswerHint) return 'Update an answer to re-run suggestions';
    if (!this.hasAnalyzableAnswers) {
      return `Write at least ${this.MIN_WORDS_FOR_AI} words in a required text answer to enable suggestions`;
    }
    return 'Get writing suggestions';
  }

  get railVisible(): boolean {
    return this.aiLoading || !!this.aiError || (!!this.aiResult && this.totalAiItems === 0);
  }

  private get hasAnalyzableAnswers(): boolean {
    return this.currentAiSignature.length > 0;
  }

  private get currentAiSignature(): string {
    return this.buildQuestionPairs()
      .map(pair => `${pair.questionId}:${this.normalizeTextAnswer(pair.answer)}`)
      .sort()
      .join('|');
  }

  private normalizeTextAnswer(answer: string): string {
    return answer.replace(/\s+/g, ' ').trim();
  }

  exportToPdf(): void {
    const cycleStr = (this.cycle?.cycleTitle ?? 'Review').replace(/\s+/g, '_').replace(/[^a-zA-Z0-9_]/g, '');
    const label = this.reviewTypeLabel;
    const filename = `${label.replace(/\s+/g, '_')}_${cycleStr}`;

    const existing = document.getElementById('__print_iframe__');
    if (existing) existing.remove();

    const iframe = document.createElement('iframe');
    iframe.id = '__print_iframe__';
    iframe.style.cssText = 'position:fixed;width:0;height:0;border:none;left:-9999px;top:-9999px;';
    iframe.setAttribute('sandbox', 'allow-same-origin allow-modals');
    document.body.appendChild(iframe);

    const win = iframe.contentWindow;
    if (!win) return;

    const originalTitle = document.title;
    document.title = filename;

    const qaRows = this.questions.map((q, i) => {
      const ans = this.responses[q.questionId];
      const ansText = Array.isArray(ans) ? (ans as string[]).join(', ') : String(ans ?? '');
      return `
        <div class="qa-item">
          <div class="qa-q"><span class="qa-num">Q${i + 1}</span>${escapeHtml(q.text)}</div>
          <div class="qa-a ${!ansText.trim() ? 'empty' : ''}">${ansText.trim() ? escapeHtml(ansText.trim()) : 'No answer provided'}</div>
        </div>`;
    }).join('');

    win.document.write(`
      <!DOCTYPE html><html><head>
      <meta charset="utf-8"><title>${escapeHtml(filename)}</title>
      <style>
        @page { size: A4 portrait; margin: 16mm 18mm; }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
               color: #2E2E2E; background: #fff; padding: 32px; }
        .doc-header {
          background: linear-gradient(135deg, #4A90E2 0%, #2465AA 100%);
          color: #fff; padding: 20px 24px; border-radius: 10px; margin-bottom: 20px;
          -webkit-print-color-adjust: exact; print-color-adjust: exact;
        }
        .doc-badge   { font-size: 0.65rem; font-weight: 700; letter-spacing: 0.1em;
                       text-transform: uppercase; opacity: 0.8; margin-bottom: 6px; }
        .doc-title   { font-size: 1.3rem; font-weight: 700; margin-bottom: 6px; }
        .doc-meta    { font-size: 0.8rem; opacity: 0.85; }
        .doc-block   { border: 1px solid #DEDEDE; border-radius: 10px; overflow: hidden; }
        .block-title { font-size: 0.72rem; font-weight: 700; text-transform: uppercase;
                       letter-spacing: 0.06em; color: #626262; padding: 12px 18px;
                       background: #F4F4F4; border-bottom: 1px solid #DEDEDE; }
        .qa-item     { padding: 12px 18px; border-bottom: 1px solid #F4F4F4; }
        .qa-item:last-child { border-bottom: none; }
        .qa-q        { display: flex; gap: 8px; font-size: 0.8rem; font-weight: 600;
                       color: #626262; margin-bottom: 5px; }
        .qa-num      { display: inline-flex; align-items: center; justify-content: center;
                       min-width: 20px; height: 20px; border-radius: 50%;
                       background: #EFF5FC; color: #4A90E2; font-size: 0.68rem;
                       font-weight: 700; flex-shrink: 0; }
        .qa-a        { font-size: 0.88rem; line-height: 1.7; white-space: pre-wrap;
                       padding-left: 28px; color: #2E2E2E; }
        .qa-a.empty  { color: #94a3b8; font-style: italic; }
        @media print {
          body { padding: 0; }
          .doc-header {
            background: linear-gradient(135deg, #4A90E2 0%, #2465AA 100%) !important;
            -webkit-print-color-adjust: exact; print-color-adjust: exact;
            padding: 14px 18px; border-radius: 8px; margin-bottom: 12px;
          }
          .doc-title { font-size: 14pt; }
          .doc-meta  { font-size: 8pt; }
          .qa-item   { padding: 6px 14px; }
          .qa-q      { font-size: 8pt; }
          .qa-a      { font-size: 9pt; line-height: 1.5; }
        }
      </style></head><body>
        <div class="doc-header">
          <div class="doc-badge">${escapeHtml(label)}</div>
          <div class="doc-title">${escapeHtml(this.cycle?.cycleTitle ?? 'Performance Review')}</div>
          <div class="doc-meta">Submitted · ${escapeHtml(this.submittedDateLabel || new Date().toLocaleDateString('en-AU', { day: 'numeric', month: 'long', year: 'numeric' }))}</div>
        </div>
        <div class="doc-block">
          <div class="block-title">Responses</div>
          ${qaRows}
        </div>
      </body></html>
    `);
    win.document.close();
    win.onafterprint = () => { iframe.remove(); document.title = originalTitle; };
    setTimeout(() => { win.focus(); win.print(); }, 300);
  }

  private isMilestoneExpired(endDate?: string): boolean {
    if (!endDate) return false;
    const parts = endDate.split('/');
    const date = parts.length === 3
      ? new Date(`${parts[2]}-${parts[1].padStart(2, '0')}-${parts[0].padStart(2, '0')}`)
      : new Date(endDate);
    return !isNaN(date.getTime()) && date < new Date();
  }

  scrollToQuestion(questionId: string): void {
    document.getElementById('q-' + questionId)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }

  clearMissing(questionId: string): void {
    if (this.missingQuestionIds.size > 0) {
      this.validationErrors = [];
      this.missingQuestionIds = new Set();
    }
  }

  get autoSavedAgo(): string {
    if (!this.autoSavedAt) return '';
    const secs = Math.floor((Date.now() - this.autoSavedAt.getTime()) / 1000);
    if (secs < 10) return 'just now';
    if (secs < 60) return `${secs}s ago`;
    return `${Math.floor(secs / 60)}m ago`;
  }

  cancel(): void {
    this.router.navigate(['/my-review']);
  }

  /** Tri-state status used by the sidebar dot + card border. */
  answerState(q: Question): 'empty' | 'in-progress' | 'answered' {
    const v = this.responses[q.questionId];
    if (v === null || v === undefined) return 'empty';

    if (typeof v === 'string') {
      const trimmed = v.trim();
      if (!trimmed) return 'empty';
      // Text answers count as "answered" only once they clear the AI word
      // threshold. Anything shorter is in-progress (orange dot) so the
      // sidebar doesn't claim a "test test test" response is done. Applies
      // to both required and optional text questions — the visual nudge to
      // write more helps reviewers either way, while the AI itself still
      // accepts any content for optional questions (see buildQuestionPairs).
      if (this.isTextQuestion(q)) {
        return this.meetsAiThreshold(v) ? 'answered' : 'in-progress';
      }
      return 'answered';
    }

    if (Array.isArray(v)) return v.length > 0 ? 'answered' : 'empty';
    return 'answered';
  }

  /**
   * True when a text question has SOME content but hasn't reached
   * MIN_WORDS_FOR_AI yet. Drives the inline "Write at least N words"
   * hint shown beneath the textarea. Empty answers don't get the hint —
   * the user hasn't started yet, no point nagging them mid-keystroke.
   */
  belowAiThreshold(q: Question): boolean {
    if (!this.isTextQuestion(q)) return false;
    const v = this.responses[q.questionId];
    if (typeof v !== 'string') return false;
    const trimmed = v.trim();
    if (!trimmed) return false;
    return !this.meetsAiThreshold(v);
  }

  /** Live word count for the inline hint UI. */
  wordsTyped(q: Question): number {
    return this.textWordCount(this.responses[q.questionId]);
  }

  /** Empty or obvious junk ("N/A", a single character). Used to hard-block
   *  submission of required text answers — nothing stricter. */
  private isMeaninglessText(value: unknown): boolean {
    if (typeof value !== 'string') return true;
    const trimmed = value.trim();
    if (trimmed.length < 2) return true; // empty or single character
    const normalized = trimmed.toLowerCase().replace(/[^a-z0-9]/g, '');
    return ['na', 'none', 'nil', 'tbd', 'nothing'].includes(normalized);
  }

  /** A required text question that was flagged at submit but isn't empty —
   *  i.e. rejected as junk. Drives the inline "write a real answer" message so
   *  the red highlight on a non-empty box isn't a mystery. */
  isFlaggedJunk(q: Question): boolean {
    return this.missingQuestionIds.has(q.questionId) &&
      this.isTextQuestion(q) &&
      this.hasMeaningfulAnswer(this.responses[q.questionId]);
  }

  /** Exposed so the template can show the threshold number in the hint. */
  get minWordsForAi(): number {
    return this.MIN_WORDS_FOR_AI;
  }

  isAnswered(q: Question): boolean {
    return this.answerState(q) === 'answered';
  }

  isInProgress(q: Question): boolean {
    return this.answerState(q) === 'in-progress';
  }

  get answeredCount(): number {
    return this.questions.filter(q => this.isAnswered(q)).length;
  }

  get progressPct(): number {
    if (!this.questions.length) return 0;
    return Math.round((this.answeredCount / this.questions.length) * 100);
  }

  private isSubmittedStatus(status: any): boolean {
    const value = String(status ?? '').toUpperCase();
    return value === 'COMPLETED' || value === 'COMPLETE' || value === 'SUBMITTED' || value === 'PUBLISHED';
  }

  titleFor(): string {
    if (this.reviewType === 'MANAGER') return 'Manager Review';
    if (this.reviewType === 'PEER_360') {
      return this.peerSubjectName ? `Peer 360 Review — ${this.peerSubjectName}` : 'Peer 360 Review';
    }
    return 'Self Review';
  }

  /* ── Read-only "view" presentation helpers ───────────────────────
   * When a review is submitted/published the employee is reading it, not
   * writing it — these back a clean Q&A document instead of the (disabled)
   * authoring form. */

  /** Short label for the review type, used in the doc header + PDF. */
  get reviewTypeLabel(): string {
    if (this.reviewType === 'MANAGER') return 'Manager Review';
    if (this.reviewType === 'PEER_360') return 'Peer 360 Review';
    return 'Self Review';
  }

  /** Human status for the doc header badge. */
  get statusLabel(): string {
    const value = String(this.review?.status ?? '').toUpperCase();
    if (value === 'PUBLISHED') return 'Published';
    if (value === 'COMPLETED' || value === 'COMPLETE' || value === 'SUBMITTED') return 'Completed';
    return 'Submitted';
  }

  /** Submitted date for the doc header, falling back through the timestamps. */
  get submittedDateLabel(): string {
    const raw = (this.review as any)?.submittedAt
      ?? (this.review as any)?.updatedAt
      ?? (this.review as any)?.createdAt;
    if (!raw) return '';
    const date = new Date(raw);
    if (Number.isNaN(date.getTime())) return '';
    return date.toLocaleDateString('en-AU', { day: 'numeric', month: 'long', year: 'numeric' });
  }

  /** True when the stored answer for a question is blank. */
  isAnswerEmpty(q: Question): boolean {
    const v = this.responses[q.questionId];
    if (v === null || v === undefined) return true;
    if (typeof v === 'string') return v.trim().length === 0;
    if (Array.isArray(v)) return v.length === 0;
    return false;
  }

  /** Plain-text answer for the read-only text rendering. */
  textAnswer(q: Question): string {
    const v = this.responses[q.questionId];
    return typeof v === 'string' ? v : String(v ?? '');
  }

  /** Chosen options for multi/single-select questions, as chips. */
  selectedOptions(q: Question): string[] {
    const v = this.responses[q.questionId];
    if (Array.isArray(v)) return v as string[];
    if (typeof v === 'string' && v.trim()) return [v];
    return [];
  }

  /** Normalised Yes/No answer for read-only rendering. */
  yesNoAnswer(q: Question): string {
    const v = String(this.responses[q.questionId] ?? '').toLowerCase();
    if (v === 'yes') return 'Yes';
    if (v === 'no') return 'No';
    return '';
  }

  private milestoneMatchesType(m: any, type: ReviewType): boolean {
    const snapType = m?.templateSnapshot?.reviewType;
    if (snapType === type) return true;
    const name = String(m?.name ?? '').toLowerCase();
    if (type === 'PEER_360') return name.includes('peer') || name.includes('360');
    if (type === 'MANAGER') return name.includes('manager');
    return name.includes('self');
  }
}
