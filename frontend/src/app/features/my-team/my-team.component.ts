import { Component, HostListener, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { AuthService } from '../../core/auth/auth.service';
import { EmployeeService, ReviewCycle, CycleMilestone } from '../../services/employees.services';
import { NotificationService } from '../../services/notification.service';
import { User } from '../../models/employee.model';
import { Review, ReviewStatus } from '../../models/review.model';
import {
  ManualReminderResponse,
  ManualReminderSkippedItem,
  ManualReminderSkippedReason,
  ManualReminderTargetType,
  NotificationJobSummary,
  NotificationJobStatus,
} from '../../models/notification.model';
import { SkeletonComponent } from '../../shared/skeleton/skeleton.component';

export interface MemberDetail {
  member: User;
  reviews: { cycle?: ReviewCycle; review: Review }[];
  totalReviews: number;
  latestRating?: string;
  loading: boolean;
}

type ReminderKind = 'employee' | 'peer';
type NotificationTone = 'success' | 'error' | 'warning' | 'info';

interface ReminderModalState {
  member: User;
  kind: ReminderKind;
  recipients: string[];
}

interface NotificationToast {
  id: string;
  type: NotificationTone;
  message: string;
}

@Component({
  selector: 'app-my-team',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule, SkeletonComponent],
  templateUrl: './my-team.component.html',
  styleUrls: ['./my-team.component.css'],
})
export class MyTeamComponent implements OnInit, OnDestroy {
  private employeeService = inject(EmployeeService);
  private notificationService = inject(NotificationService);
  private authService     = inject(AuthService);
  private router          = inject(Router);

  members: User[]          = [];
  filtered: User[]         = [];
  allCycles: ReviewCycle[] = [];
  /** All ACTIVE cycles, sorted most recent first — drives the cycle picker. */
  availableActiveCycles: ReviewCycle[] = [];
  /** Bound to the picker. */
  selectedCycleId: string = '';
  activeCycle: ReviewCycle | null      = null;
  managerMilestone: CycleMilestone | null = null;

  /** userId → employee's self-review status */
  selfReviewStatus = new Map<string, ReviewStatus>();
  /** userId → manager's existing review (draft/completed) */
  managerDrafts = new Map<string, Review>();
  /** userId → peer360 reviews assigned for that subject */
  peerReviews = new Map<string, Review[]>();

  detailsItem: MemberDetail | null = null;

  searchQuery       = '';
  statusFilter      = '';
  selfStatusFilter  = '';
  sortColumn        = 'name';
  sortDir           = 1; // 1 = asc, -1 = desc
  loading           = true;
  allEmployees: User[] = [];

  openReminderMenuFor = '';
  reminderModal: ReminderModalState | null = null;
  sendingReminderKey = '';
  notificationToasts: NotificationToast[] = [];

  private managerId = '';
  private reminderPollers = new Map<string, ReturnType<typeof setInterval>>();
  private completedReminderPollers = new Set<string>();
  private toastTimers = new Map<string, ReturnType<typeof setTimeout>>();
  private readonly maxVisibleToasts = 5;

  @HostListener('document:click')
  closeReminderMenus(): void {
    this.openReminderMenuFor = '';
  }

  ngOnInit(): void {
    this.managerId = this.authService.getTokenPayload()?.sub ?? '';

    forkJoin({
      team:      this.employeeService.getMyTeam(this.managerId).pipe(catchError(() => of([]))),
      cycles:    this.employeeService.getCycles().pipe(catchError(() => of([]))),
      employees: this.employeeService.getEmployees().pipe(catchError(() => of([]))),
    }).subscribe(({ team, cycles, employees }) => {
      this.allEmployees = employees ?? [];
      this.members   = (team ?? []).filter((u: User) => u.status === 'Active');
      this.filtered  = this.members;
      this.allCycles = cycles;

      // Multi-active support: keep the full list, default to the most recent.
      this.availableActiveCycles = (cycles ?? [])
        .filter((c: ReviewCycle) => c.status === 'ACTIVE')
        .sort((a: ReviewCycle, b: ReviewCycle) =>
          (b.updatedAt ?? b.createdAt ?? '').localeCompare(a.updatedAt ?? a.createdAt ?? '')
        );

      this.activeCycle = this.availableActiveCycles[0] ?? null;
      this.selectedCycleId = this.activeCycle?.cycleId ?? '';

      if (this.activeCycle) {
        this.loadCycleData(this.activeCycle);
      } else {
        this.loading = false;
      }
    });
  }

  ngOnDestroy(): void {
    this.clearReminderPollers();
    this.clearToastTimers();
  }

  /**
   * Cycle picker change handler. Re-fetches reviews scoped to the newly-selected cycle
   * and re-derives self-review-status + manager-drafts maps. Selections in the picker
   * also feed `Manage 360` and `Start Review` button navigation.
   */
  onCycleChange(cycleId: string): void {
    this.selectedCycleId = cycleId;
    this.activeCycle = this.availableActiveCycles.find(c => c.cycleId === cycleId) ?? null;
    this.selfReviewStatus = new Map();
    this.managerDrafts = new Map();
    this.managerMilestone = null;
    this.closeReminderModal();
    this.clearReminderPollers();
    if (this.activeCycle) {
      this.loadCycleData(this.activeCycle);
    }
  }

  /** Loads the manager milestone + per-employee review state for one cycle. */
  private loadCycleData(cycle: ReviewCycle): void {
    this.loading = true;
    this.managerMilestone = cycle.milestones.find(
      (m: CycleMilestone) => m.assignedRoles.includes('MANAGER')
    ) ?? null;

    this.employeeService.getReviewsByCycle(cycle.cycleId)
      .pipe(catchError(() => of([])))
      .subscribe((reviews: Review[]) => {
        reviews.filter((r: Review) => r.reviewType === 'SELF')
          .forEach((r: Review) => {
            const uid = r.subjectUserId ?? r.userId;
            if (uid) this.selfReviewStatus.set(uid, r.status);
          });

        reviews.filter((r: Review) =>
          r.reviewType === 'MANAGER' && r.authorUserId === this.managerId
        ).forEach((r: Review) => {
          const uid = r.subjectUserId ?? '';
          if (uid) this.managerDrafts.set(uid, r);
        });

        // Index PEER_360 reviews by subject
        this.peerReviews.clear();
        reviews.filter((r: Review) => r.reviewType === 'PEER_360')
          .forEach((r: Review) => {
            const uid = r.subjectUserId ?? '';
            if (!uid) return;
            if (!this.peerReviews.has(uid)) this.peerReviews.set(uid, []);
            this.peerReviews.get(uid)!.push(r);
          });

        this.loading = false;
      });
  }

  applyFilters(): void {
    const q = (this.searchQuery ?? '').toLowerCase();
    this.filtered = this.members
      .filter(m => {
        // Defensive lowercasing — admin / legacy users may be missing email or
        // position. An unguarded .toLowerCase() on undefined throws and silently
        // breaks the entire filter (same class of bug all-employees had).
        const name     = `${m.firstName ?? ''} ${m.lastName ?? ''}`.toLowerCase();
        const email    = (m.email    ?? '').toLowerCase();
        const position = (m.position ?? '').toLowerCase();
        const match    = !q || name.includes(q) || email.includes(q) || position.includes(q);
        const empStat  = !this.statusFilter     || m.status === this.statusFilter;
        const selfStat = !this.selfStatusFilter || this.getSelfReviewStatus(m) === this.selfStatusFilter;
        return match && empStat && selfStat;
      })
      .sort((a, b) => {
        if (this.sortColumn === 'name') {
          const na = `${a.firstName} ${a.lastName}`.toLowerCase();
          const nb = `${b.firstName} ${b.lastName}`.toLowerCase();
          return na.localeCompare(nb) * this.sortDir;
        }
        if (this.sortColumn === 'selfStatus') {
          const order = ['NOT_STARTED', 'DRAFT', 'IN_PROGRESS', 'COMPLETED', 'PUBLISHED'];
          const ia = order.indexOf(this.getSelfReviewStatus(a));
          const ib = order.indexOf(this.getSelfReviewStatus(b));
          return (ia - ib) * this.sortDir;
        }
        if (this.sortColumn === 'managerStatus') {
          const order = ['NOT_STARTED', 'DRAFT', 'IN_PROGRESS', 'COMPLETED', 'PUBLISHED'];
          const ia = order.indexOf(this.getManagerReviewStatus(a));
          const ib = order.indexOf(this.getManagerReviewStatus(b));
          return (ia - ib) * this.sortDir;
        }
        return 0;
      });
  }

  sortBy(col: string): void {
    if (this.sortColumn === col) {
      this.sortDir = this.sortDir === 1 ? -1 : 1;
    } else {
      this.sortColumn = col;
      this.sortDir    = 1;
    }
    this.applyFilters();
  }

  get totalMembers(): number { return this.members.length; }

  get selfSubmittedCount(): number {
    return this.members.filter(m => this.employeeSubmitted(m)).length;
  }

  get managerDoneCount(): number {
    return this.members.filter(m => this.managerReviewCompleted(m)).length;
  }

  get cycleDaysRemaining(): number {
    if (!this.activeCycle) return 0;
    const due = this.parseDate(this.activeCycle.dueDate);
    if (!due) return 0;
    return Math.max(0, Math.ceil((due.getTime() - Date.now()) / 86400000));
  }

  getSelfReviewStatus(member: User): ReviewStatus {
    return this.selfReviewStatus.get(member.userId ?? '') ?? 'NOT_STARTED';
  }

  employeeSubmitted(member: User): boolean {
    const s = this.getSelfReviewStatus(member);
    return s === 'COMPLETED' || s === 'PUBLISHED';
  }

  hasManagerDraft(member: User): boolean {
    return this.managerDrafts.has(member.userId ?? '');
  }

  managerReviewCompleted(member: User): boolean {
    const draft = this.managerDrafts.get(member.userId ?? '');
    return draft?.status === 'COMPLETED' || draft?.status === 'PUBLISHED';
  }

  reviewButtonLabel(member: User): string {
    if (this.managerReviewCompleted(member)) return 'View Review';
    return this.hasManagerDraft(member) ? 'Continue Review' : 'Start Review';
  }

  startReview(member: User): void {
    if (!this.activeCycle) return;
    const existing  = this.managerDrafts.get(member.userId ?? '');
    const viewOnly  = this.managerReviewCompleted(member);
    // Note: no `canSubmit` query param — the manager-review-form fetches the
    // server-authoritative gate state via /reviews/manager-gate on init.
    this.router.navigate(['/my-team/review'], {
      queryParams: {
        cycleId:       this.activeCycle.cycleId,
        milestoneId:   this.managerMilestone?.milestoneId ?? '',
        subjectUserId: member.userId,
        reviewId:      existing?.reviewId ?? '',
        viewOnly:      viewOnly ? 'true' : 'false',
        from:          'my-team',
      }
    });
  }

  /**
   * Navigate to the per-employee 360 assignment screen for the active cycle.
   * Disabled when there is no active cycle.
   */
  managePeer360(member: User): void {
    if (!this.activeCycle) return;
    const employeeId = member.userId ?? String(member.id ?? '');
    if (!employeeId) return;
    this.router.navigate(
      ['/my-team', employeeId, 'cycle', this.activeCycle.cycleId, 'peer360'],
      { queryParams: { managerId: this.managerId } }
    );
  }

  toggleReminderMenu(member: User, event: MouseEvent): void {
    event.stopPropagation();
    const id = this.memberId(member);
    this.openReminderMenuFor = this.openReminderMenuFor === id ? '' : id;
  }

  openReminderConfirm(member: User, kind: ReminderKind, event?: MouseEvent): void {
    event?.stopPropagation();
    this.openReminderMenuFor = '';
    if (!this.activeCycle) return;
    if (kind === 'employee' && !this.canRemindEmployee(member)) return;
    if (kind === 'peer' && !this.canRemindPeers(member)) return;

    this.reminderModal = {
      member,
      kind,
      recipients: kind === 'employee'
        ? [this.memberName(member)]
        : this.incompletePeerReviews(member).map(r => this.reviewerName(r)),
    };
  }

  closeReminderModal(): void {
    this.reminderModal = null;
  }

  confirmReminder(): void {
    if (!this.activeCycle || !this.reminderModal) return;
    const { member, kind } = this.reminderModal;
    const memberId = this.memberId(member);
    if (!memberId) return;

    const key = this.reminderKey(member, kind);
    const targetType: ManualReminderTargetType =
      kind === 'employee' ? 'SELF_REVIEW_SUBJECT' : 'PEER_REVIEW_EMPLOYEE';

    this.sendingReminderKey = key;

    this.notificationService.sendManualReminder({
      cycleId: this.activeCycle.cycleId,
      targetType,
      subjectUserIds: [memberId],
    }).subscribe({
      next: response => {
        this.sendingReminderKey = '';
        this.closeReminderModal();
        this.handleReminderResponse(member, kind, response);
      },
      error: err => {
        this.sendingReminderKey = '';
        this.closeReminderModal();
        const reminderResponse = this.manualReminderResponseFromError(err);
        if (reminderResponse) {
          this.handleReminderResponse(member, kind, reminderResponse);
          return;
        }
        const msg = err?.status === 403
          ? 'You do not have permission to send this reminder.'
          : (err?.error?.message ?? 'Could not send reminder. Please try again.');
        this.showToast('error', msg);
      },
    });
  }

  canRemindEmployee(member: User): boolean {
    return !!this.activeCycle && !this.employeeSubmitted(member);
  }

  canRemindPeers(member: User): boolean {
    return !!this.activeCycle && this.incompletePeerReviews(member).length > 0;
  }

  employeeReminderDisabledReason(member: User): string {
    if (!this.activeCycle) return 'No active cycle';
    if (this.employeeSubmitted(member)) return 'Employee review is complete';
    return '';
  }

  peerReminderDisabledReason(member: User): string {
    if (!this.activeCycle) return 'No active cycle';
    const peers = this.peerReviews.get(this.memberId(member)) ?? [];
    if (peers.length === 0) return 'No peer reviewers assigned';
    if (this.incompletePeerReviews(member).length === 0) return 'All peer reviews are complete';
    return '';
  }

  incompletePeerReviews(member: User): Review[] {
    return (this.peerReviews.get(this.memberId(member)) ?? [])
      .filter(r => r.status !== 'COMPLETED' && r.status !== 'PUBLISHED');
  }

  isReminderSending(member: User, kind: ReminderKind): boolean {
    return this.sendingReminderKey === this.reminderKey(member, kind);
  }

  reminderModalTitle(): string {
    if (!this.reminderModal) return '';
    return this.reminderModal.kind === 'employee'
      ? 'Send employee reminder'
      : 'Send peer review reminders';
  }

  reminderModalBody(): string {
    if (!this.reminderModal) return '';
    const cycleTitle = this.activeCycle?.cycleTitle ?? 'the current cycle';
    if (this.reminderModal.kind === 'employee') {
      return `This will email ${this.memberName(this.reminderModal.member)} to complete their review for ${cycleTitle}.`;
    }
    const count = this.reminderModal.recipients.length;
    return `This will email ${count} incomplete peer reviewer${count === 1 ? '' : 's'} for ${this.memberName(this.reminderModal.member)} in ${cycleTitle}.`;
  }

  private handleReminderResponse(member: User, kind: ReminderKind, response: ManualReminderResponse): void {
    const created = response.created ?? [];
    const skipped = response.skipped ?? [];
    const jobIds = created.map(j => j.jobId).filter((id): id is string => !!id);

    if (jobIds.length > 0) {
      this.showToast('success', this.createdReminderMessage(member, kind, created));
      this.startReminderPolling(this.reminderKey(member, kind), jobIds);
    }

    if (skipped.length > 0) {
      this.showSkippedReminderToasts(kind, member, skipped);
      return;
    }

    if (jobIds.length === 0) {
      this.showToast('info', 'No reminder needed.');
    }
  }

  private manualReminderResponseFromError(err: unknown): ManualReminderResponse | null {
    const payload = (err as { error?: unknown } | null)?.error;
    const response = this.parsePossibleManualReminderResponse(payload);
    return this.isManualReminderResponse(response) ? response : null;
  }

  private parsePossibleManualReminderResponse(payload: unknown): unknown {
    if (typeof payload === 'string') {
      return this.parseManualReminderResponse(payload);
    }
    if (payload && typeof payload === 'object' && typeof (payload as { text?: unknown }).text === 'string') {
      return this.parseManualReminderResponse((payload as { text: string }).text);
    }
    return payload;
  }

  private parseManualReminderResponse(payload: string): unknown {
    try {
      return JSON.parse(payload);
    } catch {
      return null;
    }
  }

  private isManualReminderResponse(value: unknown): value is ManualReminderResponse {
    if (!value || typeof value !== 'object') return false;
    const response = value as ManualReminderResponse;
    return Array.isArray(response.created) || Array.isArray(response.skipped);
  }

  private startReminderPolling(pollerKey: string, jobIds: string[]): void {
    const existing = this.reminderPollers.get(pollerKey);
    if (existing) clearInterval(existing);
    this.completedReminderPollers.delete(pollerKey);

    const statuses = new Map<string, NotificationJobStatus>();
    jobIds.forEach(id => statuses.set(id, 'PENDING'));
    const startedAt = Date.now();

    const poll = () => {
      jobIds.forEach(jobId => {
        this.notificationService.getJobStatus(jobId).subscribe({
          next: result => {
            statuses.set(jobId, result.status ?? 'PENDING');
            if (this.allReminderJobsDone(statuses) && !this.completedReminderPollers.has(pollerKey)) {
              this.completedReminderPollers.add(pollerKey);
              this.clearReminderPoller(pollerKey);
              this.showDeliveryResultToast(statuses);
            }
          },
          error: () => {
            // Status lookup can briefly fail before materialization or due to a
            // transient transport issue. The reminder request has already been
            // accepted, so do not show a failure toast for polling errors.
          },
        });
      });

      if (Date.now() - startedAt > 60000 && !this.allReminderJobsDone(statuses)) {
        this.completedReminderPollers.add(pollerKey);
        this.clearReminderPoller(pollerKey);
      }
    };

    poll();
    this.reminderPollers.set(pollerKey, setInterval(poll, 2000));
  }

  private showDeliveryResultToast(statuses: Map<string, NotificationJobStatus>): void {
    const values = Array.from(statuses.values());
    const sent = values.filter(s => s === 'COMPLETED').length;
    const skipped = values.filter(s => s === 'SKIPPED').length;

    if (sent > 0) {
      this.showToast('success', sent === 1
        ? 'Reminder sent.'
        : `${sent} reminders sent.`);
      return;
    }
    if (skipped > 0) {
      this.showToast('info', skipped === 1
        ? 'Reminder skipped.'
        : `${skipped} reminders skipped.`);
    }
  }

  private allReminderJobsDone(statuses: Map<string, NotificationJobStatus>): boolean {
    return Array.from(statuses.values())
      .every(s => s === 'COMPLETED' || s === 'FAILED' || s === 'SKIPPED');
  }

  private createdReminderMessage(member: User, kind: ReminderKind, created: NotificationJobSummary[]): string {
    const names = this.recipientNamesFromCreated(created, member, kind);
    const recipients = this.formatNameList(names);
    if (kind === 'employee') {
      return `Reminder queued for ${recipients}.`;
    }
    return `Reminders queued for ${recipients}.`;
  }

  private showSkippedReminderToasts(
    kind: ReminderKind,
    fallbackMember: User,
    skipped: ManualReminderSkippedItem[],
  ): void {
    const grouped = new Map<ManualReminderSkippedReason | undefined, ManualReminderSkippedItem[]>();
    skipped.forEach(item => {
      const items = grouped.get(item.reason) ?? [];
      items.push(item);
      grouped.set(item.reason, items);
    });

    grouped.forEach((items, reason) => {
      const names = this.recipientNamesFromSkipped(items, fallbackMember, kind);
      const recipients = this.formatNameList(names);
      const message = this.skippedReminderMessage(kind, reason, recipients);
      this.showToast(message.bannerTone, message.bannerText);
    });
  }

  private skippedReminderMessage(
    kind: ReminderKind,
    reason: ManualReminderSkippedReason | undefined,
    recipients: string,
  ): { bannerText: string; bannerTone: NotificationTone } {
    if (reason === 'ALREADY_REMINDED_TODAY') {
      return {
        bannerText: `Already reminded ${recipients} today.`,
        bannerTone: 'warning',
      };
    }
    if (reason === 'FORBIDDEN') {
      return {
        bannerText: `You do not have permission to remind ${recipients}.`,
        bannerTone: 'error',
      };
    }
    return {
      bannerText: `No reminder needed for ${recipients}.`,
      bannerTone: 'info',
    };
  }

  private recipientNamesFromCreated(
    created: NotificationJobSummary[],
    fallbackMember: User,
    kind: ReminderKind,
  ): string[] {
    return this.uniqueNames(created.map(item =>
      this.recipientName(item.recipientUserId, fallbackMember, kind)
    ));
  }

  private recipientNamesFromSkipped(
    skipped: ManualReminderSkippedItem[],
    fallbackMember: User,
    kind: ReminderKind,
  ): string[] {
    return this.uniqueNames(skipped.map(item =>
      this.recipientName(item.recipientUserId, fallbackMember, kind)
    ));
  }

  private recipientName(recipientUserId: string | undefined, fallbackMember: User, kind: ReminderKind): string {
    if (recipientUserId) {
      const all = [...this.allEmployees, ...this.members, fallbackMember];
      const user = all.find(u => u.userId === recipientUserId || String((u as any).id ?? '') === recipientUserId);
      if (user) return this.memberName(user);
    }
    return kind === 'employee' ? this.memberName(fallbackMember) : 'the recipient';
  }

  private uniqueNames(names: string[]): string[] {
    return Array.from(new Set(names.filter(Boolean)));
  }

  private formatNameList(names: string[], cap = 3): string {
    const visible = names.slice(0, cap);
    const remaining = names.length - visible.length;
    if (visible.length === 0) return 'the recipient';
    if (remaining > 0) {
      return `${visible.join(', ')}, and ${remaining} other${remaining === 1 ? '' : 's'}`;
    }
    if (visible.length === 1) return visible[0];
    if (visible.length === 2) return `${visible[0]} and ${visible[1]}`;
    return `${visible.slice(0, -1).join(', ')}, and ${visible[visible.length - 1]}`;
  }

  private showToast(type: NotificationTone, message: string): void {
    const id = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
    const nextToasts = [...this.notificationToasts, { id, type, message }];
    const overflow = Math.max(0, nextToasts.length - this.maxVisibleToasts);
    if (overflow > 0) {
      nextToasts.slice(0, overflow).forEach(toast => this.clearToastTimer(toast.id));
    }
    this.notificationToasts = nextToasts.slice(overflow);
    const timer = setTimeout(() => this.dismissToast(id), 3000);
    this.toastTimers.set(id, timer);
  }

  dismissToast(id: string): void {
    this.notificationToasts = this.notificationToasts.filter(toast => toast.id !== id);
    this.clearToastTimer(id);
  }

  private clearToastTimer(id: string): void {
    const timer = this.toastTimers.get(id);
    if (timer) clearTimeout(timer);
    this.toastTimers.delete(id);
  }

  private clearReminderPoller(rowKey: string): void {
    const poller = this.reminderPollers.get(rowKey);
    if (poller) clearInterval(poller);
    this.reminderPollers.delete(rowKey);
  }

  private clearReminderPollers(): void {
    this.reminderPollers.forEach(poller => clearInterval(poller));
    this.reminderPollers.clear();
    this.completedReminderPollers.clear();
  }

  private clearToastTimers(): void {
    this.toastTimers.forEach(timer => clearTimeout(timer));
    this.toastTimers.clear();
  }

  openDetails(member: User): void {
    this.detailsItem = { member, reviews: [], totalReviews: 0, loading: true };

    // GET /reviews?subjectUserId=<employee> — returns all reviews where they are the subject
    this.employeeService.getReviewsBySubject(member.userId ?? '')
      .pipe(catchError(() => of([])))
      .subscribe((reviews: Review[]) => {
        // Keep only MANAGER reviews written by the currently logged-in manager
        const managerReviews = reviews.filter((r: Review) =>
          r.reviewType === 'MANAGER' && r.authorUserId === this.managerId
        );

        const mapped = managerReviews.map((r: Review) => ({
          cycle:  this.allCycles.find(c => c.cycleId === r.cycleId),
          review: r,
        })).sort((a, b) =>
          (b.review.submittedAt ?? b.review.cycleId ?? '').localeCompare(
           a.review.submittedAt ?? a.review.cycleId ?? '')
        );

        this.detailsItem = {
          member,
          reviews:      mapped,
          totalReviews: mapped.length,
          latestRating: mapped.find(m =>
            m.review.status === 'COMPLETED' || m.review.status === 'PUBLISHED'
          )?.review.overallRating ?? undefined,
          loading: false,
        };
      });
  }

  viewFullReview(item: { cycle?: ReviewCycle; review: Review }): void {
    if (!item.review.reviewId) return;
    this.router.navigate(['/my-team/review'], {
      queryParams: {
        cycleId:       item.review.cycleId,
        subjectUserId: item.review.subjectUserId,
        reviewId:      item.review.reviewId,
        viewOnly:      'true',
        from:          'my-team',
      }
    });
  }

  closeDetails(): void { this.detailsItem = null; }

  initials(member: User): string {
    return `${member.firstName?.[0] ?? ''}${member.lastName?.[0] ?? ''}`.toUpperCase();
  }

  memberName(member: User): string {
    return `${member.firstName ?? ''} ${member.lastName ?? ''}`.trim() || 'this employee';
  }

  memberId(member: User): string {
    return member.userId ?? String(member.id ?? '');
  }

  private reminderKey(member: User, kind: ReminderKind): string {
    return `${this.memberId(member)}:${kind}`;
  }

  private parseDate(dateStr?: string | null): Date | null {
    if (!dateStr) return null;
    // Handle DD/MM/YYYY (backend Australian format) → avoid JS misreading as MM/DD
    const ddmmyyyy = dateStr.match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/);
    if (ddmmyyyy) {
      const d = new Date(+ddmmyyyy[3], +ddmmyyyy[2] - 1, +ddmmyyyy[1]);
      return isNaN(d.getTime()) ? null : d;
    }
    const d = new Date(dateStr);
    return isNaN(d.getTime()) ? null : d;
  }

  formatDate(dateStr?: string | null): string {
    const d = this.parseDate(dateStr);
    if (!d) return '—';
    return d.toLocaleDateString('en-AU', { day: 'numeric', month: 'short', year: 'numeric' });
  }

  ratingClass(rating?: string | null): string {
    if (!rating) return 'grey';
    const r = rating.toLowerCase();
    if (r.includes('exceed')) return 'green';
    if (r.includes('meet'))   return 'blue';
    if (r.includes('below'))  return 'red';
    return 'grey';
  }

  getManagerReviewStatus(member: User): ReviewStatus {
    const draft = this.managerDrafts.get(member.userId ?? '');
    return (draft?.status as ReviewStatus) ?? 'NOT_STARTED';
  }

  managerStatusLabel(member: User): string {
    const s = this.getManagerReviewStatus(member);
    const map: Record<string, string> = {
      NOT_STARTED: 'Not Started', DRAFT: 'Draft',
      IN_PROGRESS: 'In Progress', COMPLETED: 'Completed', PUBLISHED: 'Published',
    };
    return map[s] ?? s;
  }

  managerStatusClass(member: User): string {
    const label = this.humanManagerStatusLabel(member);
    if (label === 'Ready to Write') return 'ready-to-write';
    if (label === 'Completed') return 'completed';
    if (label === 'In Progress') return 'in-progress';
    return 'awaiting-self-review';
  }

  selfStatusLabel(member: User): string {
    const s = this.getSelfReviewStatus(member);
    const map: Record<string, string> = {
      NOT_STARTED: 'Not Started', DRAFT: 'Draft',
      IN_PROGRESS: 'In Progress', COMPLETED: 'Completed', PUBLISHED: 'Published',
    };
    return map[s] ?? s;
  }

  selfStatusClass(member: User): string {
    return this.getSelfReviewStatus(member).toLowerCase().replace('_', '-');
  }

  isReadyForReview(member: User): boolean {
    return this.employeeSubmitted(member) && !this.managerReviewCompleted(member);
  }

  get readyCount(): number {
    return this.filtered.filter(m => this.isReadyForReview(m)).length;
  }

  humanManagerStatusLabel(member: User): string {
    if (this.managerReviewCompleted(member)) return 'Completed';
    const draft = this.managerDrafts.get(member.userId ?? '');
    if (draft?.status === 'IN_PROGRESS' || draft?.status === 'DRAFT') return 'In Progress';
    if (this.employeeSubmitted(member)) return 'Ready to Write';
    return 'Awaiting Self-Review';
  }

  hasPeerAssignments(member: User): boolean {
    return (this.peerReviews.get(member.userId ?? '') ?? []).length > 0;
  }

  peerReviewSummary(member: User): string {
    const peers = this.peerReviews.get(member.userId ?? '') ?? [];
    if (peers.length === 0) return 'Not assigned';
    const completed = peers.filter(r =>
      r.status === 'COMPLETED' || r.status === 'PUBLISHED'
    ).length;
    if (completed === peers.length) return `${peers.length}/${peers.length} completed`;
    return `${completed}/${peers.length} completed`;
  }

  get completedMembers(): User[] {
    return this.members.filter(m => this.managerReviewCompleted(m));
  }

  selfReviewSubmittedAt(member: User): string | null {
    return null;
  }

  managerReviewSubmittedAt(member: User): string | null {
    const draft = this.managerDrafts.get(member.userId ?? '');
    return draft?.submittedAt ?? null;
  }

  overallRating(member: User): string | null {
    const draft = this.managerDrafts.get(member.userId ?? '');
    return draft?.overallRating ?? null;
  }

  managerName(member: User): string {
    if (!member.reportingTo) return '—';
    const mgr = this.allEmployees.find(u => u.userId === member.reportingTo);
    return mgr ? `${mgr.firstName} ${mgr.lastName}` : '—';
  }

  reviewerName(review: Review): string {
    const id = review.authorUserId;
    if (!id) return '—';
    const all = [...this.allEmployees, ...this.members];
    const user = all.find(u => u.userId === id || String((u as any).id ?? '') === id);
    return user ? `${user.firstName} ${user.lastName}` : '—';
  }
}
