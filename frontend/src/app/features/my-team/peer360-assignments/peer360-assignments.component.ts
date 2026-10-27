import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { catchError, forkJoin, of } from 'rxjs';

import { AuthService } from '../../../core/auth/auth.service';
import { EmployeeService, ReviewCycle, CycleMilestone } from '../../../services/employees.services';
import { Peer360AssignmentService } from '../../../services/peer360-assignment.service';
import { ReviewCyclesService } from '../../../services/review-cycles.service';
import { User } from '../../../models/employee.model';
import { Review } from '../../../models/review.model';
import { SkeletonComponent } from '../../../shared/skeleton/skeleton.component';

interface EligiblePeer {
  user: User;
  selected: boolean;
}

@Component({
  selector: 'app-peer360-assignments',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule, SkeletonComponent],
  templateUrl: './peer360-assignments.component.html',
  styleUrl: './peer360-assignments.component.css',
})
export class Peer360AssignmentsComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private authService = inject(AuthService);
  private employeeService = inject(EmployeeService);
  private assignmentService = inject(Peer360AssignmentService);
  private cyclesService = inject(ReviewCyclesService);

  employeeId = '';
  cycleId = '';
  subject: User | null = null;
  managerId = '';

  cycle: ReviewCycle | null = null;

  /**
   * The PEER_360 template + milestone resolved from the cycle the admin set up.
   * Manager doesn't pick — admin already attached the template at cycle creation.
   * If null, the cycle is missing a Peer 360 milestone and assignment is blocked.
   */
  peer360Template: { templateId: string; templateName: string; milestoneName: string } | null = null;

  /** Full company employee list — kept so the toggle can recompute without re-fetching. */
  private allEmployees: User[] = [];

  /** When false (default): eligible list is scoped to the manager's team (subject's siblings). */
  showOutsideTeam = false;

  eligible: EligiblePeer[] = [];
  assignments: Review[] = [];

  loading = true;
  saving = false;
  selfReviewSubmitted = false; // true when manager review is submitted — locks peer assignments
  errorMessage = '';
  successMessage = '';
  searchQuery = '';
  collapsedGroups = new Set<string>();
  groupedEligible: { managerId: string; managerName: string; peers: EligiblePeer[] }[] = [];

  ngOnInit(): void {
    this.employeeId = this.route.snapshot.paramMap.get('employeeId') ?? '';
    this.cycleId = this.route.snapshot.paramMap.get('cycleId') ?? '';
    const fromUrl = (this.route.snapshot.queryParamMap.get('managerId') ?? '').trim();
    const fromAuth = (this.authService.getTokenPayload()?.sub ?? '').trim();
    this.managerId = fromUrl || fromAuth;

    if (!this.employeeId || !this.cycleId) {
      this.errorMessage = 'Missing employeeId or cycleId in the URL.';
      this.loading = false;
      return;
    }

    forkJoin({
      employees: this.employeeService.getEmployees().pipe(catchError(() => of([] as User[]))),
      assignments: this.assignmentService
        .list(this.cycleId, this.employeeId)
        .pipe(catchError(() => of([] as Review[]))),
      cycle: this.cyclesService.getCycle(this.cycleId).pipe(catchError(() => of(null))),
      managerReviews: this.employeeService.getReviewsBySubject(this.employeeId)
        .pipe(catchError(() => of([] as Review[]))),
    }).subscribe(({ employees, assignments, cycle, managerReviews }) => {
      this.allEmployees = employees ?? [];
      this.subject = this.allEmployees.find(u => (u.userId ?? String(u.id)) === this.employeeId) ?? null;
      this.assignments = assignments ?? [];
      this.cycle = cycle ?? null;

      // Lock peer assignments once the manager has submitted their review
      const managerReview = (managerReviews ?? []).find((r: Review) =>
        r.reviewType === 'MANAGER' && r.cycleId === this.cycleId
      );
      const mgStatus = String(managerReview?.status ?? '').toUpperCase();
      this.selfReviewSubmitted = mgStatus === 'COMPLETED' || mgStatus === 'PUBLISHED';

      if (!fromUrl && this.subject?.reportingTo) {
        this.managerId = this.subject.reportingTo;
      }

      this.peer360Template = this.resolvePeer360Template(this.cycle);
      this.recomputeEligible();
      this.loading = false;
    });
  }

  /**
   * Walks the cycle's milestones for the first one whose templateSnapshot.reviewType is PEER_360,
   * and returns the (templateId, templateName, milestoneName) triple for the UI.
   * Returns null if the cycle has no PEER_360 milestone — in which case assignments are blocked.
   */
  private resolvePeer360Template(cycle: ReviewCycle | null): typeof this.peer360Template {
    if (!cycle || !Array.isArray(cycle.milestones)) return null;
    for (const m of cycle.milestones) {
      const snap: any = (m as CycleMilestone).templateSnapshot;
      if (snap && (snap.reviewType === 'PEER_360' || snap.reviewType === 'PEER 360')) {
        const templateId = snap.templateId;
        if (!templateId) continue;
        return {
          templateId,
          templateName: snap.templateName ?? snap.title ?? 'Peer 360 template',
          milestoneName: m.name ?? 'Peer 360',
        };
      }
    }
    return null;
  }

  recomputeEligible(): void {
    const subjectId = this.employeeId;
    const managerId = this.managerId;
    const assignedAuthorIds = new Set(this.assignments.map(a => a.authorUserId ?? ''));

    this.eligible = this.allEmployees
      .filter(u => {
        const id = u.userId ?? String(u.id);
        if (!id || id === subjectId) return false;
        if (u.status !== 'Active') return false;
        if (assignedAuthorIds.has(id)) return false;
        if (!this.showOutsideTeam && managerId) {
          if (u.reportingTo !== managerId) return false;
        }
        return true;
      })
      .map(u => ({ user: u, selected: false }));

    this.rebuildGroups();
  }

  onSearchChange(): void { this.rebuildGroups(); }

  rebuildGroups(): void {
    const q = this.searchQuery.toLowerCase().trim();
    const filtered = q
      ? this.eligible.filter(e => {
          const fullName = `${e.user.firstName} ${e.user.lastName}`.toLowerCase();
          const position = (e.user.position ?? '').toLowerCase();
          const email    = (e.user.email ?? '').toLowerCase();
          const mgr      = this.allEmployees.find(u => (u.userId ?? String((u as any).id)) === e.user.reportingTo);
          const mgrName  = mgr ? `${mgr.firstName} ${mgr.lastName}`.toLowerCase() : '';
          return fullName.includes(q) || position.includes(q) || email.includes(q) || mgrName.includes(q);
        })
      : this.eligible;

    const groups = new Map<string, EligiblePeer[]>();
    for (const e of filtered) {
      const rawKey = e.user.reportingTo ?? '__none__';
      const mgr = rawKey !== '__none__'
        ? this.allEmployees.find(u => (u.userId ?? String((u as any).id)) === rawKey)
        : null;
      const key = mgr ? rawKey : '__none__';
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key)!.push(e);
    }

    this.groupedEligible = Array.from(groups.entries())
      .filter(([, peers]) => peers.length > 0)
      .map(([managerId, peers]) => {
        const mgr = this.allEmployees.find(u => (u.userId ?? String((u as any).id)) === managerId);
        const managerName = mgr ? `${mgr.firstName} ${mgr.lastName}` : 'No Manager';
        return { managerId, managerName, peers };
      });
  }

  toggleGroup(managerId: string): void {
    if (this.collapsedGroups.has(managerId)) {
      this.collapsedGroups.delete(managerId);
    } else {
      this.collapsedGroups.add(managerId);
    }
    this.collapsedGroups = new Set(this.collapsedGroups);
  }

  isGroupCollapsed(managerId: string): boolean { return this.collapsedGroups.has(managerId); }

  groupSelectedCount(peers: EligiblePeer[]): number { return peers.filter(p => p.selected).length; }

  onScopeToggle(): void { this.recomputeEligible(); }

  selectedCount(): number {
    return this.eligible.reduce((acc, e) => acc + (e.selected ? 1 : 0), 0);
  }

  canAssign(): boolean {
    return !this.saving && this.selectedCount() > 0 && !!this.peer360Template?.templateId;
  }

  assignSelected(): void {
    if (!this.canAssign() || !this.peer360Template) return;
    this.saving = true;
    this.errorMessage = '';
    this.successMessage = '';

    const authorUserIds = this.eligible
      .filter(e => e.selected)
      .map(e => e.user.userId ?? String(e.user.id))
      .filter(id => id !== '');

    this.assignmentService
      .create(this.cycleId, {
        subjectUserId: this.employeeId,
        authorUserIds,
        templateId: this.peer360Template.templateId,
      })
      .subscribe({
        next: result => {
          this.saving = false;
          const n = result.created.length;
          this.successMessage = `Successfully assigned ${n} peer reviewer${n === 1 ? '' : 's'}.`;
          const created = result.created ?? [];
          this.assignments = [...this.assignments, ...created];
          this.recomputeEligible();
        },
        error: err => {
          this.saving = false;
          this.errorMessage = err?.error?.message ?? 'Could not assign reviewers. Please try again.';
        },
      });
  }

  canRemove(row: Review): boolean {
    return String(row.status ?? '').toUpperCase() === 'NOT_STARTED';
  }

  removeAssignment(row: Review): void {
    const reviewId = row.reviewId;
    if (!reviewId) return;
    if (!this.canRemove(row)) return;

    this.errorMessage = '';
    this.assignmentService.remove(this.cycleId, reviewId).subscribe({
      next: () => {
        this.assignments = this.assignments.filter(a => a.reviewId !== reviewId);
        this.recomputeEligible();
      },
      error: err => {
        if (err?.status === 409) {
          this.errorMessage = 'This peer has already started — cannot remove.';
        } else {
          this.errorMessage = err?.error?.message ?? 'Could not remove assignment.';
        }
      },
    });
  }

  authorName(authorUserId: string | undefined): string {
    if (!authorUserId) return '—';
    const match = this.allEmployees.find(u => (u.userId ?? String(u.id)) === authorUserId);
    if (match) return `${match.firstName} ${match.lastName}`;
    return authorUserId;
  }

  initials(user: User): string {
    return `${user.firstName?.[0] ?? ''}${user.lastName?.[0] ?? ''}`.toUpperCase();
  }

  statusLabel(status: string | undefined): string {
    const map: Record<string, string> = {
      NOT_STARTED: 'Not started', DRAFT: 'Draft',
      IN_PROGRESS: 'In Progress', COMPLETED: 'Completed', PUBLISHED: 'Published',
    };
    return map[(status ?? '').toUpperCase()] ?? (status || 'Not started');
  }

  statusClass(status: string | undefined): string {
    return (status ?? 'not_started').toLowerCase().replace(/[^a-z]/g, '-');
  }

  back(): void {
    this.router.navigate(['/my-team']);
  }
}
