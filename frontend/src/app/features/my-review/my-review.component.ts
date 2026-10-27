import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
import { forkJoin, of, Observable } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { AuthService } from '../../core/auth/auth.service';
import { EmployeeService, ReviewCycle, CycleMilestone } from '../../services/employees.services';
import { Review, ReviewType, Question, Answer } from '../../models/review.model';
import { SkeletonComponent } from '../../shared/skeleton/skeleton.component';
import { escapeHtml } from '../../core/utils/escape-html';

interface ReviewActionRow {
  reviewType: ReviewType;
  title: string;
  subtitle: string;
  iconClass: 'self' | 'manager' | 'peer360';
  milestone: CycleMilestone;
  status: 'NOT_STARTED' | 'DRAFT' | 'IN_PROGRESS' | 'COMPLETED' | 'PUBLISHED' | 'PENDING' | 'LOCKED';
  daysRemaining: number;
  existingReview?: Review;
  /** For PEER_360 rows the caller is filling — the subject they're writing about. */
  peer360SubjectName?: string;
}

interface ReviewAnswerItem {
  questionId: string;
  answer: any;
}

interface HistoryReviewRecord extends Review {
  submittedTime?: string | null;
  submittedAt?: string | null;
  updatedAt?: string | null;
  createdAt?: string | null;
  employeeReview?: ReviewAnswerItem[];
  managerReview?: ReviewAnswerItem[];
  adminReview?: ReviewAnswerItem[];
  subjectUserId?: string;
  authorUserId?: string;
  managerId?: string;
}

interface ReviewHistoryItem {
  review: HistoryReviewRecord;
  cycle?: ReviewCycle;
  selfReviewStatus: string;
  selfReviewTime?: string | null;
  managerReviewStatus: string;
  managerReviewTime?: string | null;
  managerReview?: HistoryReviewRecord | null;
}

@Component({
  selector: 'app-my-review',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule, SkeletonComponent],
  templateUrl: './my-review.component.html',
  styleUrls: ['./my-review.component.css'],
})
export class MyReviewComponent implements OnInit {
  private employeeService = inject(EmployeeService);
  private authService = inject(AuthService);
  private router = inject(Router);

  /** All ACTIVE cycles, sorted most-recently-updated first — drives the cycle picker. */
  availableActiveCycles: ReviewCycle[] = [];
  /** The cycle the page is currently rendering. Bound to the picker. */
  selectedCycleId: string = '';
  activeCycle: ReviewCycle | null = null;
  activeCycleRows: { cycle: ReviewCycle; rows: ReviewActionRow[] }[] = [];
  actionRows: ReviewActionRow[] = [];
  reviewHistory: ReviewHistoryItem[] = [];
  loading = true;

  selectedHistory: ReviewHistoryItem | null = null;
  showHistoryDetails = false;
  selectedHistoryType: 'self' | 'manager' = 'self';
  selectedModalReview: HistoryReviewRecord | null = null;
  selectedModalLabel = 'Self Review';

  toastMessage = '';
  toastVisible = false;
  private toastTimer: any;

  showToast(message: string): void {
    clearTimeout(this.toastTimer);
    this.toastMessage = message;
    this.toastVisible = true;
    this.toastTimer = setTimeout(() => { this.toastVisible = false; }, 3000);
  }

  pendingPopupMessage = '';
  pendingPopupVisible = false;

  showPendingPopup(message: string): void {
    this.pendingPopupMessage = message;
    this.pendingPopupVisible = true;
  }

  closePendingPopup(): void {
    this.pendingPopupVisible = false;
  }

  /** Cached for re-running buildActionRows on cycle switch without re-fetching. */
  private allReviews: Review[] = [];
  /**
   * Reviews where the caller is the SUBJECT (from getMySubjectReviews) — the only
   * feed that carries the MANAGER review written ABOUT them (it's authored by the
   * manager, so it never appears in the author-indexed assigned-to-me feed).
   * Cached so a cycle switch can re-surface the published manager row without refetching.
   */
  private mySubjectReviews: Review[] = [];
  private currentUserId = '';
  private currentUserRole = 'EMPLOYEE';
  /** userId → "First Last" lookup for the peer360Queue subject column. */
  private userNames = new Map<string, string>();

  ngOnInit(): void {
    this.currentUserId = this.authService.getTokenPayload()?.sub ?? '';

    this.currentUserRole = this.authService.isAdmin()
      ? 'ADMIN'
      : this.authService.getUserEmployeeType() === 'MANAGER'
        ? 'MANAGER'
        : 'EMPLOYEE';

    forkJoin({
      cycles: this.employeeService.getCycles().pipe(catchError(() => of([]))),
      reviews: this.employeeService.getMyReviews(this.currentUserId).pipe(catchError(() => of([]))),
      subjectReviews: this.employeeService.getMySubjectReviews().pipe(catchError(() => of([]))),
    }).subscribe(({ cycles, reviews, subjectReviews }) => {
      this.allReviews = reviews ?? [];
      this.mySubjectReviews = (subjectReviews as Review[]) ?? [];

      // All ACTIVE cycles drive the picker. Sort by updatedAt desc so the most recently
      // activated cycle is the default selection.
      this.availableActiveCycles = (cycles ?? [])
        .filter(c => c.status === 'ACTIVE')
        .sort((a, b) =>
          (b.updatedAt ?? b.createdAt ?? '').localeCompare(a.updatedAt ?? a.createdAt ?? '')
        );

      this.activeCycle = this.availableActiveCycles[0] ?? null;
      this.selectedCycleId = this.activeCycle?.cycleId ?? '';

      // Resolve peer-360 subject names via the minimal /users/{id}/public projection.
      // We used to pull the entire user directory (/users) for this, which is ADMIN-only
      // and 403'd for EMPLOYEE/MANAGER. The caller only needs names for the peer-360
      // reviews they themselves are authoring, so look those up one-by-one in parallel.
      this.resolvePeer360SubjectNames(this.allReviews).subscribe(() => {
        if (this.activeCycle) {
          this.buildActionRows(this.activeCycle, this.currentUserRole, this.allReviews, this.currentUserId);
        }

        this.buildReviewHistory(this.allReviews as HistoryReviewRecord[], subjectReviews as HistoryReviewRecord[], cycles, this.currentUserId);

        this.loading = false;
      });
    });
  }

  /**
   * Populates {@link userNames} with the display names of every peer-360 subject the
   * current user is assigned to review. Emits once when all lookups resolve (or
   * immediately if there are no peer-360 subjects). Errors per id are swallowed so
   * one bad lookup doesn't break the whole map — those rows fall back to 'Team Member'.
   */
  private resolvePeer360SubjectNames(reviews: Review[]): Observable<void> {
    const subjectIds = new Set<string>();
    for (const r of reviews ?? []) {
      if (r.reviewType !== 'PEER_360') continue;
      const author = (r as any).authorUserId ?? r.userId;
      if (author && author !== this.currentUserId) continue;
      const subjectId =
        (r as any).subjectUserId
        ?? ((r as any).authorUserId ? r.userId : undefined);
      if (subjectId) subjectIds.add(subjectId);
    }

    this.userNames.clear();
    if (subjectIds.size === 0) return of(void 0);

    return forkJoin(
      Array.from(subjectIds).map(id =>
        this.employeeService.getUserPublic(id).pipe(catchError(() => of(null)))
      )
    ).pipe(
      map(users => {
        for (const u of users) {
          if (!u) continue;
          const fullName = `${u.firstName ?? ''} ${u.lastName ?? ''}`.trim();
          if (u.userId && fullName) this.userNames.set(u.userId, fullName);
        }
      })
    );
  }

  /** Picker change handler — re-renders the action rows for the newly-selected cycle. */
  onCycleChange(cycleId: string): void {
    this.selectedCycleId = cycleId;
    this.activeCycle = this.availableActiveCycles.find(c => c.cycleId === cycleId) ?? null;
    this.actionRows = [];
    if (this.activeCycle) {
      this.buildActionRows(this.activeCycle, this.currentUserRole, this.allReviews, this.currentUserId);
    }
  }

  /**
   * PEER_360 rows where the caller is the author of an assignment in the given cycle.
   * Returned as ReviewActionRow entries so they slot into the same table as SELF / MANAGER.
   */
  private buildPeer360Rows(cycle: ReviewCycle, reviews: Review[], currentUserId: string): ReviewActionRow[] {
    return (reviews ?? [])
      .filter(r => {
        if (r.reviewType !== 'PEER_360') return false;
        if (r.cycleId !== cycle.cycleId) return false;
        const author = (r as any).authorUserId ?? r.userId;
        return !author || author === currentUserId;
      })
      .map(r => {
        // subjectUserId is the primary field; fall back to userId when authorUserId
        // confirms the current user is the author (meaning userId = subject)
        const subjectId: string | undefined =
          (r as any).subjectUserId
          ?? ((r as any).authorUserId ? r.userId : undefined);
        const subjectName = this.subjectName(subjectId);
        const milestone =
          cycle.milestones?.find(m => m.templateSnapshot?.templateId === r.templateId) ??
          cycle.milestones?.find(m => (m.name ?? '').toLowerCase().includes('peer')
                                   || (m.name ?? '').toLowerCase().includes('360'))
          ?? cycle.milestones?.[0] as CycleMilestone;
        return {
          reviewType: 'PEER_360' as ReviewType,
          title: `Peer Review - ${subjectName}`,
          subtitle: `Write 360 feedback for ${subjectName}`,
          iconClass: 'peer360' as const,
          milestone,
          status: this.normalizeActionStatus(r.status),
          daysRemaining: this.daysBetween(new Date(), this.parseDate(milestone?.endDate)),
          existingReview: r,
          peer360SubjectName: subjectName,
        };
      });
  }

  /**
   * Resolves a userId to "First Last" using the cached employee directory.
   * Falls back to a generic label so the UI never shows a raw UUID — peer 360 is
   * anonymous to the subject but the *author* always has a real name to attach.
   */
  subjectName(userId: string | undefined): string {
    if (!userId) return 'Team Member';
    return this.userNames.get(userId) ?? this.userNames.get(String(userId)) ?? 'Team Member';
  }

      private buildReviewHistory(
      reviews: HistoryReviewRecord[],
      subjectReviews: HistoryReviewRecord[],
      cycles: ReviewCycle[],
      currentUserId: string
    ): void {
      const activeCycleIds = new Set(
        (cycles ?? []).filter(c => c.status === 'ACTIVE').map(c => c.cycleId)
      );

      const typeOf = (r: any) => String(r?.reviewType ?? '').toUpperCase();

      // Index MANAGER reviews by cycleId (case-insensitive)
      const managerReviewByCycle = new Map<string, HistoryReviewRecord>();
      for (const r of (subjectReviews ?? [])) {
        if (typeOf(r) === 'MANAGER' && r.cycleId) {
          managerReviewByCycle.set(r.cycleId, r);
        }
      }

      // History base = SELF reviews only (one row per cycle)
      const selfReviews = (subjectReviews ?? []).filter(r => typeOf(r) === 'SELF');
      const historyBase = selfReviews.length > 0
        ? selfReviews
        : (reviews ?? []).filter(r => typeOf(r) === 'SELF');

      this.reviewHistory = historyBase
        .filter(review => !activeCycleIds.has(review.cycleId))
        .map(review => {
          const cycle = this.findCycleForReview(review, cycles);
          const managerReview = managerReviewByCycle.get(review.cycleId) ?? null;

          const managerDone = managerReview != null &&
            ['COMPLETED', 'PUBLISHED'].includes(String(managerReview.status ?? '').toUpperCase());

          const managerStatus = managerDone ? 'Completed' : 'Pending';

          // Only show a date when the manager review is actually submitted
          const managerTime = managerDone
            ? (managerReview!.submittedAt ?? null)
            : null;

          return {
            review,
            cycle,
            selfReviewStatus: this.resolveSelfReviewStatus(review),
            selfReviewTime: this.resolveSelfReviewTime(review),
            managerReviewStatus: managerStatus,
            managerReviewTime: managerTime,
            managerReview,
          };
        })
        .sort((a, b) =>
          (this.resolveSortTime(b.review) ?? '').localeCompare(this.resolveSortTime(a.review) ?? '')
        );
    }

    private isHistoryReview(review: HistoryReviewRecord, currentUserId: string): boolean {
      const status = String(review.status ?? '').toUpperCase();

      const isCompletedStatus = [
        'PUBLISHED',
        'COMPLETED',
        'COMPLETE',
        'SUBMITTED',
      ].includes(status);

      const isAboutCurrentUser =
        review.subjectUserId === currentUserId ||
        review.userId === currentUserId;

      return isAboutCurrentUser && isCompletedStatus;
    }
      private findCycleForReview(review: HistoryReviewRecord, cycles: ReviewCycle[]): ReviewCycle | undefined {
        return cycles.find(c =>
          c.cycleId === review.cycleId ||
          String((c as any).id) === String(review.cycleId)
        );
      }

  private resolveSelfReviewStatus(review: HistoryReviewRecord): string {
    if (Array.isArray(review.employeeReview) && review.employeeReview.length > 0) {
      return 'Completed';
    }

    if (review.reviewType === 'SELF') {
      return this.displayStatus(review.status);
    }

    return 'Completed';
  }

  private resolveSelfReviewTime(review: HistoryReviewRecord): string | null {
    return review.submittedTime
      ?? review.submittedAt
      ?? review.updatedAt
      ?? review.createdAt
      ?? null;
  }

  private resolveManagerReviewStatus(review: HistoryReviewRecord): string {
    if (Array.isArray(review.managerReview) && review.managerReview.length > 0) {
      return 'Published';
    }

    if (Array.isArray(review.adminReview) && review.adminReview.length > 0) {
      return 'Published';
    }

    if (review.reviewType === 'MANAGER' || review.reviewType === 'PEER_360') {
      return 'Published';
    }

    return 'Pending';
  }

  private resolveManagerReviewTime(review: HistoryReviewRecord): string | null {
  const status = this.resolveManagerReviewStatus(review);

  if (status === 'Pending') {
    return null;
  }

  return review.submittedTime
    ?? review.submittedAt
    ?? review.updatedAt
    ?? review.createdAt
    ?? null;
}

  private resolveSortTime(review: HistoryReviewRecord): string | null {
    return review.submittedTime
      ?? review.submittedAt
      ?? review.updatedAt
      ?? review.createdAt
      ?? null;
  }

  private displayStatus(status: any): string {
    const value = String(status ?? '').toUpperCase();

    if (value === 'PUBLISHED') return 'Completed';
    if (value === 'COMPLETED') return 'Completed';
    if (value === 'COMPLETE') return 'Completed';
    if (value === 'SUBMITTED') return 'Completed';
    if (value === 'DRAFT') return 'Draft';

    return value || 'Pending';
  }

  private buildActionRows(cycle: ReviewCycle, userRole: string, reviews: Review[], userId: string): void {
    const rows: ReviewActionRow[] = [];

    for (const m of cycle.milestones ?? []) {
      const isEmployeeMilestone = m.assignedRoles?.includes('EMPLOYEE');
      if (!isEmployeeMilestone) continue;

      const reviewType: ReviewType = this.milestoneToReviewType(m.name);

      if (reviewType === 'PEER_360') continue;

      const existing = reviews.find(r =>
        r.cycleId === cycle.cycleId &&
        r.reviewType === reviewType &&
        (
          r.userId === userId ||
          (r as any).subjectUserId === userId ||
          (r as any).authorUserId === userId
        )
      );

      let status: ReviewActionRow['status'] = 'NOT_STARTED';

      if (existing) {
        status = this.normalizeActionStatus(existing.status);
      } else if (m.status === 'NOT_STARTED') {
        status = 'LOCKED';
      } else if (m.status === 'COMPLETE' || m.status === 'COMPLETED') {
        status = 'COMPLETED';
      } else {
        status = 'NOT_STARTED';
      }

      rows.push({
        reviewType,
        title: this.titleFor(reviewType),
        subtitle: this.subtitleFor(reviewType),
        iconClass: reviewType === 'SELF' ? 'self' : reviewType === 'MANAGER' ? 'manager' : 'peer360',
        milestone: m,
        status,
        daysRemaining: this.daysBetween(new Date(), this.parseDate(m.endDate)),
        existingReview: existing,
      });
    }

    // The caller's MANAGER review is authored by their manager, so it never shows
    // up in the author-indexed `reviews` (assigned-to-me) feed above, and its
    // milestone is assigned to MANAGER (not EMPLOYEE), so the loop skips it.
    // Surface it from the subject-indexed feed once PUBLISHED so the employee can
    // read the feedback released for this still-active cycle. Gated on PUBLISHED so
    // an un-released (DRAFT/COMPLETED) manager review never leaks before publish.
    const publishedManager = (this.mySubjectReviews ?? []).find(r =>
      r.cycleId === cycle.cycleId &&
      String(r.reviewType ?? '').toUpperCase() === 'MANAGER' &&
      (r.userId === userId || (r as any).subjectUserId === userId) &&
      String(r.status ?? '').toUpperCase() === 'PUBLISHED'
    );
    if (publishedManager) {
      const managerRow = rows.find(r => r.reviewType === 'MANAGER');
      if (managerRow) {
        // A manager milestone exposed to the employee already produced a row —
        // enrich it with the real published review so it becomes viewable.
        managerRow.status = 'PUBLISHED';
        managerRow.existingReview = publishedManager;
      } else {
        rows.push(this.buildManagerRow(cycle, publishedManager));
      }
    }

    // Append PEER_360 assignments for this cycle as additional rows in the same table.
    rows.push(...this.buildPeer360Rows(cycle, reviews, userId));

    this.actionRows = rows;
  }

  /**
   * Builds the read-only MANAGER row for a PUBLISHED manager review surfaced from
   * the subject feed. Anchors to the cycle's manager milestone (for the deadline)
   * when present, falling back to the first milestone.
   */
  private buildManagerRow(cycle: ReviewCycle, managerReview: Review): ReviewActionRow {
    const milestone =
      cycle.milestones?.find(m => m.templateSnapshot?.reviewType === 'MANAGER') ??
      cycle.milestones?.find(m => m.assignedRoles?.includes('MANAGER')) ??
      cycle.milestones?.find(m => (m.name ?? '').toLowerCase().includes('manager')) ??
      (cycle.milestones?.[0] as CycleMilestone);

    return {
      reviewType: 'MANAGER',
      title: this.titleFor('MANAGER'),
      subtitle: this.subtitleFor('MANAGER'),
      iconClass: 'manager',
      milestone,
      status: 'PUBLISHED',
      daysRemaining: this.daysBetween(new Date(), this.parseDate(milestone?.endDate)),
      existingReview: managerReview,
    };
  }

  private normalizeActionStatus(status: any): ReviewActionRow['status'] {
    const value = String(status ?? '').toUpperCase();

    if (value === 'PUBLISHED') return 'PUBLISHED';
    if (value === 'COMPLETED' || value === 'COMPLETE' || value === 'SUBMITTED') return 'COMPLETED';
    if (value === 'IN_PROGRESS') return 'IN_PROGRESS';
    if (value === 'DRAFT') return 'DRAFT';
    if (value === 'PENDING') return 'PENDING';

    return 'NOT_STARTED';
  }

  private milestoneToReviewType(name: string): ReviewType {
    const n = (name ?? '').toLowerCase();

    if (n.includes('manager')) return 'MANAGER';
    if (n.includes('peer') || n.includes('360') || n.includes('admin') || n.includes('hr')) return 'PEER_360';

    return 'SELF';
  }

  private titleFor(t: ReviewType): string {
    if (t === 'SELF') return 'Self Review';
    if (t === 'MANAGER') return 'Manager Review';
    return 'Peer 360 Review';
  }

  private subtitleFor(t: ReviewType): string {
    if (t === 'SELF') return 'Complete your self-assessment';
    if (t === 'MANAGER') return 'Completed by your manager';
    return 'Completed by a teammate (360 feedback)';
  }

  private parseDate(dateStr: string): Date {
    if (!dateStr) return new Date(NaN);

    const parts = dateStr.split('/');

    if (parts.length === 3) {
      const [day, month, year] = parts;
      return new Date(`${year}-${month.padStart(2, '0')}-${day.padStart(2, '0')}`);
    }
    return new Date(dateStr);
  }

  private daysBetween(a: Date, b: Date): number {
    const ms = b.getTime() - a.getTime();

    if (Number.isNaN(ms)) return 0;

    return Math.max(0, Math.ceil(ms / (1000 * 60 * 60 * 24)));
  }

  get selfReviewStat(): string {
    const row = this.actionRows.find(r => r.reviewType === 'SELF');
    if (!row) return 'Not Started';
    return this.displayActionStatus(row.status);
  }

  get selfReviewStatClass(): string {
    const row = this.actionRows.find(r => r.reviewType === 'SELF');
    if (!row) return 'stat-orange';
    if (row.status === 'COMPLETED' || row.status === 'PUBLISHED') return 'stat-green';
    return 'stat-orange';
  }

  get managerReviewStat(): string {
    const row = this.actionRows.find(r => r.reviewType === 'MANAGER');
    if (!row) return 'Pending';
    if (row.status === 'COMPLETED' || row.status === 'PUBLISHED') return 'Completed';
    return 'Pending';
  }

  get managerReviewStatClass(): string {
    const row = this.actionRows.find(r => r.reviewType === 'MANAGER');
    if (!row) return 'stat-muted';
    if (row.status === 'COMPLETED' || row.status === 'PUBLISHED') return 'stat-green';
    return 'stat-muted';
  }

  displayActionStatus(status: string): string {
    switch ((status ?? '').toUpperCase()) {
      case 'NOT_STARTED': return 'Not Started';
      case 'IN_PROGRESS': return 'In Progress';
      case 'COMPLETED':   return 'Completed';
      case 'PUBLISHED':   return 'Published';
      case 'DRAFT':       return 'Draft';
      case 'LOCKED':      return 'Pending';
      default:            return status;
    }
  }

  actionLabel(row: ReviewActionRow): string {
    const typeName = row.reviewType === 'SELF' ? 'Self Review'
      : row.reviewType === 'PEER_360' ? 'Peer Review'
      : 'Manager Review';

    if (row.status === 'COMPLETED' || row.status === 'PUBLISHED') return `View ${typeName}`;
    if (row.status === 'LOCKED') return 'Awaiting Review';
    if (this.isMilestoneExpired(row.milestone)) return 'Deadline Passed';
    if (row.status === 'NOT_STARTED') return `Start ${typeName}`;
    if (row.status === 'DRAFT' || row.status === 'IN_PROGRESS') return `Continue ${typeName}`;
    return 'Pending';
  }

  actionBtnClass(row: ReviewActionRow): string {
    if (!this.canAct(row) && !this.canView(row)) return 'muted';
    if (this.canView(row)) return 'view';
    if (row.reviewType === 'PEER_360') return 'dark';
    if (row.reviewType === 'MANAGER') return 'dark';
    return 'primary';
  }

  isMilestoneExpired(milestone: CycleMilestone): boolean {
    const end = this.parseDate(milestone?.endDate);
    if (!end || Number.isNaN(end.getTime())) return false;
    return end < new Date();
  }

  canAct(row: ReviewActionRow): boolean {
    if (row.status !== 'NOT_STARTED' && row.status !== 'DRAFT' && row.status !== 'IN_PROGRESS') return false;
    return !this.isMilestoneExpired(row.milestone);
  }

  canView(row: ReviewActionRow): boolean {
    return row.status === 'COMPLETED' || row.status === 'PUBLISHED';
  }

  openReview(row: ReviewActionRow): void {
    if (!this.canAct(row) && !this.canView(row)) return;

    const slug = row.reviewType === 'PEER_360' ? 'peer-360' : row.reviewType.toLowerCase();
    this.router.navigate(['/my-review/fill', slug], {
      queryParams: {
        cycleId: this.activeCycle?.cycleId,
        milestoneId: row.milestone?.milestoneId ?? '',
        reviewId: row.existingReview?.reviewId ?? '',
      },
    });
  }

  openHistoryDetails(item: ReviewHistoryItem, type: 'self' | 'manager' = 'self'): void {
    this.selectedHistory = item;
    this.selectedHistoryType = type;
    this.selectedModalReview = type === 'manager' && item.managerReview
      ? item.managerReview
      : item.review;
    this.selectedModalLabel = type === 'manager' ? 'Manager Review' : 'Self Review';
    this.showHistoryDetails = true;
  }

  hasManagerReview(item: ReviewHistoryItem): boolean {
    return item.managerReviewStatus !== 'Pending';
  }

  closeHistoryDetails(): void {
    this.showHistoryDetails = false;
    this.selectedHistory = null;
    this.selectedModalReview = null;
    this.selectedHistoryType = 'self';
    this.selectedModalLabel = 'Self Review';
  }

  exportHistoryToPdf(item: ReviewHistoryItem | null): void {
    if (!item) return;

    const isManager = this.selectedHistoryType === 'manager';
    const reviewToExport = this.selectedModalReview ?? item.review;
    const label = isManager ? 'Manager Review' : 'Self Review';
    const submittedDate = isManager
      ? this.formatDate(item.managerReviewTime)
      : this.formatDate(item.selfReviewTime);

    const cycleStr = (item.cycle?.cycleTitle ?? 'Review').replace(/\s+/g, '_').replace(/[^a-zA-Z0-9_]/g, '');
    const filename = `${label.replace(' ', '_')}_${cycleStr}`;

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

    const qaRows = (reviewToExport.answers ?? []).map((ans, i) => {
      const meta = this.getQuestionMeta(item, ans.questionId);
      const qText = meta?.text ?? ans.questionId;
      const isRating = (meta?.type ?? '').toUpperCase() === 'RATING';
      const scale = meta?.scale ?? 5;
      const ratingVal = isRating ? Number(ans.response) : 0;

      const ansHtml = isRating
        ? `<div class="qa-stars">${Array.from({ length: scale }, (_, j) =>
            `<span class="star ${j + 1 <= ratingVal ? 'filled' : ''}">★</span>`
          ).join('')} <span class="qa-rating-val">${ratingVal}/${scale}</span></div>`
        : `<div class="qa-a ${!ans.response ? 'empty' : ''}">${ans.response ? escapeHtml(ans.response) : 'No answer provided'}</div>`;

      return `
        <div class="qa-item">
          <div class="qa-q"><span class="qa-num">Q${i + 1}</span>${escapeHtml(qText)}</div>
          ${ansHtml}
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
        .doc-badge  { font-size: 0.65rem; font-weight: 700; letter-spacing: 0.1em;
                      text-transform: uppercase; opacity: 0.8; margin-bottom: 6px; }
        .doc-title  { font-size: 1.3rem; font-weight: 700; margin-bottom: 6px; }
        .doc-meta   { font-size: 0.8rem; opacity: 0.85; }
        .doc-block  { border: 1px solid #DEDEDE; border-radius: 10px; overflow: hidden; }
        .block-title{ font-size: 0.72rem; font-weight: 700; text-transform: uppercase;
                      letter-spacing: 0.06em; color: #626262; padding: 12px 18px;
                      background: #F4F4F4; border-bottom: 1px solid #DEDEDE; }
        .qa-item    { padding: 12px 18px; border-bottom: 1px solid #F4F4F4; }
        .qa-item:last-child { border-bottom: none; }
        .qa-q       { display: flex; gap: 8px; font-size: 0.8rem; font-weight: 600;
                      color: #626262; margin-bottom: 5px; align-items: flex-start; }
        .qa-num     { display: inline-flex; align-items: center; justify-content: center;
                      min-width: 20px; height: 20px; border-radius: 50%;
                      background: #EFF5FC; color: #4A90E2; font-size: 0.68rem;
                      font-weight: 700; flex-shrink: 0; }
        .qa-a       { font-size: 0.88rem; line-height: 1.7; white-space: pre-wrap;
                      padding-left: 28px; color: #2E2E2E; }
        .qa-a.empty { color: #94a3b8; font-style: italic; }
        .qa-stars   { padding-left: 28px; font-size: 1.1rem; }
        .star       { color: #DEDEDE; }
        .star.filled{ color: #4A90E2; }
        .qa-rating-val { font-size: 0.8rem; color: #626262; font-weight: 600; margin-left: 4px; }
        @media print {
          body { padding: 0; }
          .doc-header {
            background: linear-gradient(135deg, #4A90E2 0%, #2465AA 100%) !important;
            -webkit-print-color-adjust: exact; print-color-adjust: exact;
            padding: 14px 18px; border-radius: 8px; margin-bottom: 12px;
          }
          .qa-item { padding: 6px 14px; }
          .qa-q    { font-size: 8pt; }
          .qa-a    { font-size: 9pt; line-height: 1.5; }
        }
      </style></head><body>
        <div class="doc-header">
          <div class="doc-badge">${escapeHtml(label)}</div>
          <div class="doc-title">${escapeHtml(item.cycle?.cycleTitle ?? 'Performance Review')}</div>
          <div class="doc-meta">Submitted · ${escapeHtml(submittedDate)}</div>
        </div>
        <div class="doc-block">
          <div class="block-title">Responses</div>
          ${qaRows || '<div style="padding:16px 18px;color:#94a3b8;font-size:0.85rem;">No answers recorded.</div>'}
        </div>
      </body></html>
    `);
    win.document.close();
    win.onafterprint = () => { iframe.remove(); document.title = originalTitle; };
    setTimeout(() => { win.focus(); win.print(); }, 300);
  }

  /** Returns question metadata (text, type, scale) for a given questionId. */
  getQuestionMeta(item: ReviewHistoryItem | null, questionId: string): Question | null {
    if (!item?.cycle) return null;
    for (const m of item.cycle.milestones ?? []) {
      const q = m.templateSnapshot?.questions?.find((q: any) => q.questionId === questionId);
      if (q) return q;
    }
    return null;
  }

  getQuestionText(item: ReviewHistoryItem | null, questionId: string): string {
    return this.getQuestionMeta(item, questionId)?.text ?? '';
  }

  isRatingAnswer(item: ReviewHistoryItem | null, questionId: string): boolean {
    const type = (this.getQuestionMeta(item, questionId)?.type ?? '').toUpperCase();
    return type === 'RATING';
  }

  getRatingScale(item: ReviewHistoryItem | null, questionId: string): number {
    return this.getQuestionMeta(item, questionId)?.scale ?? 5;
  }

  ratingArrayFor(scale: number): number[] {
    return Array.from({ length: scale }, (_, i) => i + 1);
  }

  getHistoryQuestions(item: ReviewHistoryItem | null): Question[] {
    if (!item?.cycle) return [];
    const empMilestone = item.cycle.milestones?.find(m => m.assignedRoles?.includes('EMPLOYEE'));
    return empMilestone?.templateSnapshot?.questions ?? [];
  }

  getHistoryAnswer(item: ReviewHistoryItem | null, questionId: string): string {
    const ans = (item?.review?.answers as Answer[] | undefined)?.find(a => a.questionId === questionId);
    if (!ans || ans.response === null || ans.response === undefined) return 'Not answered';
    if (Array.isArray(ans.response)) return (ans.response as string[]).join(', ') || 'Not answered';
    return String(ans.response).trim() || 'Not answered';
  }

  getDetailReview(item: ReviewHistoryItem | null): ReviewAnswerItem[] {
    if (!item) return [];

    if (item.review.adminReview?.length) return item.review.adminReview;
    if (item.review.managerReview?.length) return item.review.managerReview;
    if (item.review.employeeReview?.length) return item.review.employeeReview;

    return [];
  }

  getAnswer(section: ReviewAnswerItem[] | undefined, questionId: string): any {
    return section?.find(entry => entry.questionId === questionId)?.answer ?? null;
  }

  getDetailOverallRating(item: ReviewHistoryItem | null): string {
    const detail = this.getDetailReview(item);
    return this.getAnswer(detail, 'overall_rating') ?? '—';
  }

  getDetailReviewedBy(item: ReviewHistoryItem | null): string {
    const detail = this.getDetailReview(item);
    return this.getAnswer(detail, 'reviewed_by') ?? '—';
  }

  getDetailSummary(item: ReviewHistoryItem | null): string {
    const detail = this.getDetailReview(item);
    return this.getAnswer(detail, 'summary') ?? '—';
  }

  getDetailList(item: ReviewHistoryItem | null, key: string): string[] {
    const detail = this.getDetailReview(item);
    const value = this.getAnswer(detail, key);

    if (Array.isArray(value)) return value;
    if (typeof value === 'string' && value.trim()) return [value];

    return [];
  }

  formatDate(dateStr?: string | null): string {
    if (!dateStr) return '—';
    const date = this.parseDate(dateStr);
    if (Number.isNaN(date.getTime())) return '—';
    return date.toLocaleDateString('en-AU', {
      day: 'numeric',
      month: 'short',
      year: 'numeric',
    });
  }

  formatPeriod(cycle?: ReviewCycle): string {
    if (!cycle) return '—';
    const startDate = this.parseDate(cycle.startDate);
    const endDate = this.parseDate(cycle.dueDate);
    if (Number.isNaN(startDate.getTime()) || Number.isNaN(endDate.getTime())) return '—';
    const start = startDate.toLocaleDateString('en-AU', { month: 'short', year: 'numeric' });
    const end   = endDate.toLocaleDateString('en-AU',   { month: 'short', year: 'numeric' });
    return `${start} – ${end}`;
  }

  getRatingClass(rating: string | null | undefined): string {
    const value = (rating ?? '').trim().toLowerCase();

    switch (value) {
      case 'exceeds expectations':
      case 'outstanding':
        return 'rating-green';

      case 'meets expectations':
        return 'rating-blue';

      case 'partially meets expectations':
      case 'developing':
        return 'rating-yellow';

      case 'needs improvement':
        return 'rating-orange';

      case 'unsatisfactory':
      case 'below expectations':
        return 'rating-red';

      default:
        return 'rating-default';
    }
  }

  getHistoryStatusClass(status: string | null | undefined): string {
    const value = (status ?? '').trim().toLowerCase();

    if (
      value === 'published' ||
      value === 'complete' ||
      value === 'completed'
    ) {
      return 'published';
    }

    return 'pending';
  }
}
