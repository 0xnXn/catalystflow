import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, of, race, shareReplay, throwError, timer } from 'rxjs';
import { catchError, filter, map, mergeMap, switchMap, takeWhile } from 'rxjs/operators';
import { environment } from '../../environment/environment';

export type AiReviewVerdict = 'STRONG' | 'NEEDS_IMPROVEMENT';

export interface AiReviewQuestionFeedback {
  questionId: string;
  questionText: string;
  verdict: AiReviewVerdict;
  suggestions: string[];
}

export interface AiReviewResponse {
  suggestions: AiReviewQuestionFeedback[];
}

export type AiReviewJobStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';

export interface AiReviewJobResponse {
  jobId: string;
  status: AiReviewJobStatus;
}

/**
 * Generic streaming-aware status envelope for the manager-jobs poll endpoint
 * (`/ai-review/manager/jobs/{jobId}/status`). Emits both intermediate states
 * (PENDING/PROCESSING with partialText) AND the terminal COMPLETED payload —
 * callers subscribe and switch on `status`. Mirror of
 * AiReviewInsightsStatusResponse for the GOLDEN + DIGEST flows.
 */
export interface ManagerJobStatusResponse<T> {
  status: AiReviewJobStatus;
  result?: T;
  partialText?: string;
  errorMessage?: string;
}

export interface AiReviewStatusResponse {
  jobId: string;
  status: AiReviewJobStatus;
  result?: AiReviewResponse;
  errorMessage?: string;
}

export interface AiReviewInsightsJobResponse {
  jobId: string;
  status: AiReviewJobStatus;
}

export interface AiReviewInsightsResponse {
  summary?: string;
  goldenMirror?: string;
  /** Optional citation map referenced inline in goldenMirror via `[cN]` tokens.
   *  For INSIGHTS, source is always 'self' — only the employee's answers are
   *  visible to this job. Frontend renders the chips with the same component
   *  as the manager-side golden. */
  citations?: Record<string, GoldenCitation>;
}

export interface AiReviewInsightsStatusResponse {
  jobId: string;
  status: AiReviewJobStatus;
  result?: AiReviewInsightsResponse;
  errorMessage?: string;
  /**
   * Cumulative narrative text streamed from Gemini while status=PROCESSING.
   * The backend worker writes this every ~500ms / 50 tokens during generation,
   * so successive polls return a growing snapshot. Use this for a live preview
   * during streaming; switch to `result` once status=COMPLETED (it carries the
   * structured citation map). Absent on FAILED rows (cleared by the worker).
   */
  partialText?: string;
}

export interface DisciplineSeniorityLevel {
  key: string;
  displayName: string;
}

export interface DisciplineInfo {
  discipline: string;
  levels: DisciplineSeniorityLevel[];
}

// ── Manager guidance models ───────────────────────────────────────────────────
// Re-exported from review.model.ts so consumers have a single import surface
// (the model file is the source of truth for shapes that round-trip to the
// backend; this service is the one place that knows about endpoints).
export type { ManagerGoldenReview, GoldenCitation } from '../models/review.model';
import type { ManagerGoldenReview, GoldenCitation } from '../models/review.model';

// Sync-flow shapes (`ManagerAnalyzeResult`, `ManagerGoldenResult`) removed in G3.
// Async flow returns AiReviewJobResponse from start*; pollManager* yields the
// inner shape (ManagerGoldenReview) directly.

export interface QuestionSuggestRequest {
  questionId: string;
  questionText: string;
  managerCurrentAnswer: string;
  discipline: string;
  seniority: string;
  employeeReview: {
    answers: Array<{ questionId: string; questionText?: string; response: string }>;
    overallComment?: string;
    overallRating?: string;
  };
  peerDigest?: {
    themes?: string[];
    consensus?: string[];
    dissent?: string[];
    overallSentiment?: string;
  };
}

export interface SuggestionCitation {
  source: 'self' | 'peer_360' | 'peer_digest';
  reviewId?: string;
  questionId?: string;
  quote: string;
  section?: string;
  authorUserId?: string;
}

export interface QuestionSuggestResponse {
  isGoodAnswer?: boolean;
  suggestion: string;
  citations?: Record<string, SuggestionCitation>;
}

// ── Peer 360 digest (ADR-2) ────────────────────────────────────────────────
// AI-clustered summary of every completed PEER_360 review for a single
// (cycleId, subjectUserId) pair. Surfaced to the manager only.
export type Peer360Sentiment = 'positive' | 'mixed' | 'constructive' | 'concerning';

export interface Peer360Theme {
  theme: string;
  summary?: string;
}

export interface Peer360Point {
  point: string;
  reviewIds?: string[];
}

export interface Peer360Digest {
  themes: Peer360Theme[];
  consensus: Peer360Point[];
  dissent: Peer360Point[];
  overallSentiment: Peer360Sentiment;
}

@Injectable({
  providedIn: 'root'
})
export class AiReviewService {
  /**
   * Cache for getDisciplines() — first call hits /ai-review/disciplines
   * (which pays the AI Lambda cold-start once per session), every call
   * after replays the cached result. Lives for the SPA's lifetime via
   * providedIn: 'root', so opening the create-user modal repeatedly
   * inside the same tab session is free.
   */
  private disciplines$?: Observable<DisciplineInfo[]>;

  constructor(private http: HttpClient) {}

  analyzeReview(
    questions: { questionId: string; questionText: string; answer: string }[],
    seniority: string,
    discipline: string
  ): Observable<AiReviewResponse> {
    return this.startAnalysis(questions, seniority, discipline).pipe(
      switchMap(job => this.pollAnalysis(job.jobId))
    );
  }

  startAnalysis(
    questions: { questionId: string; questionText: string; answer: string }[],
    seniority: string,
    discipline: string
  ): Observable<AiReviewJobResponse> {
    return this.http.post<AiReviewJobResponse>(environment.apiUrl + '/ai-review/analyze', {
      questions,
      seniority,
      discipline
    });
  }

  /**
   * Status GET that converts a 404 into a clear, terminal "stale job" error.
   *
   * AiJobs rows are TTL-bounded (~30d on create, extended on completion) but the
   * insightsJobId / managerGoldenJobId / peer360DigestJobId pointers persisted on
   * the review row are permanent. Opening an old review therefore polls a jobId
   * whose row has been reaped → the backend returns 404. Callers already route
   * errors into their failed→Retry path; mapping 404 to a labeled error gives a
   * recognizable message (and a clear "regenerate" cue) instead of surfacing a
   * raw HttpErrorResponse that looks like a Gemini failure. Non-404 errors pass
   * through untouched.
   */
  private statusGet<T>(url: string, label: string): Observable<T> {
    return this.http.get<T>(url).pipe(
      catchError((err: HttpErrorResponse) =>
        err.status === 404
          ? throwError(() => new Error(`${label} is no longer available — click Retry to regenerate.`))
          : throwError(() => err)),
    );
  }

  getAnalysisStatus(jobId: string): Observable<AiReviewStatusResponse> {
    return this.statusGet<AiReviewStatusResponse>(
      `${environment.apiUrl}/ai-review/analyze/${jobId}/status`, 'AI suggestions');
  }

  startInsights(
    questions: { questionId: string; questionText: string; answer: string }[],
    seniority: string,
    discipline: string
  ): Observable<AiReviewInsightsJobResponse> {
    return this.http.post<AiReviewInsightsJobResponse>(environment.apiUrl + '/ai-review/insights', {
      questions,
      seniority,
      discipline
    });
  }

  getInsightsStatus(jobId: string): Observable<AiReviewInsightsStatusResponse> {
    return this.statusGet<AiReviewInsightsStatusResponse>(
      `${environment.apiUrl}/ai-review/insights/${jobId}/status`, 'AI insights');
  }

  /**
   * Polls /ai-review/insights/{jobId}/status until the job is COMPLETED or
   * FAILED, then emits the result (or errors). Mirrors {@link pollAnalysis}
   * for the insights endpoint — used when a manager opens the review form
   * and the employee's insights job is still mid-flight.
   *
   * COMPLETED with a null result is treated as a failure (code-review N7,
   * 2026-05-11). Previously the helper emitted null which the manager UI
   * rendered as "no insights available" — visually indistinguishable from a
   * real success with empty content. Surfacing as an error routes the manager
   * into the ADR-5 fallback (with Retry button) where they belong.
   *
   * <p><b>Streaming preview</b>: now emits the full
   * {@link AiReviewInsightsStatusResponse} on every poll (not just the final
   * result). During PROCESSING, callers can render {@code status.partialText}
   * as a growing snapshot — the backend worker streams Gemini's output to
   * AiJobs.partialText so each successive poll returns more text. On COMPLETED,
   * callers switch to {@code status.result} (structured + citation chips).
   */
  pollInsights(jobId: string): Observable<AiReviewInsightsStatusResponse> {
    const poll$ = timer(0, 1000).pipe(
      switchMap(() => this.getInsightsStatus(jobId)),
      takeWhile(s => s.status === 'PENDING' || s.status === 'PROCESSING', true),
      mergeMap(s => {
        if (s.status === 'COMPLETED') {
          if (s.result) return [s];           // emit full status (incl. result) on success
          return throwError(() => new Error('AI insights completed but returned no result'));
        }
        if (s.status === 'FAILED') {
          return throwError(() => new Error(s.errorMessage ?? 'AI insights failed'));
        }
        // PENDING / PROCESSING — emit the status snapshot so subscribers can
        // read partialText. The takeWhile above lets these flow through
        // (inclusive=true), so the final terminal emission still fires once.
        return [s];
      })
    );

    const timeout$ = timer(300000).pipe(
      mergeMap(() => throwError(() => new Error('AI insights timed out')))
    );

    return race(poll$, timeout$);
  }

  pollAnalysis(jobId: string): Observable<AiReviewResponse> {
    const poll$ = timer(0, 1000).pipe(
      switchMap(() => this.getAnalysisStatus(jobId)),
      takeWhile(status => status.status === 'PENDING' || status.status === 'PROCESSING', true),
      mergeMap(status => {
        if (status.status === 'COMPLETED') {
          if (status.result) return [status.result];
          return throwError(() => new Error('AI suggestions completed but returned no result'));
        }
        if (status.status === 'FAILED') {
          return throwError(() => new Error(status.errorMessage ?? 'AI suggestions failed'));
        }
        return [];
      })
    );

    const timeout$ = timer(300000).pipe(
      mergeMap(() => throwError(() => new Error('AI suggestions timed out')))
    );

    return race(poll$, timeout$);
  }

  /**
   * Returns the discipline + seniority catalog used by the create-user
   * dropdown. Hits the backend on first call (paying any AI Lambda
   * cold-start) and replays the cached response for every subsequent
   * call in the same SPA session — so reopening the modal is free.
   */
  getDisciplines(): Observable<DisciplineInfo[]> {
    if (!this.disciplines$) {
      this.disciplines$ = this.http.get<DisciplineInfo[]>(environment.apiUrl + '/ai-review/disciplines').pipe(
        shareReplay({ bufferSize: 1, refCount: false }),
      );
    }
    return this.disciplines$;
  }

  // ── Manager guidance endpoints (G3 — async via SQS) ─────────────────────────
  //
  // POSTs return 202 + jobId. Poll /ai-review/analyze/{jobId}/status (the same
  // endpoint /analyze and /insights use) until the row is COMPLETED. The
  // status response's `result` field shape depends on which start* method
  // was called — the poll helpers below cast for you.

  /** Enqueues a manager-golden job. */
  startManagerGolden(reviewId: string): Observable<AiReviewJobResponse> {
    return this.http.post<AiReviewJobResponse>(
      `${environment.apiUrl}/ai-review/manager/reviews/${reviewId}/golden`, {});
  }

  /** Enqueues a per-question manager suggestion job. */
  startManagerQuestionSuggest(reviewId: string,
                              req: QuestionSuggestRequest): Observable<AiReviewJobResponse> {
    return this.http.post<AiReviewJobResponse>(
      `${environment.apiUrl}/ai-review/manager/reviews/${reviewId}/question-suggest`, req);
  }

  /**
   * Polls until terminal. Emits intermediate PENDING/PROCESSING envelopes
   * carrying partialText (for streaming preview) AND the final COMPLETED
   * envelope with `result`. Throws on FAILED / timeout. Signature changed
   * 2026-05-28 from `Observable<ManagerGoldenReview>` to envelope-shaped to
   * support the manager-side AI Review tab live-streaming.
   */
  pollManagerGolden(jobId: string): Observable<ManagerJobStatusResponse<ManagerGoldenReview>> {
    return this.pollUntilTerminal<ManagerGoldenReview>(jobId, 'manager golden');
  }

  /**
   * One-shot fetch of a manager-golden job's status + result (no polling).
   * For read-only views (e.g. Cycle Summary) opening an already-finished golden.
   */
  getManagerGolden(jobId: string): Observable<ManagerJobStatusResponse<ManagerGoldenReview>> {
    return this.statusGet<ManagerJobStatusResponse<ManagerGoldenReview>>(
      `${environment.apiUrl}/ai-review/manager/jobs/${jobId}/status`, 'manager golden');
  }

  /**
   * Persist a manager-edited AI Narrative draft. PUTs the edited
   * {@link ManagerGoldenReview} onto the AiJobs row's resultJson. Server
   * validates shape + that the row is COMPLETED; rejects with 400/409
   * otherwise. Returns the canonical server-side payload so the caller
   * can re-render from a single source of truth.
   */
  updateManagerGoldenResult(jobId: string,
                            golden: ManagerGoldenReview): Observable<ManagerGoldenReview> {
    return this.http.put<ManagerGoldenReview>(
      `${environment.apiUrl}/ai-review/manager/jobs/${jobId}/result`, golden);
  }

  /**
   * Polls until COMPLETED, then casts the opaque result to QuestionSuggestResponse.
   * QSUGG output is too small to stream meaningfully (~150 tokens) — kept on the
   * simple `Observable<T>` shape so caller pipes don't have to filter envelopes.
   */
  pollManagerQuestionSuggest(jobId: string): Observable<QuestionSuggestResponse> {
    return this.pollUntilTerminal<QuestionSuggestResponse>(jobId, 'manager question-suggest').pipe(
      filter(s => s.status === 'COMPLETED'),
      map(s => s.result!),
    );
  }

  /**
   * Enqueues a peer-360 digest job for the (cycleId, subjectUserId) pair.
   * Returns 202 + jobId; poll with pollPeer360Digest. ADR-2.
   */
  startPeer360Digest(cycleId: string, subjectUserId: string): Observable<AiReviewJobResponse> {
    return this.http.post<AiReviewJobResponse>(
      `${environment.apiUrl}/ai-review/manager/cycles/${encodeURIComponent(cycleId)}` +
      `/subjects/${encodeURIComponent(subjectUserId)}/peer360-digest`, {});
  }

  /**
   * Polls until terminal — same envelope shape as pollManagerGolden so the
   * manager-side 360 Digest tab can render partialText live. See
   * pollManagerGolden doc for the signature change rationale.
   */
  pollPeer360Digest(jobId: string): Observable<ManagerJobStatusResponse<Peer360Digest>> {
    return this.pollUntilTerminal<Peer360Digest>(jobId, 'peer-360 digest');
  }

  /** One-shot fetch of a peer-360 digest job's status + result (no polling). */
  getPeer360Digest(jobId: string): Observable<ManagerJobStatusResponse<Peer360Digest>> {
    return this.statusGet<ManagerJobStatusResponse<Peer360Digest>>(
      `${environment.apiUrl}/ai-review/manager/jobs/${jobId}/status`, 'peer-360 digest');
  }

  /**
   * Resolve the latest COMPLETED peer-360 digest jobId for a (cycleId,
   * subjectUserId), or null. Lets the UI reuse a finished digest on refresh —
   * the digest is a (subject, cycle) artifact, resolved server-side via the
   * Peer360DigestIndex GSI rather than pinned to a review row. 204 → null;
   * errors → null (fall back to the manual Generate button).
   */
  getLatestPeer360Digest(cycleId: string, subjectUserId: string): Observable<{ jobId: string } | null> {
    return this.http.get<{ jobId?: string } | null>(
      `${environment.apiUrl}/ai-review/manager/cycles/${encodeURIComponent(cycleId)}` +
      `/subjects/${encodeURIComponent(subjectUserId)}/peer360-digest/latest`
    ).pipe(
      map(body => (body && body.jobId) ? { jobId: body.jobId } : null),
      catchError(() => of(null)),
    );
  }

  /**
   * Generic streaming poller for manager endpoints. Emits intermediate
   * PENDING/PROCESSING envelopes with partialText AND the terminal COMPLETED
   * envelope (with `result`). Throws on FAILED / timeout. Hits the loose-
   * typed manager-jobs status endpoint — the strict /analyze/{jobId}/status
   * endpoint binds the result into AiReviewResponse and would silently drop
   * manager-specific fields. Callers switch on `status` and cast `result`.
   */
  private pollUntilTerminal<T>(jobId: string, label: string): Observable<ManagerJobStatusResponse<T>> {
    const poll$ = timer(0, 1000).pipe(
      switchMap(() => this.statusGet<{ status: AiReviewJobStatus; result?: unknown; partialText?: string; errorMessage?: string }>(
        `${environment.apiUrl}/ai-review/manager/jobs/${jobId}/status`, label)),
      takeWhile(s => s.status === 'PENDING' || s.status === 'PROCESSING', true),
      mergeMap(s => {
        if (s.status === 'COMPLETED') {
          if (s.result == null) return throwError(() => new Error(`${label} returned no result`));
          return [{ status: s.status, result: s.result as T, partialText: s.partialText } as ManagerJobStatusResponse<T>];
        }
        if (s.status === 'FAILED') {
          return throwError(() => new Error(s.errorMessage ?? `${label} failed`));
        }
        // Intermediate PENDING/PROCESSING — carry partialText through so the
        // caller can render a streaming preview while the worker generates.
        return [{ status: s.status, partialText: s.partialText } as ManagerJobStatusResponse<T>];
      })
    );
    const timeout$ = timer(300000).pipe(
      mergeMap(() => throwError(() => new Error(`${label} timed out`))));
    return race(poll$, timeout$);
  }
}
