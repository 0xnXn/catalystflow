import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { HttpClient } from '@angular/common/http';
import { EmployeeService } from '../../services/employees.services';
import { NotificationService } from '../../services/notification.service';
import { AiReviewService, ManagerGoldenReview, AiReviewInsightsResponse, Peer360Digest } from '../../services/ai-review.service';
import { environment } from '../../../environment/environment';
import { escapeHtml } from '../../core/utils/escape-html';
import {
  ManualReminderResponse,
  ManualReminderSkippedItem,
  ManualReminderSkippedReason,
  ManualReminderTargetType,
  NotificationJobStatus,
  NotificationJobSummary,
} from '../../models/notification.model';

interface EmployeeRow {
  employeeId: string;
  employeeName: string;
  managerId: string | null;
  managerName: string | null;
  role: string;
  selfReviewStatus: string | null;
  selfSubmittedDate: string | null;
  selfReviewId: string | null;
  managerReviewStatus: string | null;
  managerSubmittedDate: string | null;
  managerReviewId: string | null;
  completedByAdmin: string | null;
  publishStatus: 'NOT_PUBLISHED' | 'PUBLISHED';
  peer360Count: number;
  hasGoldenReview: boolean;
  hasPeer360Digest: boolean;
  hasInsights: boolean;
  managerGoldenJobId: string | null;
  insightsJobId: string | null;
  peer360DigestJobId: string | null;
}

interface ManagerGroup {
  managerId: string;
  managerName: string;
  employeeCount: number;
  completedCount: number;
  expanded: boolean;
  employees: EmployeeRow[];
}

interface ReviewCycle {
  cycleId: string;
  name: string;
  status: string;
}

interface PublishBundleResponse {
  message?: string;
  reviews?: unknown[];
  notificationJobs?: NotificationJobSummary[];
}

type ReminderTarget = 'employee' | 'manager';
type NotificationTone = 'success' | 'error' | 'warning' | 'info';

interface NotificationToast {
  id: string;
  type: NotificationTone;
  message: string;
}

@Component({
  selector: 'app-cycle-summary',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './cycle-summary.component.html',
  styleUrls: ['./cycle-summary.component.css'],
})
export class CycleSummaryComponent implements OnInit, OnDestroy {
  cycles: ReviewCycle[] = [];
  selectedCycleId = '';
  searchTerm = '';
  managerGroups: ManagerGroup[] = [];
  unassignedEmployees: EmployeeRow[] = [];

  loading = true;
  error = '';
  unassignedExpanded = true;
  openDropdownFor: string | null = null;
  openReminderMenuFor: string | null = null;
  sendingReminderKey = '';
  notificationToasts: NotificationToast[] = [];
  selectedIds = new Set<string>();

  // Modal state
  modalOpen = false;
  modalTitle = '';
  modalSubtitle = '';
  modalSubmittedDate: string | null = null;
  modalLoading = false;
  modalQuestions: any[] = [];
  modalAnswers: Record<string, any> = {};
  modalPeerReviews: any[] = [];
  modalType: 'self' | 'manager' | 'peer360' | 'golden' | 'insights' | 'digest' = 'self';
  modalGolden: ManagerGoldenReview | null = null;
  modalInsights: AiReviewInsightsResponse | null = null;
  modalDigest: Peer360Digest | null = null;
  modalError: string | null = null;
  private fullCycle: any = null;
  private allUsers: any[] = [];
  private notificationPollers = new Map<string, ReturnType<typeof setInterval>>();
  private completedNotificationPollers = new Set<string>();
  private toastTimers = new Map<string, ReturnType<typeof setTimeout>>();
  private readonly maxVisibleToasts = 5;

  constructor(
    private router: Router,
    private employeeService: EmployeeService,
    private http: HttpClient,
    private notificationService: NotificationService,
    private aiReviewService: AiReviewService
  ) {}

  ngOnInit(): void {
    this.loadCycles();
  }

  ngOnDestroy(): void {
    this.clearNotificationPollers();
    this.clearToastTimers();
  }

  private loadCycles(): void {
    this.employeeService.getCycles().pipe(
      catchError(() => of([]))
    ).subscribe(cycles => {
      this.cycles = (cycles as any[])
        .filter(c => c.status !== 'DRAFT')
        .map(c => ({
          cycleId: c.cycleId,
          name: c.cycleTitle ?? c.name ?? c.cycleId,
          status: c.status,
        }));

      const active = this.cycles.find(c => c.status === 'ACTIVE');
      this.selectedCycleId = active?.cycleId ?? this.cycles[0]?.cycleId ?? '';

      if (this.selectedCycleId) {
        this.loadData();
      } else {
        this.loading = false;
      }
    });
  }

  private loadData(): void {
    this.loading = true;
    this.error = '';

    forkJoin({
      users: this.employeeService.getEmployees().pipe(catchError(() => of([]))),
      reviews: this.employeeService.getReviewsByCycle(this.selectedCycleId).pipe(catchError(() => of([]))),
      cycles: this.employeeService.getCycles().pipe(catchError(() => of([]))),
    }).subscribe(({ users, reviews, cycles }) => {
      const allUsers: any[] = Array.isArray(users) ? users : (users as any)?.users ?? [];
      const allReviews: any[] = Array.isArray(reviews) ? reviews : (reviews as any)?.reviews ?? [];
      const allCycles: any[] = Array.isArray(cycles) ? cycles : (cycles as any)?.cycles ?? [];
      this.allUsers = allUsers;
      this.fullCycle = allCycles.find((c: any) => c.cycleId === this.selectedCycleId) ?? null;

      this.buildGroups(allUsers, allReviews);
      this.loading = false;
    });
  }

  private buildGroups(users: any[], reviews: any[]): void {
    const reviewsBySubject = new Map<string, any[]>();
    for (const r of reviews) {
      const sid = r.subjectUserId;
      if (!sid) continue;
      if (!reviewsBySubject.has(sid)) reviewsBySubject.set(sid, []);
      reviewsBySubject.get(sid)!.push(r);
    }

    const userMap = new Map<string, any>();
    for (const u of users) { if (u.userId) userMap.set(u.userId, u); }

    // Derive manager per employee from who authored their MANAGER review
    const employeeManagerMap = new Map<string, string>();
    for (const r of reviews) {
      if (r.reviewType === 'MANAGER' && r.subjectUserId && r.authorUserId) {
        employeeManagerMap.set(r.subjectUserId, r.authorUserId);
      }
    }

    const buildRow = (user: any, managerId: string | null = null, managerName: string | null = null): EmployeeRow => {
      const userReviews = reviewsBySubject.get(user.userId) ?? [];
      const selfReview = userReviews.find((r: any) => r.reviewType === 'SELF');
      const managerReview = userReviews.find((r: any) => r.reviewType === 'MANAGER');
      const peer360Reviews = userReviews.filter((r: any) => r.reviewType === 'PEER_360');
      const isPublished = managerReview?.status === 'PUBLISHED';

      return {
        employeeId: user.userId ?? '',
        employeeName: `${user.firstName ?? ''} ${user.lastName ?? ''}`.trim() || user.email || user.userId,
        managerId,
        managerName,
        role: user.position ?? user.jobTitle ?? user.discipline ?? '',
        selfReviewStatus: selfReview?.status ?? null,
        selfSubmittedDate: selfReview?.submittedAt ? this.formatDate(selfReview.submittedAt) : null,
        selfReviewId: selfReview?.reviewId ?? null,
        managerReviewStatus: managerReview?.status ?? null,
        managerSubmittedDate: managerReview?.submittedAt ? this.formatDate(managerReview.submittedAt) : null,
        managerReviewId: managerReview?.reviewId ?? null,
        completedByAdmin: managerReview?.completedByAdmin ?? null,
        publishStatus: isPublished ? 'PUBLISHED' : 'NOT_PUBLISHED',
        peer360Count: peer360Reviews.filter((r: any) => r.status === 'COMPLETED' || r.status === 'PUBLISHED').length,
        hasGoldenReview: !!managerReview?.managerGoldenJobId,
        hasPeer360Digest: !!managerReview?.peer360DigestJobId,
        hasInsights: !!selfReview?.insightsJobId,
        managerGoldenJobId: managerReview?.managerGoldenJobId ?? null,
        insightsJobId: selfReview?.insightsJobId ?? null,
        peer360DigestJobId: managerReview?.peer360DigestJobId ?? null,
      };
    };

    // Only process actual employees (not admins or managers)
    const employees = users.filter(u => u.role !== 'ADMIN' && u.role !== 'MANAGER' && u.status === 'Active');

    const groupMap = new Map<string, ManagerGroup>();
    const unassigned: any[] = [];

    for (const user of employees) {
      const managerId = employeeManagerMap.get(user.userId) ?? user.managerId ?? user.reportingTo;
      if (!managerId) { unassigned.push(user); continue; }
      const manager = userMap.get(managerId);
      if (!manager) { unassigned.push(user); continue; }
      const managerName = `${manager.firstName ?? ''} ${manager.lastName ?? ''}`.trim() || manager.email || managerId;

      if (!groupMap.has(managerId)) {
        groupMap.set(managerId, {
          managerId,
          managerName,
          employeeCount: 0,
          completedCount: 0,
          expanded: true,
          employees: [],
        });
      }

      const row = buildRow(user, managerId, managerName);
      const group = groupMap.get(managerId)!;
      group.employees.push(row);
      group.employeeCount++;
      if (row.managerReviewStatus === 'COMPLETED' || row.publishStatus === 'PUBLISHED') {
        group.completedCount++;
      }
    }

    this.managerGroups = Array.from(groupMap.values());
    this.unassignedEmployees = unassigned.map(user => buildRow(user));
  }

  private formatDate(dateStr: string): string {
    const d = new Date(dateStr);
    return d.toLocaleDateString('en-AU', { day: 'numeric', month: 'short', year: 'numeric' });
  }

  onCycleChange(): void {
    this.selectedIds = new Set();
    this.openDropdownFor = null;
    this.openReminderMenuFor = null;
    this.clearNotificationPollers();
    this.loadData();
  }

  get selectedCycleName(): string {
    return this.cycles.find(c => c.cycleId === this.selectedCycleId)?.name ?? '';
  }

  get allEmployees(): EmployeeRow[] {
    return [
      ...this.managerGroups.flatMap(g => g.employees),
      ...this.unassignedEmployees,
    ];
  }

  get totalReviews(): number { return this.allEmployees.length; }

  get selfSubmittedCount(): number {
    return this.allEmployees.filter(e => e.selfReviewStatus === 'COMPLETED' || e.selfReviewStatus === 'PUBLISHED').length;
  }

  get managerSubmittedCount(): number {
    return this.allEmployees.filter(e => e.managerReviewStatus === 'COMPLETED' || e.managerReviewStatus === 'PUBLISHED').length;
  }

  get publishedCount(): number {
    return this.allEmployees.filter(e => e.publishStatus === 'PUBLISHED').length;
  }

  get filteredManagerGroups(): ManagerGroup[] {
    if (!this.searchTerm.trim()) return this.managerGroups;
    const term = this.searchTerm.toLowerCase();
    return this.managerGroups
      .map(g => ({
        ...g,
        employees: g.employees.filter(e =>
          e.employeeName.toLowerCase().includes(term) ||
          g.managerName.toLowerCase().includes(term)
        ),
      }))
      .filter(g => g.employees.length > 0);
  }

  get filteredUnassigned(): EmployeeRow[] {
    if (!this.searchTerm.trim()) return this.unassignedEmployees;
    return this.unassignedEmployees.filter(e =>
      e.employeeName.toLowerCase().includes(this.searchTerm.toLowerCase())
    );
  }

  toggleGroup(group: ManagerGroup): void { group.expanded = !group.expanded; }

  get publishableEmployees(): EmployeeRow[] {
    return this.allEmployees.filter(e => this.canPublish(e));
  }

  get allSelected(): boolean {
    return this.publishableEmployees.length > 0 &&
      this.publishableEmployees.every(e => this.selectedIds.has(e.employeeId));
  }

  get someSelected(): boolean { return this.selectedIds.size > 0; }

  canPublish(employee: EmployeeRow): boolean {
    return (employee.managerReviewStatus === 'COMPLETED') && employee.publishStatus === 'NOT_PUBLISHED';
  }

  toggleSelect(employee: EmployeeRow): void {
    const updated = new Set(this.selectedIds);
    updated.has(employee.employeeId) ? updated.delete(employee.employeeId) : updated.add(employee.employeeId);
    this.selectedIds = updated;
  }

  toggleSelectAll(): void {
    this.selectedIds = this.allSelected
      ? new Set()
      : new Set(this.publishableEmployees.map(e => e.employeeId));
  }

  publish(employee: EmployeeRow): void {
    this.http.post<PublishBundleResponse>(`${environment.apiUrl}/reviews/bundle/publish`, {
      cycleId: this.selectedCycleId,
      subjectUserId: employee.employeeId,
    }).pipe(catchError(() => of(null))).subscribe(res => {
      if (res !== null) {
        this.markPublished(employee);
        this.showToast('success', `Published ${employee.employeeName}.`);
        this.handlePublishNotificationJobs([employee], res.notificationJobs ?? [], false);
      }
    });
  }

  publishSelected(): void {
    const toPublish = this.allEmployees.filter(e => this.selectedIds.has(e.employeeId));
    const calls = toPublish.map(e =>
      this.http.post<PublishBundleResponse>(`${environment.apiUrl}/reviews/bundle/publish`, {
        cycleId: this.selectedCycleId,
        subjectUserId: e.employeeId,
      }).pipe(catchError(() => of(null)))
    );

    forkJoin(calls).subscribe(results => {
      const publishedRows: EmployeeRow[] = [];
      const publishJobs: NotificationJobSummary[] = [];
      toPublish.forEach((e, i) => {
        const result = results[i];
        if (result !== null) {
          this.markPublished(e);
          publishedRows.push(e);
          publishJobs.push(...(result.notificationJobs ?? []));
        }
      });
      if (publishedRows.length > 0) {
        const label = publishedRows.length === 1 ? 'review bundle' : 'review bundles';
        this.showToast('success', `Published ${publishedRows.length} ${label}.`);
        this.handlePublishNotificationJobs(publishedRows, publishJobs, true);
      }
    });
  }

  private markPublished(employee: EmployeeRow): void {
    employee.publishStatus = 'PUBLISHED';
    employee.managerReviewStatus = 'PUBLISHED';
    const updated = new Set(this.selectedIds);
    updated.delete(employee.employeeId);
    this.selectedIds = updated;
  }

  toggleDropdown(employeeId: string, event: Event): void {
    event.stopPropagation();
    this.openReminderMenuFor = null;
    this.openDropdownFor = this.openDropdownFor === employeeId ? null : employeeId;
  }

  closeDropdown(): void {
    this.openDropdownFor = null;
    this.openReminderMenuFor = null;
  }

  toggleReminderMenu(employee: EmployeeRow, event: Event): void {
    event.stopPropagation();
    this.openDropdownFor = null;
    this.openReminderMenuFor = this.openReminderMenuFor === employee.employeeId ? null : employee.employeeId;
  }

  canRemindEmployee(row: EmployeeRow): boolean {
    return !!this.selectedCycleId && !this.isCompletedStatus(row.selfReviewStatus);
  }

  canRemindManager(row: EmployeeRow): boolean {
    return !!this.selectedCycleId && !!row.managerId && !this.isCompletedStatus(row.managerReviewStatus);
  }

  employeeReminderDisabledReason(row: EmployeeRow): string {
    if (!this.selectedCycleId) return 'No cycle selected';
    if (this.isCompletedStatus(row.selfReviewStatus)) return 'Employee review is complete';
    return '';
  }

  managerReminderDisabledReason(row: EmployeeRow): string {
    if (!this.selectedCycleId) return 'No cycle selected';
    if (!row.managerId) return 'No manager assigned';
    if (this.isCompletedStatus(row.managerReviewStatus)) return 'Manager review is complete';
    return '';
  }

  isReminderSending(row: EmployeeRow, target: ReminderTarget): boolean {
    return this.sendingReminderKey === this.reminderKey(row, target);
  }

  sendReminder(row: EmployeeRow, target: ReminderTarget, event?: Event): void {
    event?.stopPropagation();
    this.openReminderMenuFor = null;
    if (target === 'employee' && !this.canRemindEmployee(row)) return;
    if (target === 'manager' && !this.canRemindManager(row)) return;

    const targetType: ManualReminderTargetType =
      target === 'employee' ? 'SELF_REVIEW_SUBJECT' : 'MANAGER_REVIEW_MANAGER';
    const key = this.reminderKey(row, target);
    this.sendingReminderKey = key;

    this.notificationService.sendManualReminder({
      cycleId: this.selectedCycleId,
      targetType,
      subjectUserIds: [row.employeeId],
    }).subscribe({
      next: response => {
        this.sendingReminderKey = '';
        this.handleReminderResponse(row, target, response);
      },
      error: err => {
        this.sendingReminderKey = '';
        const reminderResponse = this.manualReminderResponseFromError(err);
        if (reminderResponse) {
          this.handleReminderResponse(row, target, reminderResponse);
          return;
        }
        const msg = err?.status === 403
          ? 'You do not have permission to send this reminder.'
          : (err?.error?.message ?? 'Could not send reminder. Please try again.');
        this.showToast('error', msg);
      },
    });
  }

  attachmentCount(emp: EmployeeRow): number {
    let count = 0;
    // Self / manager only count once SUBMITTED (COMPLETED/PUBLISHED) — a DRAFT or
    // not-started review is not a viewable document and would otherwise contradict
    // the "Pending" status column. (Peer / AI artifacts below are inherently completed.)
    if (this.isCompletedStatus(emp.selfReviewStatus)) count++;
    if (this.isCompletedStatus(emp.managerReviewStatus)) count++;
    if (emp.peer360Count > 0) count++;
    if (emp.hasGoldenReview) count++;
    if (emp.hasPeer360Digest) count++;
    if (emp.hasInsights) count++;
    return count;
  }

  private handleReminderResponse(row: EmployeeRow, target: ReminderTarget, response: ManualReminderResponse): void {
    const created = response.created ?? [];
    const skipped = response.skipped ?? [];
    const jobIds = created.map(job => job.jobId).filter((id): id is string => !!id);

    if (jobIds.length > 0) {
      this.showToast('success', this.createdReminderMessage(row, target, created));
      this.startReminderPolling(this.reminderKey(row, target), jobIds);
    }

    if (skipped.length > 0) {
      this.showSkippedReminderToasts(row, target, skipped);
      return;
    }

    if (jobIds.length === 0) {
      this.showToast('info', this.noReminderNeededMessage(row, target));
    }
  }

  private handlePublishNotificationJobs(
    rows: EmployeeRow[],
    jobs: NotificationJobSummary[],
    grouped: boolean,
  ): void {
    const jobsWithIds = jobs.filter((job): job is NotificationJobSummary & { jobId: string } => !!job.jobId);
    if (jobsWithIds.length === 0) return;

    const names = this.uniqueNames(jobsWithIds.map(job => this.publishRecipientName(job.recipientUserId, rows)));
    if (names.length > 0) {
      const recipients = this.formatNameList(names);
      const prefix = grouped || names.length > 1 ? 'Publish notifications queued for' : 'Publish notification queued for';
      this.showToast('info', `${prefix} ${recipients}.`);
    }

    const namesByJobId = new Map<string, string>();
    jobsWithIds.forEach(job => {
      namesByJobId.set(job.jobId, this.publishRecipientName(job.recipientUserId, rows));
    });
    this.startPublishNotificationPolling(
      `publish:${jobsWithIds.map(job => job.jobId).join('|')}`,
      jobsWithIds.map(job => job.jobId),
      namesByJobId
    );
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
    this.startNotificationPolling(pollerKey, jobIds, statuses => this.showReminderDeliveryResultToast(statuses));
  }

  private startPublishNotificationPolling(
    pollerKey: string,
    jobIds: string[],
    namesByJobId: Map<string, string>,
  ): void {
    this.startNotificationPolling(pollerKey, jobIds, statuses =>
      this.showPublishDeliveryResultToasts(statuses, namesByJobId)
    );
  }

  private startNotificationPolling(
    pollerKey: string,
    jobIds: string[],
    onComplete: (statuses: Map<string, NotificationJobStatus>) => void,
  ): void {
    const existing = this.notificationPollers.get(pollerKey);
    if (existing) clearInterval(existing);
    this.completedNotificationPollers.delete(pollerKey);

    const statuses = new Map<string, NotificationJobStatus>();
    jobIds.forEach(id => statuses.set(id, 'PENDING'));
    const startedAt = Date.now();

    const poll = () => {
      jobIds.forEach(jobId => {
        this.notificationService.getJobStatus(jobId).subscribe({
          next: result => {
            statuses.set(jobId, result.status ?? 'PENDING');
            if (this.allNotificationJobsDone(statuses) && !this.completedNotificationPollers.has(pollerKey)) {
              this.completedNotificationPollers.add(pollerKey);
              this.clearNotificationPoller(pollerKey);
              onComplete(statuses);
            }
          },
          error: () => {
            // The source action already succeeded; transient status lookup
            // failures should not turn a queued notification into a user error.
          },
        });
      });

      if (Date.now() - startedAt > 60000 && !this.allNotificationJobsDone(statuses)) {
        this.completedNotificationPollers.add(pollerKey);
        this.clearNotificationPoller(pollerKey);
      }
    };

    poll();
    this.notificationPollers.set(pollerKey, setInterval(poll, 2000));
  }

  private showReminderDeliveryResultToast(statuses: Map<string, NotificationJobStatus>): void {
    const values = Array.from(statuses.values());
    const sent = values.filter(status => status === 'COMPLETED').length;
    const skipped = values.filter(status => status === 'SKIPPED').length;

    if (sent > 0) {
      this.showToast('success', sent === 1 ? 'Reminder sent.' : `${sent} reminders sent.`);
      return;
    }
    if (skipped > 0) {
      this.showToast('info', skipped === 1 ? 'Reminder skipped.' : `${skipped} reminders skipped.`);
    }
  }

  private showPublishDeliveryResultToasts(
    statuses: Map<string, NotificationJobStatus>,
    namesByJobId: Map<string, string>,
  ): void {
    const namesForStatus = (status: NotificationJobStatus) => this.uniqueNames(
      Array.from(statuses.entries())
        .filter(([, value]) => value === status)
        .map(([jobId]) => namesByJobId.get(jobId) ?? 'the recipient')
    );

    const sent = namesForStatus('COMPLETED');
    const skipped = namesForStatus('SKIPPED');
    const failed = namesForStatus('FAILED');

    if (sent.length > 0) {
      const prefix = sent.length === 1 ? 'Publish notification sent to' : 'Publish notifications sent to';
      this.showToast('success', `${prefix} ${this.formatNameList(sent)}.`);
    }
    if (skipped.length > 0) {
      const prefix = skipped.length === 1 ? 'Publish notification skipped for' : 'Publish notifications skipped for';
      this.showToast('info', `${prefix} ${this.formatNameList(skipped)}.`);
    }
    if (failed.length > 0) {
      const prefix = failed.length === 1 ? 'Publish notification could not be sent to' : 'Publish notifications could not be sent to';
      this.showToast('error', `${prefix} ${this.formatNameList(failed)}.`);
    }
  }

  private allNotificationJobsDone(statuses: Map<string, NotificationJobStatus>): boolean {
    return Array.from(statuses.values())
      .every(status => status === 'COMPLETED' || status === 'FAILED' || status === 'SKIPPED');
  }

  private createdReminderMessage(row: EmployeeRow, target: ReminderTarget, created: NotificationJobSummary[]): string {
    const recipients = this.formatNameList(this.recipientNamesFromCreated(created, row, target));
    if (target === 'manager') {
      return `Reminder queued for manager ${recipients}.`;
    }
    return `Reminder queued for ${recipients}.`;
  }

  private showSkippedReminderToasts(
    row: EmployeeRow,
    target: ReminderTarget,
    skipped: ManualReminderSkippedItem[],
  ): void {
    const grouped = new Map<ManualReminderSkippedReason | undefined, ManualReminderSkippedItem[]>();
    skipped.forEach(item => {
      const items = grouped.get(item.reason) ?? [];
      items.push(item);
      grouped.set(item.reason, items);
    });

    grouped.forEach((items, reason) => {
      const names = this.recipientNamesFromSkipped(items, row, target);
      const recipients = this.formatNameList(names);
      const message = this.skippedReminderMessage(target, reason, recipients);
      this.showToast(message.bannerTone, message.bannerText);
    });
  }

  private skippedReminderMessage(
    target: ReminderTarget,
    reason: ManualReminderSkippedReason | undefined,
    recipients: string,
  ): { bannerText: string; bannerTone: NotificationTone } {
    const labelledRecipients = target === 'manager' ? `manager ${recipients}` : recipients;
    if (reason === 'ALREADY_REMINDED_TODAY') {
      return {
        bannerText: `Already reminded ${labelledRecipients} today.`,
        bannerTone: 'warning',
      };
    }
    if (reason === 'FORBIDDEN') {
      return {
        bannerText: `You do not have permission to remind ${labelledRecipients}.`,
        bannerTone: 'error',
      };
    }
    return {
      bannerText: `No reminder needed for ${labelledRecipients}.`,
      bannerTone: 'info',
    };
  }

  private noReminderNeededMessage(row: EmployeeRow, target: ReminderTarget): string {
    const recipient = target === 'manager' ? `manager ${this.managerReminderName(row)}` : row.employeeName;
    return `No reminder needed for ${recipient}.`;
  }

  private recipientNamesFromCreated(
    created: NotificationJobSummary[],
    row: EmployeeRow,
    target: ReminderTarget,
  ): string[] {
    return this.uniqueNames(created.map(item =>
      this.recipientName(item.recipientUserId, row, target)
    ));
  }

  private recipientNamesFromSkipped(
    skipped: ManualReminderSkippedItem[],
    row: EmployeeRow,
    target: ReminderTarget,
  ): string[] {
    return this.uniqueNames(skipped.map(item =>
      this.recipientName(item.recipientUserId, row, target)
    ));
  }

  private recipientName(recipientUserId: string | undefined, row: EmployeeRow, target: ReminderTarget): string {
    if (recipientUserId) {
      const user = this.allUsers.find(u => u.userId === recipientUserId || String(u.id ?? '') === recipientUserId);
      if (user) return this.userName(user);
    }
    return target === 'manager' ? this.managerReminderName(row) : row.employeeName;
  }

  private publishRecipientName(recipientUserId: string | undefined, fallbackRows: EmployeeRow[]): string {
    if (recipientUserId) {
      const row = fallbackRows.find(item => item.employeeId === recipientUserId);
      if (row) return row.employeeName;
      const user = this.allUsers.find(u => u.userId === recipientUserId || String(u.id ?? '') === recipientUserId);
      if (user) return this.userName(user);
    }
    return fallbackRows.length === 1 ? fallbackRows[0].employeeName : 'the recipient';
  }

  private managerReminderName(row: EmployeeRow): string {
    return row.managerName || 'the recipient';
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

  private clearNotificationPoller(rowKey: string): void {
    const poller = this.notificationPollers.get(rowKey);
    if (poller) clearInterval(poller);
    this.notificationPollers.delete(rowKey);
  }

  private clearNotificationPollers(): void {
    this.notificationPollers.forEach(poller => clearInterval(poller));
    this.notificationPollers.clear();
    this.completedNotificationPollers.clear();
  }

  private clearToastTimers(): void {
    this.toastTimers.forEach(timer => clearTimeout(timer));
    this.toastTimers.clear();
  }

  private reminderKey(row: EmployeeRow, target: ReminderTarget): string {
    return `${row.employeeId}:${target}`;
  }

  /** A review is a viewable "document" only once submitted (COMPLETED/PUBLISHED).
   *  DRAFT / IN_PROGRESS / NOT_STARTED reviews are work-in-progress, not attachments —
   *  this gates both the attachment count and the per-review dropdown items so they
   *  stay consistent with the "Submitted / Pending" status column. */
  isCompletedStatus(status: string | null): boolean {
    return status === 'COMPLETED' || status === 'PUBLISHED';
  }

  private userName(user: any): string {
    return `${user.firstName ?? ''} ${user.lastName ?? ''}`.trim() || user.email || user.userId || 'the recipient';
  }

  private openModal(title: string, subtitle: string, type: 'self' | 'manager' | 'peer360', employee: EmployeeRow): void {
    this.modalTitle = title;
    this.modalSubtitle = subtitle;
    this.modalType = type;
    this.modalOpen = true;
    this.modalLoading = true;
    this.modalQuestions = [];
    this.modalAnswers = {};
    this.modalPeerReviews = [];
    this.modalSubmittedDate = type === 'self' ? employee.selfSubmittedDate : type === 'manager' ? employee.managerSubmittedDate : null;

    const reviewType = type === 'self' ? 'SELF' : type === 'manager' ? 'MANAGER' : 'PEER_360';
    const milestone = this.fullCycle?.milestones?.find((m: any) => m.templateSnapshot?.reviewType === reviewType)
      ?? this.fullCycle?.milestones?.find((m: any) =>
          type === 'manager' ? m.assignedRoles?.includes('MANAGER') : m.assignedRoles?.includes('EMPLOYEE')
        );
    this.modalQuestions = milestone?.templateSnapshot?.questions ?? [];

    this.employeeService.getReviewsBySubject(employee.employeeId)
      .pipe(catchError(() => of([])))
      .subscribe((reviews: any[]) => {
        if (type === 'peer360') {
          this.modalPeerReviews = reviews.filter((r: any) => r.reviewType === 'PEER_360'
            && (r.status === 'COMPLETED' || r.status === 'PUBLISHED'));
        } else {
          const review = reviews.find((r: any) => r.reviewType === reviewType
            && r.cycleId === this.selectedCycleId);
          for (const a of review?.answers ?? []) {
            this.modalAnswers[a.questionId] = a.response;
          }
        }
        this.modalLoading = false;
      });
  }

  openSelfReview(employee: EmployeeRow): void {
    this.openModal('Employee Self Review', employee.employeeName, 'self', employee);
  }

  openManagerReview(employee: EmployeeRow): void {
    this.openModal('Manager Review', employee.employeeName, 'manager', employee);
  }

  openPeer360Reviews(employee: EmployeeRow): void {
    this.openModal('Peer 360 Reviews', employee.employeeName, 'peer360', employee);
  }

  openGoldenReview(employee: EmployeeRow): void {
    this.openAiArtifact('Manager Golden (AI)', employee.employeeName, 'golden', employee.managerGoldenJobId);
  }

  openEmployeeGolden(employee: EmployeeRow): void {
    this.openAiArtifact('Employee Golden (AI)', employee.employeeName, 'insights', employee.insightsJobId);
  }

  /** Preserve citation map insertion order (c1, c2, …) in *ngFor | keyvalue. */
  keepOrder = (): number => 0;

  /**
   * Open the modal on an AI artifact (manager golden or employee insights).
   * Read-only viewer that one-shot-fetches an already-generated job result.
   * Deliberately separate from openModal() so the self/manager/peer360 Q&A
   * paths are left completely untouched.
   */
  private openAiArtifact(title: string, subtitle: string,
                         mode: 'golden' | 'insights' | 'digest', jobId: string | null): void {
    this.modalTitle = title;
    this.modalSubtitle = subtitle;
    this.modalType = mode;
    this.modalOpen = true;
    this.modalSubmittedDate = null;
    this.modalQuestions = [];
    this.modalAnswers = {};
    this.modalPeerReviews = [];
    this.modalGolden = null;
    this.modalInsights = null;
    this.modalDigest = null;
    this.modalError = null;

    if (!jobId) {
      this.modalError = `No AI ${mode === 'golden' ? 'golden review' : mode === 'insights' ? 'insights' : 'peer-360 digest'} has been generated for this employee yet.`;
      this.modalLoading = false;
      return;
    }

    this.modalLoading = true;
    if (mode === 'golden') {
      this.aiReviewService.getManagerGolden(jobId).subscribe({
        next: env => {
          if (env.status === 'COMPLETED' && env.result) { this.modalGolden = env.result; }
          else { this.modalError = env.errorMessage ?? `Golden review is ${env.status.toLowerCase()}.`; }
          this.modalLoading = false;
        },
        error: err => { this.modalError = err?.message ?? 'Failed to load golden review.'; this.modalLoading = false; },
      });
    } else if (mode === 'insights') {
      this.aiReviewService.getInsightsStatus(jobId).subscribe({
        next: env => {
          if (env.status === 'COMPLETED' && env.result) { this.modalInsights = env.result; }
          else { this.modalError = env.errorMessage ?? `Insights are ${env.status.toLowerCase()}.`; }
          this.modalLoading = false;
        },
        error: err => { this.modalError = err?.message ?? 'Failed to load insights.'; this.modalLoading = false; },
      });
    } else {
      this.aiReviewService.getPeer360Digest(jobId).subscribe({
        next: env => {
          if (env.status === 'COMPLETED' && env.result) { this.modalDigest = env.result; }
          else { this.modalError = env.errorMessage ?? `Peer-360 digest is ${env.status.toLowerCase()}.`; }
          this.modalLoading = false;
        },
        error: err => { this.modalError = err?.message ?? 'Failed to load peer-360 digest.'; this.modalLoading = false; },
      });
    }
  }

  openPeer360Digest(employee: EmployeeRow): void {
    this.openAiArtifact('Peer 360 Digest (AI)', employee.employeeName, 'digest', employee.peer360DigestJobId);
  }

  closeModal(): void {
    this.modalOpen = false;
    this.modalGolden = null;
    this.modalInsights = null;
    this.modalDigest = null;
    this.modalError = null;
  }

  exportModal(): void {
    const cycleName = this.selectedCycleName;
    const filename = `${this.modalSubtitle.replace(/\s+/g, '_')}_${this.modalTitle.replace(/\s+/g, '_')}_${cycleName.replace(/\s+/g, '_')}`;

    let bodyHtml = '';
    if (this.modalType === 'peer360') {
      this.modalPeerReviews.forEach((peer, pi) => {
        bodyHtml += `<div class="peer-label">Peer ${pi + 1}</div>`;
        this.modalQuestions.forEach((q, i) => {
          const ans = this.getPeerAnswer(peer, q.questionId);
          bodyHtml += `<div class="qa"><div class="q">${i + 1}. ${escapeHtml(q.text)}</div><div class="a${ans === '—' ? ' empty' : ''}">${escapeHtml(ans)}</div></div>`;
        });
      });
    } else {
      this.modalQuestions.forEach((q, i) => {
        const ans = this.getModalAnswer(q.questionId);
        bodyHtml += `<div class="qa"><div class="q">${i + 1}. ${escapeHtml(q.text)}</div><div class="a${ans === '—' ? ' empty' : ''}">${escapeHtml(ans)}</div></div>`;
      });
    }

    const existing = document.getElementById('__modal_print__');
    if (existing) existing.remove();
    const iframe = document.createElement('iframe');
    iframe.id = '__modal_print__';
    iframe.style.cssText = 'position:fixed;width:0;height:0;border:none;left:-9999px;top:-9999px;';
    iframe.setAttribute('sandbox', 'allow-same-origin allow-modals');
    document.body.appendChild(iframe);
    const win = iframe.contentWindow!;
    const origTitle = document.title;
    document.title = filename;
    win.document.write(`<!DOCTYPE html><html><head><meta charset="utf-8"><title>${escapeHtml(filename)}</title><style>
      @page{size:A4 portrait;margin:18mm 20mm}
      *{box-sizing:border-box;margin:0;padding:0}
      body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;color:#2E2E2E;padding:32px}
      .header{background:linear-gradient(135deg,#4A90E2,#2465AA);color:#fff;padding:18px 22px;border-radius:10px;margin-bottom:20px;-webkit-print-color-adjust:exact;print-color-adjust:exact}
      .header-badge{font-size:0.65rem;font-weight:700;letter-spacing:0.1em;text-transform:uppercase;opacity:0.85;margin-bottom:6px}
      .header-name{font-size:1.2rem;font-weight:700}
      .header-meta{font-size:0.8rem;opacity:0.8;margin-top:4px}
      .peer-label{font-size:0.72rem;font-weight:700;text-transform:uppercase;letter-spacing:0.06em;color:#4A90E2;margin:20px 0 8px}
      .qa{border:1px solid #DEDEDE;border-radius:8px;overflow:hidden;margin-bottom:12px}
      .q{font-size:0.78rem;font-weight:600;color:#626262;background:#F4F4F4;padding:9px 14px;border-bottom:1px solid #DEDEDE}
      .a{font-size:0.88rem;padding:10px 14px;line-height:1.6;white-space:pre-wrap}
      .a.empty{color:#94a3b8;font-style:italic}
    </style></head><body>
      <div class="header">
        <div class="header-badge">${escapeHtml(this.modalTitle)}</div>
        <div class="header-name">${escapeHtml(this.modalSubtitle)}</div>
        <div class="header-meta">${escapeHtml(cycleName)}${this.modalSubmittedDate ? ' &nbsp;·&nbsp; Submitted ' + escapeHtml(this.modalSubmittedDate) : ''}</div>
      </div>
      ${bodyHtml}
    </body></html>`);
    win.document.close();
    win.onafterprint = () => { iframe.remove(); document.title = origTitle; };
    setTimeout(() => { win.focus(); win.print(); }, 300);
  }

  getModalAnswer(questionId: string): string {
    const val = this.modalAnswers[questionId];
    if (val === null || val === undefined || val === '') return '—';
    if (Array.isArray(val)) return val.length ? val.join(', ') : '—';
    return String(val);
  }

  getPeerAnswer(review: any, questionId: string): string {
    const ans = (review.answers ?? []).find((a: any) => a.questionId === questionId);
    if (!ans) return '—';
    const v = ans.response;
    if (v === null || v === undefined || v === '') return '—';
    if (Array.isArray(v)) return v.length ? v.join(', ') : '—';
    return String(v);
  }

  startReview(employee: EmployeeRow): void {
    this.router.navigate(['/review/start/manager'], {
      queryParams: { subjectUserId: employee.employeeId, cycleId: this.selectedCycleId, adminOverride: 'true' }
    });
  }
}
