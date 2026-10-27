import { Component, OnInit, OnDestroy, HostListener, inject, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { forkJoin, Observable, of, interval, Subject } from 'rxjs';
import { catchError, switchMap, tap, debounceTime, takeUntil } from 'rxjs/operators';
import { AuthService } from '../../core/auth/auth.service';
import { HttpErrorResponse } from '@angular/common/http';
import { EmployeeService, ManagerGateStatus, ReviewCycle } from '../../services/employees.services';
import { AiReviewService, QuestionSuggestResponse, Peer360Digest, SuggestionCitation } from '../../services/ai-review.service';
import { Peer360AssignmentService } from '../../services/peer360-assignment.service';
import { Question, Review, ManagerGoldenReview, GoldenCitation } from '../../models/review.model';
import { LoadingSpinnerComponent } from '../../shared/loading-spinner/loading-spinner.component';
import { SkeletonComponent } from '../../shared/skeleton/skeleton.component';
import { escapeHtml } from '../../core/utils/escape-html';

/**
 * UUID v1-v5 shape — `8-4-4-4-12` lowercase hex. Used to filter out
 * questionId / reviewId / authorUserId / cycleId values from the live
 * streaming preview so the manager doesn't see hex noise mid-stream.
 */
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Identifier / enum-shaped token: purely lowercase letters + digits + `_-`,
 * no whitespace, no capitals. Catches enum values ("self", "peer_360",
 * "positive", "constructive", "mixed") and short id strings without
 * dropping real prose, which always carries whitespace and/or capitals.
 * Pair with a length check (<30) so a long-tail kebab-case label can't
 * be misclassified.
 */
const ENUM_LIKE_RE = /^[a-z0-9_-]+$/;

/**
 * Streaming preview state per AI task. Each tab that streams owns its own
 * buffer so concurrent regenerates don't trample each other. See the
 * streamingBuffers field doc for the bug this isolates.
 */
type StreamingTask = 'insights' | 'golden' | 'digest';
interface StreamingBuffer {
  /** Prettified latest poll snapshot — what the rAF loop walks toward. */
  target: string;
  /** Chars currently rendered (template-visible). Lags behind target. */
  displayed: string;
  /** In-flight rAF handle, or null. */
  raf: number | null;
  /**
   * Frames to skip before advancing. Set after a punctuation mark so the
   * cursor pauses briefly at sentence/clause boundaries — produces a more
   * human reading rhythm than uniform char-per-frame advance.
   */
  pauseFrames: number;
}

@Component({
  selector: 'app-manager-review-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule, LoadingSpinnerComponent, SkeletonComponent],
  templateUrl: './manager-review-form.component.html',
  styleUrls: ['./manager-review-form.component.css'],
})
export class ManagerReviewFormComponent implements OnInit, OnDestroy {
  private employeeService = inject(EmployeeService);
  private aiReviewService = inject(AiReviewService);
  private peer360Service  = inject(Peer360AssignmentService);
  private authService     = inject(AuthService);
  private route           = inject(ActivatedRoute);
  private router          = inject(Router);
  private cdr             = inject(ChangeDetectorRef);

  cycle:             ReviewCycle | null = null;
  review:            Review | null = null;
  employeeReview:    Review | null = null;
  questions:         Question[] = [];
  groupedQuestions:  { category: string; questions: Question[] }[] = [];
  employeeQuestions: Question[] = [];
  /** Questions for the PEER_360 template snapshot — used to label the 360 Raw
   *  tab's per-peer answers with the actual question text. */
  peer360Questions:  Question[] = [];
  responses:         Record<string, any> = {};

  subjectUserId  = '';
  employeeName   = '';
  milestoneId    = '';
  templateId         = '';
  milestoneStartDate = '';
  milestoneEndDate   = '';
  // Server-authoritative submit gate — driven by /reviews/manager-gate, NOT
  // by URL query params. Defaults to false until the response lands.
  canSubmit     = false;
  gateLoading   = true;
  gateStatus: ManagerGateStatus | null = null;
  viewOnly      = false;

  currentStep = 0;
  saving    = false;
  loading   = true;
  errorMsg  = '';
  missingQuestionIds = new Set<string>();
  showSubmitBlockerPopup = false;
  submitBlockers: string[] = [];

  // ── Resizable split ───────────────────────────────────────────────────────
  leftPanelPct  = 50;
  isResizing    = false;
  private resizeStartX   = 0;
  private resizeStartPct = 50;
  /** True only after the cursor moves > 3px during a drag — distinguishes click from drag. */
  private resizeHasMoved = false;

  startResize(e: MouseEvent): void {
    this.isResizing     = true;
    this.resizeHasMoved = false;
    this.resizeStartX   = e.clientX;
    this.resizeStartPct = this.leftPanelPct;
    e.preventDefault();
  }

  @HostListener('document:mousemove', ['$event'])
  onMouseMove(e: MouseEvent): void {
    if (!this.isResizing) return;
    const dx = e.clientX - this.resizeStartX;
    if (Math.abs(dx) > 3) this.resizeHasMoved = true;
    const container = document.querySelector('.split-layout') as HTMLElement;
    if (!container) return;
    const deltaPct = (dx / container.clientWidth) * 100;
    this.leftPanelPct = Math.min(75, Math.max(25, this.resizeStartPct + deltaPct));
  }

  @HostListener('document:mouseup')
  onMouseUp(): void {
    if (this.isResizing && !this.resizeHasMoved) {
      this.leftPanelCollapsed = !this.leftPanelCollapsed;
    }
    this.isResizing = false;
    this.resizeHasMoved = false;
  }

  // ── Auto-save ─────────────────────────────────────────────────────────────
  autoSaveStatus: '' | 'saving' | 'saved' | 'error' = '';
  autoSavedAt: Date | null = null;
  private autoSave$  = new Subject<void>();
  private destroy$   = new Subject<void>();

  // ── Per-question AI suggestion ────────────────────────────────────────────
  questionSuggestions:       Record<string, string>  = {};
  questionSuggestionCitations: Record<string, Record<string, SuggestionCitation>> = {};
  questionSuggestionLoading: Record<string, boolean> = {};
  questionSuggestionError:   Record<string, string>  = {};
  dismissedSuggestions = new Set<string>(); // tracks questions where user dismissed AI suggestion
  private readonly MIN_WORDS         = 1;    // enable as soon as anything is typed
  private readonly SUBSTANTIAL_WORDS = 80;   // answer considered solid — button hides

  // ── Employee AI insights ──────────────────────────────────────────────────
  employeeSummary:      string | null = null;
  employeeGoldenMirror: string | null = null;
  /** Citations from INSIGHTS — embedded in goldenMirror via [cN] tokens.
   *  Same shape as parsedGoldenReview.citations; all entries have source='self'. */
  employeeGoldenCitations: Record<string, GoldenCitation> | null = null;
  insightsLoading = false;
  insightsFailed  = false; // ADR-5: surfaces FAILED inside the tab body, never blocks submit
  /**
   * Streaming preview text from the INSIGHTS Gemini call. Populated from
   * AiReviewInsightsStatusResponse.partialText while status=PROCESSING and
   * cleared once status=COMPLETED (the structured `result` view takes over).
   * Bound to the AI Insights tab body for ChatGPT-style live rendering.
   *
   * Per-task buffers (not a single shared buffer with context switching):
   * concurrent regenerates — e.g. INSIGHTS streaming on the Golden Mirror tab
   * while GOLDEN streams on the AI Review tab — would otherwise mutually wipe
   * each other on every poll cycle. Each task owns its own target/displayed/
   * rAF so the streams are fully independent.
   */
  private streamingBuffers: Record<StreamingTask, StreamingBuffer> = {
    insights: { target: '', displayed: '', raf: null, pauseFrames: 0 },
    golden:   { target: '', displayed: '', raf: null, pauseFrames: 0 },
    digest:   { target: '', displayed: '', raf: null, pauseFrames: 0 },
  };

  /** Template-binding getters — one per task. Gate the *ngIf on these. */
  get displayedInsightsStream(): string { return this.streamingBuffers.insights.displayed; }
  get displayedGoldenStream():   string { return this.streamingBuffers.golden.displayed; }
  get displayedDigestStream():   string { return this.streamingBuffers.digest.displayed; }

  /**
   * Update the streaming target for a specific task from the latest poll
   * snapshot. Prettifies (strips JSON envelope) and schedules the rAF lerp
   * forward on that task's buffer only — other concurrent streams are
   * untouched.
   */
  private setStreamingTarget(rawPartial: string, task: StreamingTask): void {
    const buf = this.streamingBuffers[task];
    buf.target = this.prettifyStreamingSnapshot(rawPartial);
    this.scheduleStreamingRender(task);
  }

  private scheduleStreamingRender(task: StreamingTask): void {
    const buf = this.streamingBuffers[task];
    if (buf.raf !== null) return;
    buf.raf = requestAnimationFrame(() => {
      buf.raf = null;
      const remaining = buf.target.length - buf.displayed.length;
      if (remaining <= 0) return;

      // ── Pause counter (punctuation rhythm) ─────────────────────────────
      // Hold the cursor briefly at sentence/clause boundaries so the eye
      // gets natural reading rhythm instead of a uniform sprint. Feels
      // closer to a human typing than a uniform stream.
      if (buf.pauseFrames > 0) {
        buf.pauseFrames--;
        this.scheduleStreamingRender(task);
        return;
      }

      // ── Adaptive pace ──────────────────────────────────────────────────
      // When far behind the target, sprint to catch up (the stream burst
      // doesn't feel laggy). When close, settle to a natural typewriter
      // tempo so the final chars don't whip past. Anchor at ~20 ch/frame
      // (~1200 chars/sec @ 60fps).
      let charsThisFrame: number;
      if (remaining > 600)       charsThisFrame = 45;   // catch-up sprint
      else if (remaining > 200)  charsThisFrame = 30;
      else if (remaining > 60)   charsThisFrame = 20;
      else                       charsThisFrame = 12;   // settle near end
      const step = Math.min(charsThisFrame, remaining);
      const newLen = buf.displayed.length + step;
      buf.displayed = buf.target.slice(0, newLen);

      // ── Inspect the char we just wrote and queue a pause if it's a
      //    natural break point. Tuned for ~60fps so values stay short
      //    enough to feel like rhythm, not lag.
      const last = buf.target.charAt(newLen - 1);
      if (last === '.' || last === '!' || last === '?')      buf.pauseFrames = 7;   // ~115ms sentence end
      else if (last === ',' || last === ';' || last === ':') buf.pauseFrames = 3;   // ~50ms clause break
      else if (last === '\n')                                buf.pauseFrames = 5;   // ~85ms line break

      if (buf.displayed.length < buf.target.length) {
        this.scheduleStreamingRender(task);
      }
    });
  }

  /**
   * Tear down a streaming preview — cancels any in-flight rAF and clears
   * that task's target + displayed. Pass no arg to clear ALL three (used
   * in ngOnDestroy).
   */
  private clearStreaming(task?: StreamingTask): void {
    const tasks: StreamingTask[] = task ? [task] : ['insights', 'golden', 'digest'];
    for (const t of tasks) {
      const buf = this.streamingBuffers[t];
      if (buf.raf !== null) {
        cancelAnimationFrame(buf.raf);
        buf.raf = null;
      }
      buf.target = '';
      buf.displayed = '';
      buf.pauseFrames = 0;
    }
  }

  /**
   * Strips the JSON envelope from Gemini's in-progress structured output so
   * the streaming preview shows readable prose instead of `{"summary":"..."`.
   *
   * Generic for ANY structured-output task — extracts every quoted-string
   * VALUE from the partial JSON and joins them with blank lines. Works for:
   *   INSIGHTS  → summary + goldenMirror + citation quotes
   *   GOLDEN    → technologyComments + consultingComments + companyValuesComments
   *               + developmentOpportunities + otherComments + citation quotes
   *   DIGEST    → theme.theme + theme.summary + consensus.point + dissent.point
   *
   * Mid-stream JSON is malformed (unclosed strings, no terminating brace), so
   * we use a tolerant `"key":"value"` regex with the value pattern allowing
   * any non-quote/non-backslash chars OR an escaped pair. Unescapes \n / \" /
   * \\ for display.
   *
   * Filtering rules (drop non-prose values that pollute the live preview):
   *   1. Skip values shorter than 4 chars (noise — too short to be prose).
   *   2. Skip UUIDs (questionId / reviewId / authorUserId / cycleId etc.).
   *   3. Skip enum/identifier-shaped tokens — purely lowercase + digits + _ /
   *      dash, under 30 chars long ("self", "peer_360", "positive",
   *      "constructive", etc.). Real prose has whitespace and/or capitals.
   */
  private prettifyStreamingSnapshot(raw: string): string {
    if (!raw) return '';
    const parts: string[] = [];
    const re = /"[\w$]+"\s*:\s*"((?:[^"\\]|\\.)*)/g;
    let match: RegExpExecArray | null;
    while ((match = re.exec(raw)) !== null) {
      const v = match[1];
      if (!v || v.length < 4) continue;
      if (UUID_RE.test(v)) continue;
      if (v.length < 30 && ENUM_LIKE_RE.test(v)) continue;
      parts.push(this.unescapeJsonString(v));
    }
    return parts.join('\n\n');
  }

  private unescapeJsonString(s: string): string {
    return s
      .replace(/\\n/g, '\n')
      .replace(/\\t/g, '\t')
      .replace(/\\"/g, '"')
      .replace(/\\\\/g, '\\');
  }
  insightsRetrying = false;
  // Cached subject metadata — needed to rebuild the INSIGHTS payload when the
  // manager retries an employee-side job (Slice 4b).
  private subjectSeniority  = '';
  private subjectDiscipline = '';

  // ── Left-pane tabs (ADR-4 Path C′) ────────────────────────────────────────
  // Internal state still keys off five values so the per-section content
  // blocks (self_raw / golden, peer360_raw / peer360_digest) can stay
  // untouched. The UI surface is collapsed to 2 tabs (Self-Review /
  // Peer Feedback) + a Responses ↔ AI Insights toggle — see mainTab,
  // leftMode, setMainTab, setLeftMode below.
  // Default lands on the AI Insights view ('golden') so the manager sees
  // the digested narrative first; raw responses are one click away.
  leftTab: 'self_raw' | 'peer360_raw' | 'peer360_digest' | 'golden' | 'ai_summary' = 'golden';

  // Per-section memory so flipping between Self-Review and Peer Feedback
  // preserves whichever mode (raw / AI) the manager last chose on that
  // section. Default: AI Insights on self (matches initial leftTab);
  // peer stays on raw until the manager opts in (peer360 digest has its
  // own generation gate).
  private leftModeMemory: { self: 'raw' | 'ai'; peer: 'raw' | 'ai' } = {
    self: 'ai',
    peer: 'raw',
  };

  get mainTab(): 'self' | 'peer' {
    return this.leftTab === 'peer360_raw' || this.leftTab === 'peer360_digest'
      ? 'peer'
      : 'self';
  }

  get leftMode(): 'raw' | 'ai' {
    return this.leftTab === 'golden' || this.leftTab === 'peer360_digest' || this.leftTab === 'ai_summary'
      ? 'ai'
      : 'raw';
  }

  setMainTab(tab: 'self' | 'peer'): void {
    const mode = this.leftModeMemory[tab];
    this.setLeftTab(this.resolveLeftTab(tab, mode));
  }

  setLeftMode(mode: 'raw' | 'ai'): void {
    const tab = this.mainTab;
    this.leftModeMemory[tab] = mode;
    this.setLeftTab(this.resolveLeftTab(tab, mode));
  }

  private resolveLeftTab(
    tab: 'self' | 'peer',
    mode: 'raw' | 'ai',
  ): ManagerReviewFormComponent['leftTab'] {
    if (tab === 'self') return mode === 'ai' ? 'golden' : 'self_raw';
    return mode === 'ai' ? 'peer360_digest' : 'peer360_raw';
  }

  // ── 360 Raw tab state ─────────────────────────────────────────────────────
  peer360Reviews: Review[] = [];
  peer360Loading = false;

  // ── 360 Digest tab state (ADR-2 / ADR-5) ──────────────────────────────────
  // 'idle'    — not generated yet (default; user opens tab → fires startPeer360Digest)
  // 'loading' — POST in flight or polling
  // 'ready'   — digest in hand
  // 'failed'  — terminal error from backend or polling timeout (manual Retry button shown)
  peer360DigestState: 'idle' | 'loading' | 'ready' | 'failed' = 'idle';
  peer360Digest: Peer360Digest | null = null;
  peer360DigestError = '';
  // Option A: true while resolving the latest (subject, cycle) digest jobId
  // server-side (Peer360DigestIndex), so the eager-load and tab-open callers
  // don't both resolve concurrently. Reset in the resolve subscribe handlers.
  digestResolving = false;

  // ── Left panel collapse ───────────────────────────────────────────────────
  leftPanelCollapsed = false;
  selfReviewCollapsed = false;
  rightTab: 'form' | 'ai' = 'form';

  switchToAiTab(): void {
    this.rightTab = 'ai';
    if (!this.parsedGoldenReview && !this.goldenLoading) {
      // ADR-6: check review.managerGoldenJobId first (same as digest)
      this.loadOrFetchGolden();
    }
  }

  // ── Preview modal ─────────────────────────────────────────────────────────
  showPreview = false;
  parsedGoldenReview: ManagerGoldenReview | null = null;

  // ── Manager AI Narrative edit mode ────────────────────────────────────────
  // Edit-then-save flow on the AI Review tab. The buffer is a deep clone of
  // parsedGoldenReview taken at edit-mode entry; saveGoldenEdit() PUTs it to
  // /ai-review/manager/jobs/{jobId}/result so a reload renders the edited
  // version; cancelGoldenEdit() drops the buffer and exits without writes.
  // Citations preserved through the edit cycle; the manager may break referential
  // integrity by deleting [cN] tokens — acceptable trade-off for the MVP.
  goldenEditMode = false;
  goldenEditBuffer: ManagerGoldenReview | null = null;
  goldenSaving = false;
  goldenEditError = '';

  // ── Citation jump-to-source (Slice 5b / ADR-3) ────────────────────────────
  // When a chip in the golden preview is clicked, the matching evidence tab
  // opens with the cited quote highlighted. Cleared when the manager manually
  // switches tabs (so noise doesn't linger across an unrelated browse).
  highlightedCitation: GoldenCitation | null = null;
  /** Which citation number is currently active — for POC-style "filled bubble" feedback. */
  activeCitationNum: number | null = null;
  activeSuggestionCitationKey: string | null = null;
  highlightedDigestCitation: SuggestionCitation | null = null;

  private managerId = '';

  ngOnInit(): void {
    // Auto-save: debounce 2.5s after last change (same as self review)
    this.autoSave$.pipe(
      debounceTime(2500),
      takeUntil(this.destroy$)
    ).subscribe(() => this.performAutoSave());

    this.managerId    = this.authService.getTokenPayload()?.sub ?? '';
    const cycleId     = this.route.snapshot.queryParamMap.get('cycleId')      ?? '';
    const milestoneId = this.route.snapshot.queryParamMap.get('milestoneId')  ?? '';
    const reviewId    = this.route.snapshot.queryParamMap.get('reviewId')     ?? '';
    this.subjectUserId = this.route.snapshot.queryParamMap.get('subjectUserId') ?? '';

    // Server-authoritative submit gate — replaces the legacy `canSubmit` query
    // param. Defaults to false (locked) until the gate response lands so a slow
    // network can't briefly let the manager click submit prematurely.
    this.canSubmit = false;
    this.gateLoading = true;
    if (cycleId && this.subjectUserId) {
      this.employeeService.getManagerGate(cycleId, this.subjectUserId)
        .pipe(catchError(() => of({ open: false, reasons: [] } as any)))
        .subscribe(gate => {
          this.gateLoading = false;
          if (gate) {
            this.gateStatus = gate;
            this.canSubmit  = gate.open;
          }
        });

      // 360 Raw tab — load peer reviews about this subject for the manager.
      // Server-side ACL: subject is forbidden, manager/admin allowed.
      this.peer360Loading = true;
      this.peer360Service.list(cycleId, this.subjectUserId)
        .pipe(takeUntil(this.destroy$), catchError(() => of([] as Review[])))
        .subscribe(rows => {
          this.peer360Loading = false;
          // Show only completed/published peer reviews — drafts and not-started
          // rows have no answer content worth surfacing on the manager screen.
          this.peer360Reviews = (rows ?? []).filter(r =>
            r.status === 'COMPLETED' || r.status === 'PUBLISHED');
        });
    } else {
      this.gateLoading = false;
    }

    // Fetch subject metadata — name for display, seniority/discipline for the
    // INSIGHTS retry payload (Slice 4b: manager-driven retry of a failed
    // employee-side job since the employee never sees the failure).
    if (this.subjectUserId) {
      this.employeeService.getUserById(this.subjectUserId)
        .pipe(catchError(() => of(null)))
        .subscribe((u: any) => {
          if (u) {
            this.employeeName = `${u.firstName ?? ''} ${u.lastName ?? ''}`.trim();
            this.subjectSeniority  = u.seniority  ?? '';
            this.subjectDiscipline = u.discipline ?? '';
          }
        });
    }
    this.viewOnly     = this.route.snapshot.queryParamMap.get('viewOnly')     === 'true';
    this.milestoneId  = milestoneId;

    forkJoin({
      cycles:   this.employeeService.getCycles().pipe(catchError(() => of([]))),
      // Get ALL reviews where the employee is the subject (includes both SELF and MANAGER reviews)
      reviews:  this.employeeService.getReviewsBySubject(this.subjectUserId).pipe(catchError(() => of([]))),
    }).subscribe(({ cycles, reviews }) => {
      // Split into employee self-reviews and manager reviews written by this manager
      const managerReviews  = reviews.filter((r: Review) => r.reviewType === 'MANAGER' && r.authorUserId === this.managerId);
      const employeeReviews = reviews.filter((r: Review) => r.reviewType === 'SELF');
      this.cycle = cycles.find((c: ReviewCycle) => c.cycleId === cycleId) ?? cycles[0] ?? null;

      if (!this.cycle) {
        this.errorMsg = 'No active cycle found.';
        this.loading  = false;
        return;
      }

      // Find employee's self-review — show whenever it's submitted. After a
      // bundle publish the status flips COMPLETED → PUBLISHED, so we must
      // accept both. Matches the broader check in isSelfReviewDone(); the
      // previous COMPLETED-only filter caused the left pane to render
      // "Employee hasn't submitted" once the cycle's reviews were published,
      // even though the status pill (using isSelfReviewDone) said otherwise.
      this.employeeReview = employeeReviews.find((r: Review) =>
        r.reviewType === 'SELF' && r.cycleId === cycleId &&
        (r.status === 'COMPLETED' || r.status === 'PUBLISHED')
      ) ?? null;

      // Load AI insights if available — poll until terminal so a manager opening
      // the page mid-job still sees the result without a manual refresh. Bound
      // to destroy$ so navigating away cancels the long-running polling loop.
      if (this.employeeReview?.insightsJobId) {
        this.insightsLoading = true;
        this.insightsFailed  = false;
        this.clearStreaming('insights');
        this.aiReviewService.pollInsights(this.employeeReview.insightsJobId)
          .pipe(
            takeUntil(this.destroy$),
            catchError(() => { this.insightsFailed = true; this.insightsLoading = false; this.clearStreaming('insights'); return of(null); }),
          )
          .subscribe(statusResp => {
            if (!statusResp) return;
            // Streaming preview while PROCESSING — push partialText through
            // the rAF-smoothed target so the manager sees a steady typewriter
            // flow rather than chunky poll-cadence jumps.
            if (statusResp.status === 'PROCESSING' || statusResp.status === 'PENDING') {
              if (statusResp.partialText) {
                this.setStreamingTarget(statusResp.partialText, 'insights');
              }
              // Keep spinner; don't fall through to the COMPLETED render yet.
              return;
            }
            // Terminal COMPLETED — hydrate structured fields, clear streaming.
            this.insightsLoading = false;
            const insights = statusResp.result;
            if (insights) {
              this.employeeSummary         = insights.summary      ?? null;
              this.employeeGoldenMirror    = insights.goldenMirror ?? null;
              this.employeeGoldenCitations = insights.citations ?? null;
            }
            this.clearStreaming('insights');
          });
      }

      // Eagerly reuse a cached peer-360 digest too (mirrors the insights reuse
      // above). Poll-only — a status GET on an already-completed job, never a
      // Gemini call. Anchored on the SELF review so the digest survives a refresh
      // even with no manager review; without this, reuse ran only lazily inside
      // setLeftTab and missed the pointer once the review finished loading.
      this.reuseCachedDigestIfAny();

      // Find the MANAGER milestone — prefer the one passed via query param,
      // then any milestone assigned to MANAGER role (never fall back to employee milestone)
      const managerMilestone = this.cycle.milestones?.find((m: any) => m.milestoneId === milestoneId)
                            ?? this.cycle.milestones?.find((m: any) => m.assignedRoles?.includes('MANAGER'));

      if (!managerMilestone) {
        this.errorMsg = 'No manager milestone found for this cycle.';
        this.loading  = false;
        return;
      }

      this.milestoneId        = managerMilestone.milestoneId;
      this.milestoneStartDate = managerMilestone.startDate ?? '';
      this.milestoneEndDate   = managerMilestone.endDate   ?? '';

      // Load employee self-review questions for the LEFT panel. Filter by the
      // template's reviewType (SELF vs PEER_360), not by assignedRoles —
      // both SELF and PEER_360 milestones have assignedRoles: ['EMPLOYEE']
      // so a `find` by role would silently match whichever appears first.
      const employeeMilestone = this.cycle.milestones?.find((m: any) =>
        m.templateSnapshot?.reviewType === 'SELF'
      ) ?? this.cycle.milestones?.find((m: any) =>
        m.assignedRoles?.includes('EMPLOYEE')
      );
      if (employeeMilestone?.templateSnapshot?.questions?.length) {
        this.employeeQuestions = employeeMilestone.templateSnapshot.questions;
      }

      // Load PEER_360 questions so the 360 Raw tab can label each answer with
      // the actual question text instead of "Q1, Q2, …".
      const peer360Milestone = this.cycle.milestones?.find((m: any) =>
        m.templateSnapshot?.reviewType === 'PEER_360'
      );
      if (peer360Milestone?.templateSnapshot?.questions?.length) {
        this.peer360Questions = peer360Milestone.templateSnapshot.questions;
      }

      // Load manager questions for the RIGHT panel
      if (managerMilestone.templateSnapshot?.questions?.length) {
        this.questions  = managerMilestone.templateSnapshot.questions;
        this.buildGroupedQuestions();
        this.templateId = managerMilestone.templateSnapshot.templateId ?? '';
        this.initReview(managerReviews, reviewId, cycleId);

      } else if (managerMilestone.templateSnapshot?.templateId) {
        this.employeeService.getTemplateById(managerMilestone.templateSnapshot.templateId)
          .pipe(catchError(() => of(null)))
          .subscribe((tmpl: any) => {
            this.questions  = tmpl?.questions ?? [];
            this.buildGroupedQuestions();
            this.templateId = managerMilestone.templateSnapshot?.templateId ?? '';
            if (!this.questions.length) this.errorMsg = 'No questions found in manager template.';
            this.initReview(managerReviews, reviewId, cycleId);
          });

      } else {
        this.errorMsg = 'No template attached to the manager milestone.';
        this.loading  = false;
      }
    });
  }

  private initReview(reviews: Review[], reviewId: string, cycleId: string): void {
    const existing = reviews.find((r: Review) =>
      (reviewId && r.reviewId === reviewId) ||
      (r.cycleId === cycleId && r.reviewType === 'MANAGER' &&
       r.subjectUserId === this.subjectUserId && r.authorUserId === this.managerId)
    );

    if (existing) {
      this.review = existing;
      for (const a of existing.answers ?? []) {
        this.responses[a.questionId] = a.response;
      }
      if (existing.status === 'COMPLETED' || existing.status === 'PUBLISHED') {
        this.viewOnly = true;
      }
    } else {
      this.review = {
        cycleId:       this.cycle!.cycleId,
        milestoneId:   this.milestoneId,
        templateId:    this.templateId,
        subjectUserId: this.subjectUserId,
        authorUserId:  this.managerId,
        userId:        this.managerId,
        reviewType:    'MANAGER',
        status:        'DRAFT',
        answers:       [],
      };
    }

    for (const q of this.questions) {
      if (this.responses[q.questionId] === undefined) {
        this.responses[q.questionId] = (q.type === 'multi_select' || q.type === 'MULTI_SELECT') ? [] : '';
      }
    }
    this.loading = false;
  }

  // ── 360 Digest (ADR-2 / ADR-5) ───────────────────────────────────────────
  /**
   * Switch left-pane tab. Centralised because opening the digest tab fires
   * the AI job lazily — we don't pay for a Gemini call until the manager
   * actually asks for the digest.
   */
  setLeftTab(tab: ManagerReviewFormComponent['leftTab']): void {
    this.leftTab = tab;
    // Keep the 2-tab + toggle UI in sync with direct setLeftTab callers
    // (e.g. citation jumps in jumpToCitation, lazy-load triggers below).
    if (tab === 'self_raw') this.leftModeMemory.self = 'raw';
    else if (tab === 'golden') this.leftModeMemory.self = 'ai';
    else if (tab === 'peer360_raw') this.leftModeMemory.peer = 'raw';
    else if (tab === 'peer360_digest') this.leftModeMemory.peer = 'ai';
    // Manual tab switch clears any prior chip-driven highlight — manager is
    // browsing freely now, not following a cited reference.
    this.highlightedCitation = null;
    this.activeCitationNum = null;
    if (tab === 'peer360_digest') {
      // Reuse a cached completed digest (poll-only — cheap status GET, never
      // Gemini). With NO cached job, reuseCachedDigestIfAny no-ops and the tab
      // stays 'idle' so the manager clicks "Generate Digest" (manual-button rule,
      // like golden/insights). Also runs eagerly from the review-load callback so
      // the digest survives a refresh whatever tab was open.
      this.reuseCachedDigestIfAny();
    }
  }

  /**
   * Poll a PEER_360_DIGEST job resolved by (subject, cycle) via the resolve
   * endpoint (Option A). Poll-only — a cheap status GET, never a Gemini call.
   * On COMPLETED → render. On FAILED or poll error → surface the failed state
   * with a manual Retry (manual-button rule); never auto re-fires Gemini.
   */
  private pollCachedPeer360Digest(jobId: string): void {
    this.peer360DigestState = 'loading';
    this.peer360DigestError = '';
    this.peer360Digest = null;
    this.clearStreaming('digest');

    this.aiReviewService.pollPeer360Digest(jobId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: statusResp => {
          if (statusResp.status === 'PROCESSING' || statusResp.status === 'PENDING') {
            if (statusResp.partialText) {
              this.setStreamingTarget(statusResp.partialText, 'digest');
            }
            return;
          }
          // Terminal COMPLETED — swap to structured render.
          this.peer360DigestState = 'ready';
          if (statusResp.result) this.peer360Digest = statusResp.result;
          this.clearStreaming('digest');
        },
        error: () => {
          // Cached job is gone or failed — surface the failed state with a manual
          // Retry (manual-button rule, consistent with golden). Do NOT auto re-fire
          // Gemini: that re-ran a fresh digest on every refresh of a failed job.
          this.clearStreaming('digest');
          this.peer360DigestState = 'failed';
        },
      });
  }

  /**
   * Reuse an already-completed peer-360 digest for this (subject, cycle) by
   * resolving the latest COMPLETED job server-side (Option A), then poll-only
   * (a cheap status GET, never a Gemini call). The digest is a (subject, cycle)
   * artifact resolved via the Peer360DigestIndex GSI — not pinned to any review
   * row — so it resolves identically whether or not a manager review exists, and
   * survives a refresh. Called eagerly from the review-load callback AND from
   * setLeftTab when the digest tab opens. No-op unless idle (and not already
   * resolving), so it never double-resolves and never auto-generates.
   */
  private reuseCachedDigestIfAny(): void {
    if (this.peer360DigestState !== 'idle' || this.digestResolving) return;
    if (!this.cycle?.cycleId || !this.subjectUserId) return;
    this.digestResolving = true;
    this.aiReviewService.getLatestPeer360Digest(this.cycle.cycleId, this.subjectUserId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: hit => {
          this.digestResolving = false;
          // Re-check idle: the user may have hit Generate while we resolved.
          if (hit?.jobId && this.peer360DigestState === 'idle') {
            this.pollCachedPeer360Digest(hit.jobId);
          }
        },
        error: () => { this.digestResolving = false; },
      });
  }

  /**
   * Clicked from a citation chip in the golden preview (ADR-3). Closes the
   * preview modal, switches the left pane to the right evidence tab, and
   * sets {@link highlightedCitation} so the matching answer renders the
   * cited substring inside a <mark>. Slice 5b.
   *
   * Defensive resolve before storing: Gemini sometimes returns a citation
   * whose questionId (or peer reviewId) doesn't match the loaded evidence
   * exactly, which would silently disable the highlight. See
   * {@link resolveGoldenCitation}.
   */
  jumpToCitation(cite: GoldenCitation, num: number | null = null): void {
    this.showPreview = false;
    this.activeCitationNum = num;
    const targetTab = cite.source === 'peer_360' ? 'peer360_raw' : 'self_raw';
    const alreadyOnTab = this.leftTab === targetTab;

    const resolved = this.resolveGoldenCitation(cite);

    // Clear highlight first so Angular detects a change even when on same tab
    this.highlightedCitation = null;
    this.cdr.detectChanges();

    this.highlightedCitation = resolved;
    this.leftTab = targetTab;
    this.cdr.detectChanges();

    // Give DOM more time when already on the tab (no tab-switch re-render)
    setTimeout(() => this.scrollHighlightIntoView(), alreadyOnTab ? 80 : 30);
  }

  /**
   * If the citation's questionId / reviewId already matches a loaded answer,
   * return it unchanged. Otherwise fall back to a quote substring search and
   * rewrite the citation to point at the matching answer so isHighlightedSelf
   * / isHighlightedPeer fire and highlightSegments renders the <mark>.
   *
   * Order of attempts:
   *   source='self'      — within employee self-review answers
   *   source='peer_360'  — within the peer reviewId's answers, then across
   *                        all loaded peer rows if reviewId itself is wrong
   *
   * Returns the original citation when no match is found (graceful no-op).
   */
  private resolveGoldenCitation(cite: GoldenCitation): GoldenCitation {
    const quote = (cite.quote ?? '').toLowerCase();

    if (cite.source === 'self') {
      const answers = this.employeeReview?.answers ?? [];
      if (cite.questionId && answers.some(a => a.questionId === cite.questionId)) {
        return cite;
      }
      if (quote) {
        const found = answers.find(a => this.answerToText(a.response).toLowerCase().includes(quote));
        if (found) return { ...cite, questionId: found.questionId };
      }
      return cite;
    }

    // peer_360
    const peer = this.peer360Reviews.find(p => p.reviewId === cite.reviewId);
    if (peer) {
      const answers = peer.answers ?? [];
      if (cite.questionId && answers.some(a => a.questionId === cite.questionId)) {
        return cite;
      }
      if (quote) {
        const found = answers.find(a => this.answerToText(a.response).toLowerCase().includes(quote));
        if (found) return { ...cite, questionId: found.questionId };
      }
      return cite;
    }

    // reviewId doesn't match any loaded peer — scan all peers for the quote.
    if (quote) {
      for (const p of this.peer360Reviews) {
        const found = (p.answers ?? []).find(a => this.answerToText(a.response).toLowerCase().includes(quote));
        if (found && p.reviewId) return { ...cite, reviewId: p.reviewId, questionId: found.questionId };
      }
    }
    return cite;
  }

  private answerToText(response: string | number | string[] | null | undefined): string {
    if (response == null) return '';
    if (Array.isArray(response)) return response.join(', ');
    return String(response);
  }

  private scrollHighlightIntoView(): void {
    const el = document.querySelector('.citation-mark') as HTMLElement | null;
    if (!el) {
      // Fallback: scroll to cited emp-qa block
      const cited = document.querySelector('.emp-qa.cited') as HTMLElement | null;
      if (cited) this.scrollElIntoPanel(cited);
      return;
    }
    this.scrollElIntoPanel(el);
  }

  private scrollElIntoPanel(el: HTMLElement): void {
    // Walk up to find the nearest scrollable ancestor
    let parent = el.parentElement;
    while (parent) {
      const overflow = getComputedStyle(parent).overflowY;
      if ((overflow === 'auto' || overflow === 'scroll') && parent.scrollHeight > parent.clientHeight) {
        const elRect = el.getBoundingClientRect();
        const parentRect = parent.getBoundingClientRect();
        const offset = elRect.top - parentRect.top + parent.scrollTop - parent.clientHeight / 2 + el.clientHeight / 2;
        parent.scrollTo({ top: offset, behavior: 'smooth' });
        return;
      }
      parent = parent.parentElement;
    }
    el.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }

  /**
   * Split a piece of evidence text into segments around a quoted substring,
   * for the chip-driven highlight. If {@code quote} is missing, empty, or not
   * found verbatim, the whole text is returned as a single non-highlighted
   * segment — graceful when Gemini's quote drifted from the source.
   */
  highlightSegments(text: string | number | string[] | null | undefined,
                    quote: string | null | undefined):
      { text: string; mark: boolean }[] {
    // Answer.response is unioned (string | number | string[]) — coerce for the
    // text-search path. Multi-select arrays become comma-joined for highlight
    // purposes; numeric ratings become their string form.
    const t: string = text == null
      ? ''
      : Array.isArray(text) ? text.join(', ') : String(text);
    if (!t) return [];
    if (!quote || !quote.trim()) return [{ text: t, mark: false }];
    let idx = t.indexOf(quote);
    let q = quote;
    if (idx < 0) {
      // Fallback for minor casing/spacing drift from model citations.
      const tNorm = t.toLowerCase();
      const qNorm = quote.trim().toLowerCase();
      idx = tNorm.indexOf(qNorm);
      q = qNorm;
    }
    if (idx < 0) return [{ text: t, mark: false }];
    const out: { text: string; mark: boolean }[] = [];
    if (idx > 0) out.push({ text: t.slice(0, idx), mark: false });
    out.push({ text: t.slice(idx, idx + q.length), mark: true });
    if (idx + q.length < t.length) out.push({ text: t.slice(idx + q.length), mark: false });
    return out;
  }

  /** True iff the active citation targets this question on the SELF review. */
  isHighlightedSelf(questionId: string): boolean {
    return this.highlightedCitation?.source === 'self'
        && this.highlightedCitation?.questionId === questionId;
  }

  /** True iff the active citation targets this question on this peer's row. */
  isHighlightedPeer(reviewId: string, questionId: string): boolean {
    return this.highlightedCitation?.source === 'peer_360'
        && this.highlightedCitation?.reviewId === reviewId
        && this.highlightedCitation?.questionId === questionId;
  }

  /** True iff the active citation targets this peer card (any question). */
  isHighlightedPeerCard(reviewId: string): boolean {
    return this.highlightedCitation?.source === 'peer_360'
        && this.highlightedCitation?.reviewId === reviewId;
  }

  /**
   * Slice 4b — manager retries an employee-side INSIGHTS job that failed.
   *
   * The employee submitted, the backend kicked off INSIGHTS, the job failed,
   * the employee never noticed (they navigated away long ago). The manager
   * is the only human who sees the FAILED state — and INSIGHTS produces both
   * the `summary` and `goldenMirror` in a single call, so one retry refreshes
   * both the AI Summary and Golden tabs.
   *
   * Mirrors the employee's own startInsightsAndPersistJobId() in
   * review-form.component.ts: enqueue → PUT the SELF row with the new
   * insightsJobId so subsequent visits poll the fresh job, not the old
   * FAILED one.
   *
   * Manager has PUT-rights on any review per ReviewResource.updateReview
   * (line 283: isAuthor || isAdmin || isManager).
   */
  retryEmployeeInsights(): void {
    if (this.insightsRetrying) return;
    if (!this.employeeReview?.reviewId) return;
    if (!this.subjectSeniority || !this.subjectDiscipline) {
      // Defensive: missing user metadata means we can't build the payload.
      // Surface as a fallback-message refresh rather than a thrown error.
      this.insightsFailed = true;
      return;
    }

    const pairs = (this.employeeReview.answers ?? [])
      .map(a => {
        const q = this.employeeQuestions.find(x => x.questionId === a.questionId);
        return {
          questionId: a.questionId,
          questionText: q?.text ?? a.questionId,
          answer: typeof a.response === 'string' ? a.response : String(a.response ?? ''),
        };
      })
      .filter(p => p.answer && p.answer.trim().length > 0);

    if (pairs.length === 0) {
      this.insightsFailed = true;
      return;
    }

    const reviewId = this.employeeReview.reviewId;
    this.insightsRetrying = true;
    this.insightsFailed   = false;

    let newInsightsJobId: string | null = null;
    this.aiReviewService.startInsights(pairs, this.subjectSeniority, this.subjectDiscipline)
      .pipe(
        takeUntil(this.destroy$),
        switchMap(job => {
          // Capture the new jobId; the DURABLE persist happens on COMPLETED
          // (below), not pre-poll, so a successful retry is never orphaned —
          // mirrors the peer-360 digest / golden flows which persist on
          // completion. (Pre-poll persist + swallowed 409 was the orphan bug.)
          newInsightsJobId = job.jobId;
          this.insightsLoading = true;
          this.insightsRetrying = false;
          this.clearStreaming('insights');
          return this.aiReviewService.pollInsights(job.jobId);
        }),
        catchError(() => {
          this.insightsRetrying = false;
          this.insightsLoading  = false;
          this.insightsFailed   = true;
          this.clearStreaming('insights');
          return of(null);
        }),
      )
      .subscribe(statusResp => {
        if (!statusResp) return;
        // Streaming preview while PROCESSING — push partialText through the
        // rAF-smoothed target so chunks render as a steady typewriter rather
        // than 700ms poll-cadence jumps.
        if (statusResp.status === 'PROCESSING' || statusResp.status === 'PENDING') {
          if (statusResp.partialText) {
            this.setStreamingTarget(statusResp.partialText, 'insights');
          }
          return; // keep spinner; not terminal yet
        }
        // Terminal COMPLETED — swap to the structured render.
        this.insightsLoading = false;
        const insights = statusResp.result;
        if (insights) {
          this.employeeSummary        = insights.summary      ?? null;
          this.employeeGoldenMirror   = insights.goldenMirror ?? null;
          this.employeeGoldenCitations = insights.citations    ?? null;
        }
        this.clearStreaming('insights');
        // Durable repoint: persist the new jobId so a later visit / refresh
        // re-polls THIS COMPLETED job rather than the old FAILED one. Update
        // local state first (the session always works), then a best-effort,
        // version-safe PUT. This is the orphaned-result fix.
        if (newInsightsJobId && reviewId) {
          this.employeeReview = { ...this.employeeReview!, insightsJobId: newInsightsJobId };
          this.persistInsightsJobId(reviewId, newInsightsJobId);
        }
      });
  }

  /**
   * Persist `insightsJobId` on the SELF review so a refresh re-polls the
   * COMPLETED job. Version-safe: a stale-version 409 re-fetches the current
   * version and retries once. Best-effort — never throws/blocks (ADR-5).
   *
   * Mirrors persistGoldenJobId, plus a re-fetch the sibling lacks: the orphan
   * bug showed the page-load version can already be stale, which a
   * sync-back-after-success alone can't fix. (The peer-360 digest no longer
   * persists a review-row pointer — it resolves by (subject, cycle); Option A.)
   */
  private persistInsightsJobId(reviewId: string, jobId: string): void {
    const put = (version: number | undefined) =>
      this.employeeService.updateReview(
        { reviewId, version, insightsJobId: jobId } as Partial<Review> & { reviewId: string });

    put(this.employeeReview?.version)
      .pipe(
        catchError(() =>
          // Likely a stale-version 409 — re-fetch the current version, retry once.
          this.employeeService.getReviewsBySubject(this.employeeReview?.subjectUserId ?? this.subjectUserId ?? '')
            .pipe(
              switchMap(reviews => put(reviews.find(r => r.reviewId === reviewId)?.version)),
              catchError(() => {
                // Both attempts failed — the COMPLETED result will be orphaned on
                // refresh. Log (don't throw) so this is diagnosable; the original
                // bug was a fully silent swallow.
                console.warn(`[insights] failed to persist insightsJobId=${jobId} on review ${reviewId}; result may not survive refresh`);
                return of(null);
              }),
            )),
        // takeUntil LAST so it tears down the catchError-switched inner stream too,
        // not just the initial PUT — otherwise a destroy mid-retry leaks the
        // re-fetch+PUT and runs the subscribe on a torn-down component.
        takeUntil(this.destroy$),
      )
      .subscribe(saved => {
        if (!saved) return;
        this.employeeReview = {
          ...this.employeeReview!,
          insightsJobId: (saved as any)?.insightsJobId ?? jobId,
          version: (saved as any)?.version ?? this.employeeReview?.version,
        };
      });
  }

  /**
   * Enqueue (or re-enqueue, on retry) a peer-360 digest job and poll.
   * ADR-5 retry semantics: this is what the manual Retry button calls; the
   * existing FAILED row stays in DDB (audit) but the new UUID jobId becomes
   * the active polling target.
   */
  generatePeer360Digest(): void {
    if (!this.cycle?.cycleId || !this.subjectUserId) return;
    this.peer360DigestState = 'loading';
    this.peer360DigestError = '';
    this.peer360Digest = null;

    this.clearStreaming('digest');
    this.aiReviewService.startPeer360Digest(this.cycle.cycleId, this.subjectUserId)
      .pipe(
        takeUntil(this.destroy$),
        switchMap(job => this.aiReviewService.pollPeer360Digest(job.jobId)),
      )
      .subscribe({
        next: statusResp => {
          if (statusResp.status === 'PROCESSING' || statusResp.status === 'PENDING') {
            if (statusResp.partialText) {
              this.setStreamingTarget(statusResp.partialText, 'digest');
            }
            return;
          }
          this.peer360DigestState = 'ready';
          if (statusResp.result) this.peer360Digest = statusResp.result;
          // No pointer to persist — the backend stamps (subject, cycle) on the new
          // job (createForDigest) and indexes it in Peer360DigestIndex, so a later
          // visit reuses it via getLatestPeer360Digest / reuseCachedDigestIfAny.
          // (Option A — no review-row anchor.)
          this.clearStreaming('digest');
        },
        error: err => {
          this.peer360DigestError = (err?.message as string) || 'AI digest failed';
          this.peer360DigestState = 'failed';
          this.clearStreaming('digest');
        },
      });
  }

  /**
   * Split a narrative into segments interleaving plain text with citation
   * tokens. Drives the [c1] → chip rendering in the golden preview (ADR-3).
   *
   * Example: "Strong delivery [c1] and ownership [c2]." →
   *   [{text: "Strong delivery ", cite: null},
   *    {text: "[c1]", cite: <Citation>},
   *    {text: " and ownership ", cite: null},
   *    {text: "[c2]", cite: <Citation>}]
   *
   * Tokens with no matching citation entry render as plain text — this matches
   * the backend validator's contract (it would have flagged + repaired before
   * we ever rendered, but be defensive anyway).
   */
  goldenSegments(text: string | null | undefined): { text: string; cite: GoldenCitation | null; num: number | null }[] {
    if (!text) return [];
    const map = this.parsedGoldenReview?.citations || {};
    const out: { text: string; cite: GoldenCitation | null; num: number | null }[] = [];
    const re = /\[c(\d+)\]/g;
    let last = 0;
    let m: RegExpExecArray | null;
    while ((m = re.exec(text)) !== null) {
      if (m.index > last) out.push({ text: text.slice(last, m.index), cite: null, num: null });
      const key = `c${m[1]}`;
      const cite = map[key] ?? null;
      // Render just the digit inside the bubble (POC style) — the raw "[c1]"
      // token would be ugly inside a 20px circle.
      out.push({ text: m[1], cite, num: Number(m[1]) });
      last = m.index + m[0].length;
    }
    if (last < text.length) out.push({ text: text.slice(last), cite: null, num: null });
    return out;
  }

  /**
   * Same segmentation as goldenSegments, but for the EMPLOYEE Self Golden tab
   * (consumes employeeGoldenMirror + employeeGoldenCitations from INSIGHTS).
   * Returned segments feed the same chip template — one shared visual.
   */
  selfGoldenSegments(text: string | null | undefined): { text: string; cite: GoldenCitation | null; num: number | null }[] {
    if (!text) return [];
    const map = this.employeeGoldenCitations || {};
    const out: { text: string; cite: GoldenCitation | null; num: number | null }[] = [];
    const re = /\[c(\d+)\]/g;
    let last = 0;
    let m: RegExpExecArray | null;
    while ((m = re.exec(text)) !== null) {
      if (m.index > last) out.push({ text: text.slice(last, m.index), cite: null, num: null });
      const cite = map[`c${m[1]}`] ?? null;
      out.push({ text: m[1], cite, num: Number(m[1]) });
      last = m.index + m[0].length;
    }
    if (last < text.length) out.push({ text: text.slice(last), cite: null, num: null });
    return out;
  }

  /** Tooltip text for a citation chip — source + author + quote. */
  citationTooltip(c: GoldenCitation): string {
    const who = c.source === 'peer_360'
      ? (c.authorUserId ? `Peer (${c.authorUserId.slice(0, 8)})` : 'Peer 360')
      : 'Self review';
    const q = c.quote ? `\n"${c.quote}"` : '';
    return `${who}${q}`;
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private buildGroupedQuestions(): void {
    const groups = new Map<string, Question[]>();
    for (const q of this.questions) {
      const cat = q.category || 'General';
      if (!groups.has(cat)) groups.set(cat, []);
      groups.get(cat)!.push(q);
    }
    this.groupedQuestions = Array.from(groups.entries())
      .map(([category, questions]) => ({ category, questions }));
  }

  get currentQuestion(): Question | null {
    return this.questions[this.currentStep] ?? null;
  }

  get employeeInitials(): string {
    const parts = this.employeeName.trim().split(/\s+/);
    if (parts.length >= 2) return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
    return parts[0]?.[0]?.toUpperCase() ?? '?';
  }

  progressPercent(): number {
    if (!this.questions.length) return 0;
    return Math.round(((this.currentStep + 1) / this.questions.length) * 100);
  }

  isLastStep(): boolean {
    return this.currentStep >= this.questions.length - 1;
  }

  getEmployeeAnswer(questionId: string): string {
    if (!this.employeeReview?.answers?.length) return '—';
    const ans = this.employeeReview.answers.find((a: any) => a.questionId === questionId);
    if (!ans) return '—';
    return Array.isArray(ans.response) ? ans.response.join(', ') : String(ans.response || '—');
  }

  /**
   * Look up the human-readable question text for a PEER_360 questionId.
   * Falls back to the questionId if the template snapshot isn't available
   * (defensive — shouldn't happen for cycles created via the admin flow).
   */
  getPeerQuestionText(questionId: string, fallbackIndex: number): string {
    const q = this.peer360Questions.find((x: any) => x.questionId === questionId);
    return q?.text || `Question ${fallbackIndex + 1}`;
  }

  // ── Navigation ────────────────────────────────────────────────────────────

  nextStep(): void {
    if (this.isLastStep()) return;
    this.saveDraftSilently();
    this.currentStep++;
    this.errorMsg = '';
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  prevStep(): void {
    if (this.currentStep > 0) {
      this.currentStep--;
      this.errorMsg = '';
      window.scrollTo({ top: 0, behavior: 'smooth' });
    }
  }

  // ── Input helpers ─────────────────────────────────────────────────────────

  toggleMulti(qId: string, option: string): void {
    const arr: string[] = Array.isArray(this.responses[qId]) ? [...this.responses[qId]] : [];
    const idx = arr.indexOf(option);
    if (idx >= 0) arr.splice(idx, 1); else arr.push(option);
    this.responses[qId] = arr;
  }

  isChecked(qId: string, option: string): boolean {
    return Array.isArray(this.responses[qId]) && this.responses[qId].includes(option);
  }

  setRating(qId: string, value: number): void { this.responses[qId] = value; }

  ratingArray(scale: number = 5): number[] {
    return Array.from({ length: scale }, (_, i) => i + 1);
  }

  // ── Save ──────────────────────────────────────────────────────────────────

  private buildAnswers() {
    return this.questions.map(q => ({
      questionId: q.questionId,
      response:   this.responses[q.questionId],
    }));
  }

  private createThenUpdate(status: 'DRAFT' | 'COMPLETED'): Observable<any> {
    const sync = tap((saved: any) => {
      if (saved?.reviewId) this.review!.reviewId = saved.reviewId;
      if (saved?.version)  this.review!.version  = saved.version;
    });

    if (this.review!.reviewId) {
      return this.employeeService.updateReview(this.review!).pipe(sync);
    }
    return this.employeeService.createReview(this.review!).pipe(
      switchMap((created: any) => {
        this.review!.reviewId = created.reviewId;
        return this.employeeService.updateReview(this.review!);
      }),
      sync
    );
  }

  private saveDraftSilently(): void {
    if (!this.review || this.viewOnly) return;
    this.review.answers = this.buildAnswers();
    this.review.status  = 'DRAFT';
    this.createThenUpdate('DRAFT').pipe(catchError(() => of(null))).subscribe();
  }

  save(status: 'DRAFT' | 'COMPLETED'): void {
    if (!this.review || this.viewOnly) return;

    if (status === 'COMPLETED') {
      const blockers: string[] = [];

      // NOTE: the self/peer "gate" (gateStatus.reasons) is ADVISORY only — a
      // manager may submit even when the employee's self-review or assigned
      // peers are outstanding (e.g. to beat a deadline). Those reasons are
      // surfaced as a non-blocking heads-up elsewhere (status pills), not as
      // hard submit blockers. Only genuinely incomplete *manager* input —
      // unanswered required questions below — blocks submission.

      // Check unanswered required questions
      const missing = this.questions.filter(q => {
        if (!q.required) return false;
        const v = this.responses[q.questionId];
        return v === '' || v === null || v === undefined || (Array.isArray(v) && v.length === 0) || (typeof v === 'number' && v === 0);
      });
      if (missing.length > 0) {
        this.missingQuestionIds = new Set(missing.map(q => q.questionId));
        blockers.push(`${missing.length} required question${missing.length > 1 ? 's' : ''} not answered.`);
        setTimeout(() => {
          const el = document.getElementById('mgr-q-' + missing[0].questionId);
          if (el) this.scrollElIntoPanel(el);
        }, 50);
      } else {
        this.missingQuestionIds = new Set();
      }

      if (blockers.length > 0) {
        this.submitBlockers = blockers;
        this.showSubmitBlockerPopup = true;
        return;
      }
    }

    this.errorMsg = '';
    this.saving   = true;
    this.review.answers = this.buildAnswers();
    this.review.status  = status;
    if (status === 'COMPLETED') this.review.submittedAt = new Date().toISOString();

    this.createThenUpdate(status).subscribe({
      next: () => {
        this.saving = false;
        this.closePrintWindow();

        // Every COMPLETED manager review must have a MANAGER_GOLDEN pointer
        // in the DB. If the manager already clicked "Generate AI Narrative"
        // we skip — managerGoldenJobId is already persisted. Otherwise we
        // enqueue Gemini AND persist the new jobId so the next view polls
        // the cached job instead of re-firing. Chain start → PUT in one pipe
        // (no takeUntil) so both calls survive the router.navigate below.
        if (status === 'COMPLETED'
            && this.review?.reviewId
            && !this.review?.managerGoldenJobId) {
          const reviewId = this.review.reviewId;
          const reviewVersion = this.review.version;
          this.aiReviewService.startManagerGolden(reviewId).pipe(
            switchMap(job => this.employeeService.updateReview({
              reviewId,
              version: reviewVersion,
              managerGoldenJobId: job.jobId,
            })),
            catchError(() => of(null)),
          ).subscribe();
        }

        this.router.navigate(['/my-team']);
      },
      error: () => { this.saving = false; this.errorMsg = 'Failed to save. Please try again.'; },
    });
  }

  /** Parse bullet lines from summary text — strips leading •, –, * characters */
  getSummaryLines(text: string): string[] {
    return (text || '').split('\n')
      .map(l => l.replace(/^[•\-\*]\s*/, '').trim())
      .filter(l => l.length > 0);
  }

  /** Parse golden mirror into titled sections split by double newline or ALL CAPS headers */
  getGoldenSections(text: string): { title: string; text: string }[] {
    const sections: { title: string; text: string }[] = [];
    const parts = text.split(/\n\n+/);
    for (const part of parts) {
      const lines = part.trim().split('\n');
      const firstLine = lines[0].trim();
      const isHeader = firstLine === firstLine.toUpperCase() && firstLine.length > 3;
      if (isHeader && lines.length > 1) {
        sections.push({ title: firstLine, text: lines.slice(1).join(' ').trim() });
      } else {
        sections.push({ title: '', text: part.trim() });
      }
    }
    return sections.filter(s => s.text.length > 0);
  }

  cancel(): void {
    this.closePrintWindow();
    this.router.navigate(['/my-team']);
  }

  private buildPrintHtml(): string {
    // Build questions HTML from data (avoids Angular scoping issues)
    let qHtml = '';
    let num = 1;
    for (const group of this.groupedQuestions) {
      if (group.category !== 'General') {
        qHtml += `<div class="preview-cat-label">${escapeHtml(group.category)}</div>`;
      }
      for (const q of group.questions) {
        const ans = this.getResponseDisplay(q.questionId);
        const isRating = (q.type ?? '').toUpperCase() === 'RATING';
        const scale = (q as any).scale ?? 5;
        const ratingVal = isRating ? Number(this.responses[q.questionId] ?? 0) : 0;
        const starsHtml = isRating
          ? Array.from({ length: scale }, (_, i) =>
              `<span style="color:${i < ratingVal ? '#4A90E2' : '#DEDEDE'};font-size:1.1rem">★</span>`
            ).join('') + ` <span style="font-size:0.8rem;color:#626262;font-weight:600">${ratingVal}/${scale}</span>`
          : '';
        qHtml += `<div class="preview-qa">
          <div class="preview-q">${num++}. ${escapeHtml(q.text)}</div>
          <div class="preview-a${ans === '—' ? ' empty' : ''}">${isRating ? starsHtml : escapeHtml(ans)}</div>
        </div>`;
      }
    }

    // Golden review sections if available
    let goldenHtml = '';
    if (this.parsedGoldenReview) {
      const g = this.parsedGoldenReview;
      const sec = (label: string, val: string) => val ?
        `<div class="preview-qa"><div class="preview-q">${label}</div><div class="preview-a">${escapeHtml(val)}</div></div>` : '';
      goldenHtml = `
        <div class="preview-block golden-block" style="margin-top:16px">
          <div class="preview-block-title" style="color:#b45309;background:#fffbeb;border-color:#fde68a">
            ⚡ AI-Generated Narrative Review
          </div>
          ${sec(`Technology / Core Skills${g.technologyRating ? ` &nbsp;<span style="font-weight:400;color:#92400e">Rating: ${escapeHtml(g.technologyRating)} / 5</span>` : ''}`, g.technologyComments)}
          ${sec(`Consulting${g.consultingRating ? ` &nbsp;<span style="font-weight:400;color:#92400e">Rating: ${escapeHtml(g.consultingRating)} / 5</span>` : ''}`, g.consultingComments)}
          ${sec('Company Values', g.companyValuesComments)}
          ${sec('Development Opportunities for the Year Ahead', g.developmentOpportunities)}
          ${sec('Other Comments', g.otherComments)}
          ${g.overallRating ? `<div class="preview-rating">
            <span class="rating-label">Overall Rating</span>
            <span class="rating-value">${escapeHtml(g.overallRating)} / 5</span>
          </div>` : ''}
        </div>`;
    }

    const period = `${this.milestoneStartDate || this.cycle?.startDate || ''} – ${this.milestoneEndDate || this.cycle?.dueDate || ''}`;

    return `
      <div class="preview-doc-header">
        <div class="preview-doc-top-row">
          <div class="preview-doc-badge">MANAGER REVIEW</div>
          <div class="preview-doc-employee">${escapeHtml(this.employeeName)}</div>
        </div>
        <div class="preview-doc-title">${escapeHtml(this.cycle?.cycleTitle ?? '')}</div>
        <div class="preview-doc-meta">
          <span>${escapeHtml(period)}</span>
          <span class="meta-dot">·</span>
          <span>${escapeHtml(this.review?.status ?? '')}</span>
        </div>
      </div>
      <div class="preview-block" style="break-before: avoid; page-break-before: avoid;">
        <div class="preview-block-title">Manager Responses</div>
        ${qHtml}
      </div>
      ${goldenHtml}
    `;
  }

  private closePrintWindow(): void {
    const el = document.getElementById('__print_iframe__');
    if (el) el.remove();
  }

  exportToPdf(): void {
    // Build directly from data — no need for preview modal to be open
    if (!this.groupedQuestions.length) return;

    // Build filename
    const name     = this.employeeName.replace(/\s+/g, '_') || 'Employee';
    const cycleStr = (this.cycle?.cycleTitle ?? 'Review').replace(/\s+/g, '_').replace(/[^a-zA-Z0-9_]/g, '');
    const filename = `${name}_Manager_Review_${cycleStr}`;

    // Use a hidden iframe — no new tab, just the print dialog
    const existing = document.getElementById('__print_iframe__');
    if (existing) existing.remove();

    const iframe = document.createElement('iframe');
    iframe.id = '__print_iframe__';
    iframe.style.cssText = 'position:fixed;width:0;height:0;border:none;left:-9999px;top:-9999px;';
    iframe.setAttribute('sandbox', 'allow-same-origin allow-modals');
    document.body.appendChild(iframe);

    const win = iframe.contentWindow;
    if (!win) return;

    // Temporarily set page title — Chrome uses this as the PDF filename
    const originalTitle = document.title;
    document.title = filename;

    win.document.write(`
      <!DOCTYPE html>
      <html>
      <head>
        <meta charset="utf-8">
        <title>${escapeHtml(filename)}</title>
        <style>
          @page { size: A4 portrait; margin: 16mm 18mm; }
          * { box-sizing: border-box; margin: 0; padding: 0; }
          body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
                 color: #2E2E2E; background: #fff; padding: 32px; }
          .preview-doc-header {
            background: linear-gradient(135deg, #4A90E2 0%, #2465AA 100%);
            color: #fff; padding: 20px 24px; border-radius: 10px; margin-bottom: 20px;
            -webkit-print-color-adjust: exact; print-color-adjust: exact;
          }
          .preview-doc-top-row {
            display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px;
          }
          .preview-doc-badge {
            font-size: 0.65rem; font-weight: 700; letter-spacing: 0.1em;
            text-transform: uppercase; opacity: 0.85;
          }
          .preview-doc-employee {
            display: flex; align-items: center; gap: 5px;
            font-size: 0.88rem; font-weight: 700; opacity: 0.95;
          }
          .preview-doc-title  { font-size: 1.3rem; font-weight: 700; margin-bottom: 6px; }
          .preview-doc-meta   { font-size: 0.8rem; opacity: 0.85; display: flex; gap: 8px; }
          .meta-dot           { opacity: 0.5; }
          .preview-block      { border: 1px solid #DEDEDE; border-radius: 10px;
                                 overflow: hidden; margin-bottom: 20px; }
          .golden-block       { border-color: #B8D3F2; }
          .preview-block-title { font-size: 0.72rem; font-weight: 700; text-transform: uppercase;
                                  letter-spacing: 0.06em; color: #626262; padding: 12px 18px;
                                  background: #F4F4F4; border-bottom: 1px solid #DEDEDE; }
          .golden-block .preview-block-title { color: #b45309; background: #fffbeb;
                                               border-color: #fde68a; }
          .preview-cat-label  { font-size: 0.7rem; font-weight: 700; color: #4A90E2;
                                 text-transform: uppercase; letter-spacing: 0.05em; padding: 10px 18px 0; }
          .preview-qa         { padding: 12px 18px; border-bottom: 1px solid #F4F4F4; }
          .preview-qa:last-child { border-bottom: none; }
          .preview-q          { font-size: 0.8rem; font-weight: 600; color: #626262; margin-bottom: 5px; }
          .preview-a          { font-size: 0.88rem; line-height: 1.7; white-space: pre-wrap; }
          .preview-a.empty    { color: #94a3b8; font-style: italic; }
          .preview-rating     { display: flex; justify-content: space-between; align-items: center;
                                 padding: 14px 18px; background: #fffbeb; border-top: 1px solid #fde68a; }
          .rating-label       { font-size: 0.82rem; font-weight: 600; color: #92400e; }
          .rating-value       { font-size: 1.2rem; font-weight: 800; color: #4A90E2; }
          @media print {
            body { padding: 0; font-size: 10pt; }
            .preview-doc-header {
              background: linear-gradient(135deg, #4A90E2 0%, #2465AA 100%) !important;
              -webkit-print-color-adjust: exact;
              print-color-adjust: exact;
              padding: 14px 18px;
              border-radius: 8px;
              margin-bottom: 12px;
              break-after: avoid;
              page-break-after: avoid;
            }
            .preview-doc-top-row { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
            .preview-doc-badge   { font-size: 7pt; font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; opacity: 0.85; }
            .preview-doc-employee{ font-size: 9pt; font-weight: 700; display: flex; align-items: center; gap: 4px; }
            .preview-doc-title   { font-size: 14pt; font-weight: 700; margin: 0 0 4px; }
            .preview-doc-meta    { font-size: 8pt; opacity: 0.85; display: flex; gap: 6px; }
            .preview-block       { break-before: avoid; page-break-before: avoid; margin-bottom: 10px; }
            .preview-block-title { padding: 6px 14px; font-size: 7pt; }
            .preview-qa          { padding: 5px 14px; }
            .preview-q           { font-size: 8pt; margin-bottom: 2px; }
            .preview-a           { font-size: 9pt; line-height: 1.5; }
          }
        </style>
      </head>
      <body>
        ${this.buildPrintHtml()}
      </body>
      </html>
    `);
    win.document.close();
    win.onafterprint = () => { iframe.remove(); document.title = originalTitle; };
    setTimeout(() => { win.focus(); win.print(); }, 300);
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.clearStreaming();
    this.closePrintWindow();
    // Cooldown intervals aren't tied to destroy$ — clear them explicitly so they
    // stop ticking against a detached component after navigation.
    Object.values(this.cooldownTimers).forEach(clearInterval);
    this.cooldownTimers = {};
  }

  // ── Auto-save ─────────────────────────────────────────────────────────────

  get autoSavedAgo(): string {
    if (!this.autoSavedAt) return '';
    const secs = Math.floor((Date.now() - this.autoSavedAt.getTime()) / 1000);
    if (secs < 10) return 'just now';
    if (secs < 60) return `${secs}s ago`;
    return `${Math.floor(secs / 60)}m ago`;
  }

  /** Called on every answer change — triggers debounced auto-save. */
  onAnswerChange(): void {
    if (this.viewOnly || !this.review) return;
    this.autoSaveStatus = '';
    this.autoSave$.next();
  }

  private performAutoSave(): void {
    if (!this.review || this.viewOnly) return;
    // For new reviews (no reviewId yet), backend requires cycleId and subjectUserId
    if (!this.review.reviewId && (!this.review.cycleId || !this.review.subjectUserId)) return;
    this.autoSaveStatus   = 'saving';
    this.review.answers   = this.buildAnswers();
    this.review.status    = this.review.status === 'NOT_STARTED' ? 'DRAFT' : this.review.status;

    this.createThenUpdate('DRAFT')
      .pipe(catchError(() => of(null)))
      .subscribe(saved => {
        if (saved) {
          this.autoSaveStatus = 'saved';
          this.autoSavedAt = new Date();
          setTimeout(() => { this.autoSaveStatus = ''; }, 3000);
        } else {
          this.autoSaveStatus = 'error';
        }
      });
  }

  /**
   * Plain-English explanation for why the submit button is disabled. Reads the
   * gate response if present, otherwise generic fallback. Renders inside the
   * submit button's title attr so hovering shows the user what's missing.
   */
  isSelfReviewDone(): boolean {
    const s = String(this.employeeReview?.status ?? '').toUpperCase();
    if (s === 'COMPLETED' || s === 'PUBLISHED') return true;
    if (this.gateStatus) {
      const reasons = this.gateStatus.reasons ?? [];
      return !reasons.some((r: any) => r.type === 'self_missing' || r.type === 'self_incomplete');
    }
    return false;
  }

  gateBlockReason(): string {
    if (this.canSubmit) return '';
    if (this.gateLoading) return 'Checking submit eligibility…';
    if (!this.gateStatus) return 'Submit is currently disabled.';
    const reasons = this.gateStatus.reasons ?? [];
    if (reasons.some(r => r.type === 'self_missing'))      return 'Employee has not started their self-review.';
    if (reasons.some(r => r.type === 'self_incomplete'))   return 'Employee has not yet submitted their self-review.';
    if (reasons.some(r => r.type === 'peer360_unassigned'))return 'Assign 360 reviewers before submitting.';
    const incomplete = reasons.find(r => r.type === 'peer360_incomplete');
    if (incomplete) return `${incomplete.missingCount ?? 'some'} 360 reviewer(s) have not completed yet.`;
    return 'Submit is currently disabled.';
  }

  // ── Per-question suggestion ───────────────────────────────────────────────

  private wordCount(questionId: string): number {
    const val = this.responses[questionId];
    if (!val || typeof val !== 'string') return 0;
    return val.trim().split(/\s+/).filter(w => w.length > 0).length;
  }

  /** True once manager has typed 10+ words — enables the suggestion button. */
  canRequestSuggestion(questionId: string): boolean {
    // Disable if employee hasn't submitted their self-review
    if (!this.isSelfReviewDone()) return false;
    return this.wordCount(questionId) >= this.MIN_WORDS;
  }

  /** True when an AI suggestion is currently displayed for this question. */
  hasSuggestion(questionId: string): boolean {
    return !!(this.questionSuggestions[questionId]?.trim());
  }

  /** True after manager has dismissed the AI suggestion — show "Good Answer". */
  isDismissed(questionId: string): boolean {
    return this.dismissedSuggestions.has(questionId);
  }

  /** Returns true when the AI response is a positive "looks good" confirmation. */
  isGoodAnswer(suggestion: string): boolean {
    if (!suggestion) return false;
    const s = suggestion.toLowerCase().replace(/"/g, '').trim();
    // Only flag as "good answer" when the response is a SHORT meta-comment from Gemini
    // (< 160 chars). Real suggestions are longer and contain the same words in context.
    if (s.length >= 160) return false;
    return s.includes('well-structured') || s.includes('looks great') ||
           s.includes('no changes needed') || s.includes('good answer') ||
           s.includes('meets the standard') || s.includes('meets standard') ||
           s.includes('answer looks strong') || s.includes('looks strong') ||
           s.includes('answer is strong') || s.includes('well written') ||
           s.includes('covers the key') ||
           (s.includes('good') && s.includes('standard')) ||
           (s.includes('great') && s.includes('example'));
  }

  /** True when answer is solid enough — button hides, no more suggestions needed. */
  isAnswerSubstantial(questionId: string): boolean {
    return this.wordCount(questionId) >= this.SUBSTANTIAL_WORDS;
  }


  /** Called on every keystroke — triggers autosave debounce only. No AI calls. */
  onTextAnswerChange(questionId: string): void {
    // Reset "Good Answer" state when user edits — their answer changed so AI needs to re-evaluate
    this.dismissedSuggestions.delete(questionId);
    this.questionSuggestions[questionId] = '';
    delete this.questionSuggestionCitations[questionId];
    // Cached golden is now stale (it was composed from the previous draft).
    // Drop it so the next Preview open generates fresh; manager can also
    // explicitly hit Regenerate from inside the modal.
    this.parsedGoldenReview = null;
    this.onAnswerChange();
  }

  /** Calls the backend to generate an AI suggestion for a specific question. */
  generateSuggestion(q: Question): void {
    if (this.questionSuggestionLoading[q.questionId]) return;

    this.questionSuggestionLoading[q.questionId] = true;
    this.questionSuggestionError[q.questionId]   = '';
    this.questionSuggestions[q.questionId]        = '';

    const currentAnswer = this.responses[q.questionId];
    const employeeAnswers = (this.employeeReview?.answers ?? []).map(a => {
      const eq = this.employeeQuestions.find(x => x.questionId === a.questionId);
      return {
        questionId: a.questionId,
        questionText: eq?.text ?? a.questionId,
        response: typeof a.response === 'string' ? a.response : String(a.response ?? ''),
      };
    }).filter(a => a.response.trim().length > 0);

    const callSuggest = (reviewId: string | undefined) => {
      // Backend requires a reviewId path param; if we don't have one yet the
      // outer branch saves a draft first and re-enters with the new id.
      if (!reviewId) {
        this.questionSuggestionError[q.questionId] = 'Save a draft first.';
        this.questionSuggestionLoading[q.questionId] = false;
        return;
      }
      // G3 async: enqueue → poll → render. The status endpoint returns the
      // SuggestionResult shape under `result`; pollManagerQuestionSuggest
      // casts and yields it to us as QuestionSuggestResponse.
      this.aiReviewService.startManagerQuestionSuggest(reviewId, {
        questionId: q.questionId,
        questionText: q.text,
        managerCurrentAnswer: currentAnswer?.toString().trim().length > 0 ? currentAnswer.toString() : '',
        discipline: this.subjectDiscipline,
        seniority: this.subjectSeniority,
        employeeReview: {
          answers: employeeAnswers,
          overallComment: this.employeeReview?.overallComment ?? undefined,
          overallRating: this.employeeReview?.overallRating ?? undefined,
        },
        peerDigest: this.peer360DigestState === 'ready' && this.peer360Digest
          ? {
              themes: (this.peer360Digest.themes ?? []).map(t => `${t.theme}${t.summary ? `: ${t.summary}` : ''}`),
              consensus: (this.peer360Digest.consensus ?? []).map(c => c.point),
              dissent: (this.peer360Digest.dissent ?? []).map(d => d.point),
              overallSentiment: this.peer360Digest.overallSentiment,
            }
          : undefined,
      }).pipe(
        switchMap(job => this.aiReviewService.pollManagerQuestionSuggest(job.jobId)),
        takeUntil(this.destroy$),
        catchError(err => of({ error: err })),
      ).subscribe(res => this.handleSuggestionResponse(q, res));
    };

    // If review hasn't been saved yet, save immediately to get a reviewId first
    if (!this.review?.reviewId) {
      this.review!.answers = this.buildAnswers();
      this.review!.status  = 'DRAFT';
      this.createThenUpdate('DRAFT')
        .pipe(catchError(() => of(null)))
        .subscribe(() => callSuggest(this.review?.reviewId));
      return;
    }

    callSuggest(this.review.reviewId);
  }

  private handleSuggestionResponse(
    q: Question,
    res: QuestionSuggestResponse | { error: HttpErrorResponse | { status?: number } } | unknown,
  ): void {
    this.questionSuggestionLoading[q.questionId] = false;

    // Narrow once: { error } when the catchError above mapped a failed call.
    const errored = res as { error?: { status?: number } };
    if (errored?.error?.status === 429) {
      this.startCooldown(q.questionId, 60);
      return;
    }

    const suggestion = (res as Partial<QuestionSuggestResponse>)?.suggestion;
    const isGood     = (res as { isGoodAnswer?: boolean })?.isGoodAnswer === true;
    if (suggestion) {
      if (isGood) {
        this.dismissedSuggestions.add(q.questionId);
      } else {
        this.questionSuggestions[q.questionId] = suggestion;
      }
      this.questionSuggestionCitations[q.questionId] =
        (res as Partial<QuestionSuggestResponse>)?.citations ?? {};
    } else {
      this.questionSuggestionError[q.questionId] = 'Could not generate suggestion. Please try again.';
    }
  }

  cooldownRemaining: Record<string, number> = {};
  private cooldownTimers: Record<string, ReturnType<typeof setInterval>> = {};

  private startCooldown(questionId: string, seconds: number): void {
    this.cooldownRemaining[questionId] = seconds;
    // Replace any existing timer for this question so a re-trigger never leaks a
    // second interval, and track the handle so ngOnDestroy can clear it.
    clearInterval(this.cooldownTimers[questionId]);
    this.cooldownTimers[questionId] = setInterval(() => {
      this.cooldownRemaining[questionId]--;
      if (this.cooldownRemaining[questionId] <= 0) {
        clearInterval(this.cooldownTimers[questionId]);
        delete this.cooldownTimers[questionId];
        delete this.cooldownRemaining[questionId];
      }
    }, 1000);
  }

  dismissSuggestion(questionId: string): void {
    delete this.questionSuggestions[questionId];
    delete this.questionSuggestionCitations[questionId];
    delete this.questionSuggestionError[questionId];
    this.dismissedSuggestions.add(questionId);  // mark as "seen and done"
  }

  suggestionSegments(questionId: string): { text: string; cite: SuggestionCitation | null; key: string | null }[] {
    const text = this.questionSuggestions[questionId];
    if (!text) return [];
    const map = this.questionSuggestionCitations[questionId] || {};
    const out: { text: string; cite: SuggestionCitation | null; key: string | null }[] = [];
    const re = /\[(c\d+)\]/g;
    let last = 0;
    let m: RegExpExecArray | null;
    while ((m = re.exec(text)) !== null) {
      if (m.index > last) out.push({ text: text.slice(last, m.index), cite: null, key: null });
      const key = m[1];
      out.push({ text: key, cite: map[key] ?? null, key });
      last = m.index + m[0].length;
    }
    if (last < text.length) out.push({ text: text.slice(last), cite: null, key: null });
    return out;
  }

  jumpToSuggestionCitation(cite: SuggestionCitation | null, key: string | null): void {
    if (!cite) return;
    this.activeSuggestionCitationKey = key;
    this.highlightedDigestCitation = null;
    if (cite.source === 'peer_digest') {
      this.leftTab = 'peer360_digest';
      this.highlightedDigestCitation = cite;
      this.cdr.detectChanges();
      setTimeout(() => {
        const el = document.querySelector('.digest-item.cited') as HTMLElement | null;
        if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' });
      }, 0);
      return;
    }
    const resolvedQuestionId = (cite.source === 'self')
      ? this.resolveSelfCitationQuestionId(cite)
      : cite.questionId;
    this.jumpToCitation({
      source: (cite.source as 'self' | 'peer_360'),
      reviewId: cite.reviewId ?? '',
      questionId: resolvedQuestionId,
      quote: cite.quote,
      authorUserId: cite.authorUserId,
    }, null);
  }

  private normalizeText(s: string | null | undefined): string {
    return (s ?? '')
      .toLowerCase()
      .replace(/^q\s*:\s*/, '')
      .replace(/[^a-z0-9]+/g, ' ')
      .trim();
  }

  private resolveSelfCitationQuestionId(cite: SuggestionCitation): string {
    const raw = (cite.questionId ?? '').trim();
    if (!raw) return raw;
    const employeeAnswerIds = new Set((this.employeeReview?.answers ?? []).map((a: any) => a.questionId));
    if (employeeAnswerIds.has(raw)) return raw;

    const rawNorm = this.normalizeText(raw);
    const byQuestionText = this.employeeQuestions.find(q => this.normalizeText(q.text) === rawNorm);
    if (byQuestionText?.questionId) return byQuestionText.questionId;

    const sectionNorm = this.normalizeText(cite.section ?? '');
    if (sectionNorm) {
      const bySection = this.employeeQuestions.find(q => this.normalizeText(q.text) === sectionNorm);
      if (bySection?.questionId) return bySection.questionId;
    }
    return raw;
  }

  isDigestCitationMatch(text: string | null | undefined): boolean {
    if (!this.highlightedDigestCitation || !text) return false;
    const quote = this.highlightedDigestCitation.quote?.toLowerCase() ?? '';
    const section = this.highlightedDigestCitation.section?.toLowerCase() ?? '';
    const t = text.toLowerCase();
    return (!!quote && t.includes(quote)) || (!!section && t.includes(section));
  }

  // ── Preview modal ─────────────────────────────────────────────────────────

  /** Loading state for the in-flight MANAGER_GOLDEN job. */
  goldenLoading = false;
  /** Terminal failure of the MANAGER_GOLDEN job. Kept separate from
   *  insightsFailed so the right "AI Review" tab and the left "AI Insights"
   *  tab never reflect each other's job state (they are independent jobs). */
  goldenFailed = false;

  /**
   * Open the preview modal. Caches {@link parsedGoldenReview} in component
   * state and persists managerGoldenJobId on the Review row via
   * {@link persistGoldenJobId}, so re-opening the tab or page reloading does
   * not re-fire Gemini. Use {@link regenerateGolden} to force a fresh run
   * after editing answers (ADR-6).
   */
  openPreview(): void {
    this.showPreview = true;
    if (!this.parsedGoldenReview && !this.goldenLoading) {
      this.loadOrFetchGolden();
    }
  }

  /**
   * Explicit refresh — clears the cached golden and re-fires Gemini. Use this
   * after editing draft answers so the golden reflects the latest content.
   */
  regenerateGolden(): void {
    this.parsedGoldenReview = null;
    this.fetchManagerGolden();
  }

  /**
   * Poll an existing MANAGER_GOLDEN job (cached on the manager review row).
   * On COMPLETED → render. On FAILED or polling error → surface the failed
   * state so the manager can click "Retry Generation" manually. No auto
   * Gemini re-fire on the error path (manual-button rule).
   */
  private pollCachedGolden(jobId: string): void {
    this.goldenLoading = true;
    this.goldenFailed = false;
    this.clearStreaming('golden');
    this.aiReviewService.pollManagerGolden(jobId)
      .pipe(takeUntil(this.destroy$), catchError(() => of(null)))
      .subscribe(statusResp => {
        if (!statusResp) {
          this.goldenLoading = false;
          this.goldenFailed = true;
          this.clearStreaming('golden');
          return;
        }
        // Streaming preview while PROCESSING — push partialText through the
        // rAF-smoothed buffer keyed to the 'golden' context so it lands in
        // the AI Review tab only.
        if (statusResp.status === 'PROCESSING' || statusResp.status === 'PENDING') {
          if (statusResp.partialText) {
            this.setStreamingTarget(statusResp.partialText, 'golden');
          }
          return;
        }
        // Terminal COMPLETED — swap to the structured render.
        this.goldenLoading = false;
        if (statusResp.result) {
          this.parsedGoldenReview = statusResp.result;
        }
        this.clearStreaming('golden');
      });
  }

  /**
   * Enter edit mode on the AI Narrative tab. Snapshots the current parsed
   * golden review into `goldenEditBuffer` (deep clone — direct mutation
   * would surprise the user if they clicked Cancel). No backend traffic
   * happens here; Save / Cancel govern persistence.
   */
  enterGoldenEditMode(): void {
    if (!this.parsedGoldenReview || this.goldenLoading) return;
    // Structured-clone for a clean deep copy. Falls back to JSON round-trip
    // on browsers/test environments without structuredClone (e.g. jsdom).
    this.goldenEditBuffer = typeof structuredClone === 'function'
      ? structuredClone(this.parsedGoldenReview)
      : JSON.parse(JSON.stringify(this.parsedGoldenReview));
    this.goldenEditMode = true;
    this.goldenEditError = '';
  }

  /**
   * Persist the edited golden review to the AiJobs row via the new PUT
   * endpoint. On success replaces parsedGoldenReview with the server's
   * canonical response (single source of truth) and exits edit mode. On
   * failure surfaces the message in `goldenEditError`; buffer is preserved
   * so the manager can retry without losing their edits.
   */
  saveGoldenEdit(): void {
    const jobId = this.review?.managerGoldenJobId;
    if (!jobId || !this.goldenEditBuffer || this.goldenSaving) return;
    this.goldenSaving = true;
    this.goldenEditError = '';
    this.aiReviewService.updateManagerGoldenResult(jobId, this.goldenEditBuffer)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: saved => {
          this.parsedGoldenReview = saved;
          this.goldenEditBuffer = null;
          this.goldenEditMode = false;
          this.goldenSaving = false;
        },
        error: err => {
          // Server's 400/409 message comes through err.error.message — fall back
          // to err.message for network failures.
          this.goldenEditError = (err?.error?.message as string)
                              ?? (err?.message as string)
                              ?? 'Could not save changes';
          this.goldenSaving = false;
        },
      });
  }

  /**
   * Drop the edit buffer and exit edit mode without writing to the server.
   * parsedGoldenReview is untouched.
   */
  cancelGoldenEdit(): void {
    this.goldenEditBuffer = null;
    this.goldenEditMode = false;
    this.goldenEditError = '';
  }

  /**
   * Check review.managerGoldenJobId for an existing golden job and poll it.
   * If no jobId, show the empty state so the user can click "Generate AI
   * Narrative". Save Draft never touches this — it only saves review answers.
   */
  private loadOrFetchGolden(): void {
    const cachedJobId = this.review?.managerGoldenJobId;
    if (cachedJobId) {
      this.pollCachedGolden(cachedJobId);
    }
    // No jobId → empty state shown, user clicks Generate
  }

  /**
   * True once the manager has entered at least one answer. The AI narrative is
   * generated *from* the manager's review, so generation is gated on this — an
   * empty review would yield a meaningless narrative, and (pre-fix) the
   * "Generate AI Narrative" button silently no-op'd because an unsaved review
   * has no reviewId. The button binds [disabled] to its negation;
   * fetchManagerGolden re-checks it as defence in depth.
   */
  get hasManagerReviewContent(): boolean {
    return this.questions.some(q => {
      const v = this.responses[q.questionId];
      if (Array.isArray(v)) return v.length > 0;
      if (typeof v === 'number') return v > 0;
      return typeof v === 'string' && v.trim().length > 0;
    });
  }

  fetchManagerGolden(): void {
    // The AI narrative summarises the manager's review — the review is mandatory,
    // so block generation on an empty one. The "Generate" button is disabled in
    // this state too; this guard is defence in depth.
    if (!this.hasManagerReviewContent) return;

    const reviewId = this.review?.reviewId;
    if (reviewId) {
      this.runManagerGolden(reviewId);
      return;
    }

    // Content exists but the review row isn't persisted yet (the 2.5s auto-save
    // debounce hasn't fired). Save a draft to mint a reviewId, THEN generate —
    // rather than the old bare `if (!reviewId) return`, which made the button
    // silently do nothing ("no error, no nothing").
    this.goldenLoading = true;
    this.goldenFailed  = false;
    this.clearStreaming('golden');
    this.review!.answers = this.buildAnswers();
    this.review!.status  = 'DRAFT';
    this.createThenUpdate('DRAFT').pipe(
      takeUntil(this.destroy$),
      catchError(() => of(null)),
    ).subscribe(saved => {
      const newReviewId = this.review?.reviewId;
      if (saved && newReviewId) {
        this.runManagerGolden(newReviewId);
      } else {
        // Draft save failed — surface the failed state so the manager can retry
        // instead of leaving the spinner hanging.
        this.goldenLoading = false;
        this.goldenFailed  = true;
        this.clearStreaming('golden');
      }
    });
  }

  /**
   * Enqueue a MANAGER_GOLDEN job for an already-persisted review, persist the
   * jobId so a later visit re-polls instead of re-firing Gemini, then poll to
   * terminal — streaming partialText into the AI Review tab on the way.
   */
  private runManagerGolden(reviewId: string): void {
    this.goldenLoading = true;
    this.goldenFailed = false;
    this.clearStreaming('golden');
    let newGoldenJobId: string | null = null;
    this.aiReviewService.startManagerGolden(reviewId).pipe(
      switchMap(job => {
        // Capture the jobId; the DURABLE persist happens on COMPLETED (below),
        // NOT here at job-start. Persisting at start wrote a FAILED job's id as
        // the pointer and orphaned the result on refresh — same bug class as
        // retryEmployeeInsights. pollManagerGolden throws on FAILED, so the
        // terminal branch below is only reached on a real COMPLETED.
        newGoldenJobId = job.jobId;
        return this.aiReviewService.pollManagerGolden(job.jobId);
      }),
      takeUntil(this.destroy$),
      catchError(() => of(null)),
    ).subscribe(statusResp => {
      if (!statusResp) {
        this.goldenLoading = false;
        this.goldenFailed = true;
        this.clearStreaming('golden');
        return;
      }
      if (statusResp.status === 'PROCESSING' || statusResp.status === 'PENDING') {
        if (statusResp.partialText) {
          this.setStreamingTarget(statusResp.partialText, 'golden');
        }
        return;
      }
      this.goldenLoading = false;
      if (statusResp.result) this.parsedGoldenReview = statusResp.result;
      this.clearStreaming('golden');
      // Durable repoint on COMPLETED so a later visit / refresh re-polls THIS
      // completed job (version-safe — persistGoldenJobId self-heals a 409).
      if (newGoldenJobId && reviewId) {
        this.review = { ...this.review!, managerGoldenJobId: newGoldenJobId };
        this.persistGoldenJobId(reviewId, newGoldenJobId);
      }
    });
  }

  /**
   * PUT /reviews/{id} with the new managerGoldenJobId so the next tab open
   * polls this job instead of re-firing Gemini. Mirrors persistInsightsJobId —
   * best-effort, swallows errors since local state is already updated.
   *
   * Syncs the server-bumped `version` back so a follow-up regenerate carries
   * the latest version — otherwise the next PUT 409s and silently fails to
   * persist the new jobId.
   */
  private persistGoldenJobId(reviewId: string, jobId: string): void {
    // Orphan-fix parity with persistInsightsJobId: re-fetch the current version
    // and retry once on a stale-version 409 instead of swallowing it.
    const put = (version: number | undefined) =>
      this.employeeService.updateReview(
        { reviewId, version, managerGoldenJobId: jobId } as Partial<Review> & { reviewId: string });

    put(this.review?.version)
      .pipe(
        catchError(() =>
          this.employeeService.getReviewsBySubject(this.review?.subjectUserId ?? this.subjectUserId ?? '')
            .pipe(
              switchMap(reviews => put(reviews.find(r => r.reviewId === reviewId)?.version)),
              catchError(() => {
                console.warn(`[golden] failed to persist managerGoldenJobId=${jobId} on review ${reviewId}; result may not survive refresh`);
                return of(null);
              }),
            )),
        takeUntil(this.destroy$),
      )
      .subscribe(saved => {
        if (!saved) return;
        this.review = {
          ...this.review!,
          managerGoldenJobId: (saved as any)?.managerGoldenJobId ?? jobId,
          version: (saved as any)?.version ?? this.review?.version,
        };
      });
  }

  getResponseDisplay(questionId: string): string {
    const val = this.responses[questionId];
    if (val === null || val === undefined || val === '') return '—';
    if (Array.isArray(val)) return val.length ? val.join(', ') : '—';
    return String(val);
  }
}
