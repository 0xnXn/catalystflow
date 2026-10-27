import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { AuthService } from '../../core/auth/auth.service';
import { EmployeeService, ReviewCycle, MilestoneStatus, CycleMilestone, PublicUser } from '../../services/employees.services';
import { Review, ReviewStatus, ReviewType } from '../../models/review.model';

export interface DashboardTask {
  id: string;
  title: string;
  dueDate: string;
  status: 'Not Started' | 'In Draft' | 'Completed';
  type: 'self-review' | 'manager-review' | 'peer-review';
  actionLink: string[];
  actionQueryParams: Record<string, string>;
  /** Subject being reviewed (peer-review rows only) — used to patch the title
   *  with the resolved display name once getUserPublic returns. */
  subjectUserId?: string;
}
import { User } from '../../models/employee.model';
import { SkeletonComponent } from '../../shared/skeleton/skeleton.component';

export interface Milestone {
  label: string;
  date: string;
  offset: number;
  state: 'completed' | 'current' | 'upcoming';
  isNext: boolean;
}

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, RouterModule, SkeletonComponent],
  templateUrl: './dashboard.component.html',
  styleUrls: ['./dashboard.component.css'],
})
export class DashboardComponent implements OnInit {
  private employeeService = inject(EmployeeService);
  authService = inject(AuthService);

  currentUser: User | null = null;
  // Manager is sourced via the public projection (/users/{id}/public) so an
  // EMPLOYEE can read it. Only firstName/lastName are rendered (Org Chart
  // card) — full User is never needed here.
  manager: PublicUser | null = null;
  teamMembers: User[] = [];
  allUsers: User[] = [];
  /** subjectUserId → "First Last" for peer-review tasks, resolved via the
   *  PII-safe /users/{id}/public projection. getAllUsers() is ADMIN-only, so
   *  EMPLOYEE/MANAGER callers can't resolve peer names from allUsers — this
   *  map is how their peer task cards show the real name instead of a
   *  generic "Team Member" fallback. */
  private peerSubjectNames = new Map<string, string>();
  activeCycle: ReviewCycle | null = null;
  selectedMember: User | null = null;
  milestones: Milestone[] = [];
  progressPercent = 0;

  displayName = '';
  roleLabel = '';
  isManagerOrAdmin = false;

  tasks: DashboardTask[] = [];
  /** True until the first /users/me + /cycles fetch resolves. Drives the skeleton. */
  loading = true;
  cycleDueDateLabel = '';

  get tasksEmptyMessage(): string {
    if (this.authService.isAdmin()) {
      return "You're all caught up! No pending tasks right now.";
    }
    const role = this.currentUser?.role;
    if (role === 'MANAGER') {
      return 'No reviews waiting for you. Check back when the next cycle opens.';
    }
    return "Nothing to do right now — enjoy your day!";
  }

  ngOnInit(): void {
    const sub = this.authService.getTokenPayload()?.sub ?? '';

    // /users (list all) is ADMIN-only on the backend. Pre-flight the role so
    // EMPLOYEE/MANAGER callers don't trigger a 403 on every dashboard load —
    // they pick up their team via getMyTeam() in loadSecondaryData() instead.
    const usersObs = this.authService.isAdmin()
      ? this.employeeService.getAllUsers().pipe(catchError(() => of([])))
      : of([] as User[]);

    forkJoin({
      currentUser: this.employeeService.getUserById(sub).pipe(catchError(() => of(null))),
      cycles:      this.employeeService.getCycles('ACTIVE').pipe(catchError(() => of([]))),
      assignedReviews: this.employeeService.getMyReviews(sub).pipe(catchError(() => of([]))),
      subjectReviews: this.employeeService.getMySubjectReviews().pipe(catchError(() => of([]))),
      users: usersObs,
    }).subscribe(({ currentUser, cycles, assignedReviews, subjectReviews, users }) => {
      this.currentUser = currentUser;
      if (this.currentUser) {
        this.displayName = `${this.currentUser.firstName} ${this.currentUser.lastName}`;
        this.roleLabel   = this.getRoleLabel(this.currentUser.role);
        this.authService.cacheUserName(this.currentUser.firstName, this.currentUser.lastName);
        this.authService.cacheUserRole(this.currentUser.role);
        this.isManagerOrAdmin = this.authService.isManagerOrAdmin();
      }
      this.activeCycle = cycles
        .sort((a, b) => new Date(b.updatedAt ?? 0).getTime() - new Date(a.updatedAt ?? 0).getTime())[0] ?? null;
      if (this.activeCycle) this.buildMilestones(this.activeCycle);
      this.allUsers = users;
      this.buildPersonalTasks(assignedReviews, subjectReviews, sub);
      this.resolvePeerSubjectNames(assignedReviews);
      if (this.currentUser) this.loadSecondaryData(sub);
      this.loading = false;
    });
  }

  private loadSecondaryData(sub: string): void {
    if (!this.currentUser) return;

    // The dashboard only renders the manager's first + last name (Org Chart card).
    // Use the public projection so an EMPLOYEE can resolve their manager without
    // hitting the 403 on /users/{managerId} — that endpoint is gated to
    // self / direct-report's-manager / admin, none of which apply when an
    // employee looks "up" the org.
    const managerObs = this.currentUser.reportingTo
      ? this.employeeService.getUserPublic(this.currentUser.reportingTo).pipe(catchError(() => of(null)))
      : of(null);

    if (this.authService.isAdmin()) {
      forkJoin({
        manager: managerObs,
        users:   this.employeeService.getAllUsers().pipe(catchError(() => of([]))),
      }).subscribe(({ manager, users }) => {
        this.manager     = manager;
        this.allUsers    = users;
        this.teamMembers = users.filter(
          (u: User) => u.reportingTo === this.currentUser!.userId && u.status === 'Active'
        );
        this.loadManagerTasks(sub);
      });
    } else {
      const teamObs = this.isManagerOrAdmin
        ? this.employeeService.getMyTeam(sub).pipe(catchError(() => of([])))
        : of([]);

      forkJoin({ manager: managerObs, team: teamObs }).subscribe(({ manager, team }) => {
        this.manager     = manager;
        this.teamMembers = team;
        // Manager intentionally excluded — it's a PublicUser projection and is
        // rendered via this.manager directly, not looked up through allUsers.
        this.allUsers    = [...team, this.currentUser!];
        this.loadManagerTasks(sub);
      });
    }
  }

  private buildPersonalTasks(assignedReviews: Review[], subjectReviews: Review[], userId: string): void {
    if (!this.activeCycle) {
      this.tasks = [];
      return;
    }

    const tasks: DashboardTask[] = [];
    const cycleId = this.activeCycle.cycleId;
    const selfMilestone = this.findMilestone('SELF');
    const selfReview = [...(subjectReviews ?? []), ...(assignedReviews ?? [])].find(r =>
      r.cycleId === cycleId &&
      r.reviewType === 'SELF' &&
      ((r.subjectUserId ?? r.userId) === userId || r.authorUserId === userId)
    );

    if (selfMilestone && this.shouldShowReviewTask(selfReview?.status, selfMilestone)) {
      tasks.push({
        id: selfReview?.reviewId ?? `${cycleId}:self`,
        title: 'Complete Self Review',
        dueDate: selfMilestone.endDate,
        status: this.toTaskStatus(selfReview?.status),
        type: 'self-review',
        actionLink: ['/my-review/fill', 'self'],
        actionQueryParams: {
          cycleId,
          milestoneId: selfMilestone.milestoneId ?? '',
          reviewId: selfReview?.reviewId ?? '',
        },
      });
    }

    for (const review of assignedReviews ?? []) {
      if (review.cycleId !== cycleId || review.reviewType !== 'PEER_360') continue;
      if (this.isDone(review.status)) continue;
      const subjectId = review.subjectUserId ?? review.userId;
      const subjectName = this.peerSubjectNames.get(subjectId ?? '')
        ?? this.getUserName(subjectId, 'Team Member');
      const milestone = this.findMilestone('PEER_360') ?? this.milestonesFallback();
      tasks.push({
        id: review.reviewId ?? `${cycleId}:peer:${review.subjectUserId ?? review.userId}`,
        title: `Peer Review - ${subjectName}`,
        dueDate: milestone?.endDate ?? this.activeCycle.dueDate,
        status: this.toTaskStatus(review.status),
        type: 'peer-review',
        subjectUserId: subjectId,
        actionLink: ['/my-review/fill', 'peer-360'],
        actionQueryParams: {
          cycleId,
          milestoneId: milestone?.milestoneId ?? '',
          reviewId: review.reviewId ?? '',
        },
      });
    }

    this.tasks = this.sortTasks(tasks);
  }

  /**
   * Resolves the display name of every peer-review subject via getUserPublic
   * (works for any authenticated caller, unlike getAllUsers which is
   * ADMIN-only) and patches the peer task titles in place. Type-scoped so
   * manager tasks appended later by loadManagerTasks are never clobbered.
   */
  private resolvePeerSubjectNames(assignedReviews: Review[]): void {
    const ids = new Set<string>();
    for (const r of assignedReviews ?? []) {
      if (r.reviewType !== 'PEER_360' || this.isDone(r.status)) continue;
      const sid = r.subjectUserId ?? r.userId;
      if (sid && !this.peerSubjectNames.has(sid)) ids.add(sid);
    }
    if (ids.size === 0) return;

    forkJoin(
      Array.from(ids).map(id =>
        this.employeeService.getUserPublic(id).pipe(catchError(() => of(null)))
      )
    ).subscribe(users => {
      let changed = false;
      for (const u of users) {
        const name = u ? `${u.firstName ?? ''} ${u.lastName ?? ''}`.trim() : '';
        if (u?.userId && name) { this.peerSubjectNames.set(u.userId, name); changed = true; }
      }
      if (!changed) return;
      this.tasks = this.tasks.map(t =>
        t.type === 'peer-review' && t.subjectUserId && this.peerSubjectNames.has(t.subjectUserId)
          ? { ...t, title: `Peer Review - ${this.peerSubjectNames.get(t.subjectUserId)}` }
          : t
      );
    });
  }

  private loadManagerTasks(userId: string): void {
    if (!this.activeCycle || !this.isManagerOrAdmin || this.teamMembers.length === 0) return;
    const managerMilestone = this.findMilestone('MANAGER');
    if (!managerMilestone || this.isMilestoneClosed(managerMilestone)) return;

    this.employeeService.getReviewsByCycle(this.activeCycle.cycleId)
      .pipe(catchError(() => of([])))
      .subscribe((reviews: Review[]) => {
        const existingIds = new Set(this.tasks.map(t => t.id));
        const managerTasks: DashboardTask[] = [];

        for (const member of this.teamMembers) {
          const review = reviews.find(r =>
            r.reviewType === 'MANAGER' &&
            r.authorUserId === userId &&
            (r.subjectUserId ?? r.userId) === member.userId
          );
          if (this.isDone(review?.status)) continue;
          const id = review?.reviewId ?? `${this.activeCycle!.cycleId}:manager:${member.userId}`;
          if (existingIds.has(id)) continue;
          managerTasks.push({
            id,
            title: `Manager Review - ${member.firstName} ${member.lastName}`,
            dueDate: managerMilestone.endDate,
            status: this.toTaskStatus(review?.status),
            type: 'manager-review',
            actionLink: ['/my-team/review'],
            actionQueryParams: {
              cycleId: this.activeCycle!.cycleId,
              milestoneId: managerMilestone.milestoneId ?? '',
              subjectUserId: member.userId ?? String(member.id ?? ''),
              reviewId: review?.reviewId ?? '',
              viewOnly: 'false',
              from: 'dashboard',
            },
          });
        }

        this.tasks = this.sortTasks([...this.tasks, ...managerTasks]);
      });
  }

  private sortTasks(tasks: DashboardTask[]): DashboardTask[] {
    return tasks.sort((a, b) => {
      const ad = this.parseDate(a.dueDate).getTime();
      const bd = this.parseDate(b.dueDate).getTime();
      return ad - bd || a.title.localeCompare(b.title);
    });
  }

  private findMilestone(type: ReviewType): CycleMilestone | null {
    const ms = this.activeCycle?.milestones ?? [];
    return ms.find(m => this.milestoneType(m.name) === type) ?? null;
  }

  private milestonesFallback(): CycleMilestone | null {
    return this.activeCycle?.milestones?.[0] ?? null;
  }

  private milestoneType(name: string): ReviewType {
    const n = (name ?? '').toLowerCase();
    if (n.includes('manager')) return 'MANAGER';
    if (n.includes('peer') || n.includes('360')) return 'PEER_360';
    return 'SELF';
  }

  private shouldShowReviewTask(status: ReviewStatus | undefined, milestone: CycleMilestone): boolean {
    if (this.isDone(status)) return false;
    return !this.isMilestoneClosed(milestone);
  }

  private isMilestoneClosed(milestone: CycleMilestone): boolean {
    return milestone.status === 'COMPLETE' || milestone.status === 'COMPLETED';
  }

  private isDone(status?: ReviewStatus): boolean {
    return status === 'COMPLETED' || status === 'PUBLISHED';
  }

  private toTaskStatus(status?: ReviewStatus): DashboardTask['status'] {
    if (this.isDone(status)) return 'Completed';
    if (status === 'DRAFT' || status === 'IN_PROGRESS') return 'In Draft';
    return 'Not Started';
  }

  private getUserName(userId?: string, fallback = 'Unknown'): string {
    const user = this.allUsers.find(u => u.userId === userId || String(u.id ?? '') === String(userId ?? ''));
    return user ? `${user.firstName} ${user.lastName}`.trim() : fallback;
  }

  canStartTask(task: DashboardTask): boolean {
    return task.status !== 'Completed' && !this.isExpired(task);
  }

  /** A task is expired once its due date has passed and it was never completed.
   *  Expired tasks stay visible (so the lapse is acknowledged) but can't be started. */
  isExpired(task: DashboardTask): boolean {
    if (task.status === 'Completed') return false;
    const due = this.startOfDay(this.parseDate(task.dueDate));
    if (Number.isNaN(due.getTime())) return false;
    return due.getTime() < this.startOfDay(new Date()).getTime();
  }

  /** Pending = not completed and not expired. Drives the "N Pending" badge. */
  get pendingTaskCount(): number {
    return this.tasks.filter(t => t.status !== 'Completed' && !this.isExpired(t)).length;
  }

  getTaskActionLabel(task: DashboardTask): string {
    return task.status === 'In Draft' ? 'Continue' : 'Start';
  }

  private getRoleLabel(role: string): string {
    if (role === 'ADMIN')    return 'Full system access and administrative controls.';
    if (role === 'MANAGER')  return 'Manage your team and complete performance reviews.';
    return 'Complete your self-review and track your performance.';
  }

  private buildMilestones(cycle: ReviewCycle): void {
    const start = this.startOfDay(this.parseDate(cycle.startDate));
    const due = this.startOfDay(this.parseDate(cycle.dueDate));
    const total = Math.max(1, due.getTime() - start.getTime());
    const today = this.startOfDay(new Date());
    this.cycleDueDateLabel = this.dateToDMY(due);

    const grouped = new Map<number, { date: Date; names: string[] }>();
    for (const milestone of cycle.milestones ?? []) {
      const date = this.startOfDay(this.parseDate(milestone.startDate || milestone.endDate));
      if (Number.isNaN(date.getTime())) continue;
      const key = date.getTime();
      const existing = grouped.get(key);
      if (existing) {
        existing.names.push(milestone.name);
      } else {
        grouped.set(key, { date, names: [milestone.name] });
      }
    }

    if (!Number.isNaN(due.getTime())) {
      const dueKey = due.getTime();
      const existing = grouped.get(dueKey);
      if (existing) {
        existing.names.push('Review Cycle End');
      } else {
        grouped.set(dueKey, { date: due, names: ['Review Cycle End'] });
      }
    }

    const entries = Array.from(grouped.values())
      .sort((a, b) => a.date.getTime() - b.date.getTime());
    const nextIndex = entries.findIndex(entry => entry.date.getTime() > today.getTime());
    let reachedIndex = -1;
    entries.forEach((entry, index) => {
      if (entry.date.getTime() <= today.getTime()) reachedIndex = index;
    });

    const visualOffsets = entries.map((_, index) => ((index + 1) / (entries.length + 1)) * 100);
    if (!entries.length) {
      this.progressPercent = this.clamp(((today.getTime() - start.getTime()) / total) * 100, 0, 100);
    } else if (nextIndex === -1) {
      this.progressPercent = 100;
    } else {
      const previousOffset = reachedIndex >= 0 ? visualOffsets[reachedIndex] : 0;
      this.progressPercent = (previousOffset + visualOffsets[nextIndex]) / 2;
    }

    this.milestones = entries.map((entry, index) => {
      const isNext = index === nextIndex;
      const isPast = entry.date.getTime() <= today.getTime();
      return {
        label: entry.names.join(' / '),
        date: this.dateToDMY(entry.date),
        offset: visualOffsets[index],
        state: isPast ? 'completed' : (isNext ? 'current' : 'upcoming'),
        isNext,
      };
    });
  }

  private mapStatus(status: MilestoneStatus): 'completed' | 'current' | 'upcoming' {
    if (status === 'COMPLETE' || status === 'COMPLETED') return 'completed';
    if (status === 'IN_PROGRESS' || status === 'OPEN')   return 'current';
    return 'upcoming';
  }

  private startOfDay(date: Date): Date {
    if (Number.isNaN(date.getTime())) return date;
    return new Date(date.getFullYear(), date.getMonth(), date.getDate());
  }

  private clamp(value: number, min: number, max: number): number {
    return Math.min(max, Math.max(min, value));
  }

  private dateToDMY(date: Date): string {
    const day = String(date.getDate()).padStart(2, '0');
    const month = String(date.getMonth() + 1).padStart(2, '0');
    return `${day}/${month}/${date.getFullYear()}`;
  }

  openMemberProfile(member: User): void {
    this.selectedMember = member;
  }

  closeMemberProfile(): void {
    this.selectedMember = null;
  }

  getMemberManagerName(member: User): string {
    if (!member.reportingTo) return '—';
    const mgr = this.allUsers.find(u => u.userId === member.reportingTo);
    return mgr ? `${mgr.firstName} ${mgr.lastName}` : '—';
  }
  getMemberDepartment(member: User): string {
    if (member.discipline) return `${member.discipline} Department`;
    return '-';
  }

  getLatestRating(_member: User): string {
    return '-';
  }

  getCurrentGoals(_member: User): string {
    return '-';
  }

  getCompletedReviews(_member: User): string {
    return '-';
  }

  getMemberSkills(_member: User): string[] {
    return [];
  }

  private parseDMY(dateStr: string): Date {
    const [day, month, year] = dateStr.split('/');
    return new Date(`${year}-${month.padStart(2, '0')}-${day.padStart(2, '0')}`);
  }

  private parseDate(dateStr?: string | null): Date {
    if (!dateStr) return new Date(NaN);
    return dateStr.includes('/') ? this.parseDMY(dateStr) : new Date(dateStr);
  }

  formatHireDate(dateStr: string): string {
    if (!dateStr) return '—';
    const d = this.parseDate(dateStr);
    return d.toLocaleDateString('en-AU', { day: 'numeric', month: 'short', year: 'numeric' });
  }

  formatDate(dateStr: string): string {
    if (!dateStr) return '—';
    const d = this.parseDate(dateStr);
    return d.toLocaleDateString('en-AU', { day: 'numeric', month: 'short' });
  }

  formatTaskDate(dateStr: string): string {
    if (!dateStr) return '---';
    const d = this.parseDate(dateStr);
    return d.toLocaleDateString('en-AU', { day: 'numeric', month: 'short', year: 'numeric' });
  }

  getAvatarUrl(firstName: string, lastName: string): string {
    const name = encodeURIComponent(`${firstName} ${lastName}`);
    return `https://ui-avatars.com/api/?name=${name}&background=2E2E2E&color=fff&size=64`;
  }
}
