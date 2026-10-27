import { Component, OnInit, AfterViewChecked, ViewChild, ElementRef, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { Chart, registerables } from 'chart.js';
import { EmployeeService, ReviewCycle, CycleMilestone } from '../../services/employees.services';
import { ReviewCyclesService } from '../../services/review-cycles.service';
import { User } from '../../models/employee.model';
import { Review } from '../../models/review.model';

Chart.register(...registerables);

type KpiStatus = 'ON_TRACK' | 'AT_RISK' | 'OVERDUE' | 'COMPLETED' | 'NOT_STARTED';
type MatrixTab = 'team' | 'discipline';
type ActionFilter = 'all' | 'overdue' | 'this_week';

interface MilestoneKpi {
  milestoneId: string;
  name: string;
  completed: number;
  total: number;
  pct: number;
  status: KpiStatus;
  startDate: string;
  endDate: string;
}

interface MatrixRow {
  group: string;
  headcount: number;
  milestones: { completed: number; total: number }[];
  published: number;
  overallPct: number;
  status: KpiStatus;
}

interface OutstandingAction {
  employeeName: string;
  employeeId: string;
  milestoneName: string;
  milestoneId: string;
  reviewType: string;
  dueDate: string;
  daysOverdue: number;
  responsiblePartyName: string;
  responsiblePartyId: string;
  reminderSent: boolean;
  reminderCooldownUntil: Date | null;
}

interface PeerReviewRow {
  employeeId: string;
  employeeName: string;
  managerName: string;
  totalPeers: number;
  completedPeers: number;
  status: 'Completed' | 'In Progress' | 'Not Started';
  peers: { name: string; status: string }[];
  expanded: boolean;
}

interface FlatPeerRow {
  peerName: string;
  employeeName: string;
  managerName: string;
  status: string;
}

const CHART_PALETTE = ['#4A90E2', '#2E2E2E', '#10b981', '#8b5cf6', '#f59e0b'];

@Component({
  selector: 'app-insights',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './insights.component.html',
  styleUrls: ['./insights.component.css'],
})
export class InsightsComponent implements OnInit, AfterViewChecked {
  @ViewChild('velocityCanvas') velocityCanvasRef?: ElementRef<HTMLCanvasElement>;

  private employeeService = inject(EmployeeService);
  private cyclesService = inject(ReviewCyclesService);

  cycles: ReviewCycle[] = [];
  selectedCycleId = '';
  selectedCycle: ReviewCycle | null = null;

  loading = true;
  dataLoading = false;

  private _allUsers: User[] = [];
  private _rawUsers: User[] = [];
  private reviews: Review[] = [];
  private employeeManagerMap = new Map<string, string>();

  kpiCards: MilestoneKpi[] = [];
  publishedCount = 0;
  healthScore = 0;
  publishedPct = 0;

  matrixTab: MatrixTab = 'team';
  matrixRows: MatrixRow[] = [];
  matrixSortCol = '';
  matrixSortDir: 'asc' | 'desc' = 'asc';

  allActions: OutstandingAction[] = [];
  actionFilter: ActionFilter = 'all';

  peerReviewRows: PeerReviewRow[] = [];
  flatPeerRows: FlatPeerRow[] = [];
  peerFilter: 'all' | 'completed' | 'in_progress' | 'not_started' = 'not_started';

  private chartInstance: Chart | null = null;
  private pendingChartInit = false;
  chartPalette = CHART_PALETTE;

  ngOnInit(): void {
    this.loadCycles();
  }

  ngAfterViewChecked(): void {
    if (this.pendingChartInit && this.velocityCanvasRef) {
      this.pendingChartInit = false;
      this.initVelocityChart();
    }
  }

  private loadCycles(): void {
    this.cyclesService.getCycles().pipe(catchError(() => of([]))).subscribe(cycles => {
      // ENH-009: order the dropdown most-recent-first by start date. Backend returns
      // DynamoDB scan order (arbitrary); without this the picker had no visible order.
      // The Active cycle is still auto-selected below regardless of position.
      this.cycles = (cycles as ReviewCycle[])
        .filter(c => c.status !== 'DRAFT')
        .sort((a, b) => (b.startDate ?? '').localeCompare(a.startDate ?? ''));
      const active = this.cycles.find(c => c.status === 'ACTIVE');
      this.selectedCycleId = active?.cycleId ?? this.cycles[0]?.cycleId ?? '';
      this.loading = false;
      if (this.selectedCycleId) this.loadData();
    });
  }

  onCycleChange(): void {
    this.loadData();
  }

  private loadData(): void {
    this.dataLoading = true;
    this.selectedCycle = this.cycles.find(c => c.cycleId === this.selectedCycleId) ?? null;
    this.destroyChart();

    forkJoin({
      reviews: this.employeeService.getReviewsByCycle(this.selectedCycleId).pipe(catchError(() => of([]))),
      users: this.employeeService.getEmployees().pipe(catchError(() => of([]))),
    }).subscribe(({ reviews, users }) => {
      this.reviews = reviews as Review[];
      this._rawUsers = users as User[];
      // Match cycle-summary's definition: employees only (no admins, no managers)
      this._allUsers = this._rawUsers.filter(u => u.status === 'Active' && u.role === 'EMPLOYEE');

      this.employeeManagerMap.clear();
      for (const r of this.reviews) {
        if (r.reviewType === 'MANAGER' && r.subjectUserId && r.authorUserId) {
          this.employeeManagerMap.set(r.subjectUserId, r.authorUserId);
        }
      }

      this.compute();
      this.dataLoading = false;
      this.pendingChartInit = true;
    });
  }

  private compute(): void {
    if (!this.selectedCycle) return;
    const milestones = this.selectedCycle.milestones ?? [];

    this.kpiCards = milestones
      .filter(m => !!m.templateSnapshot?.reviewType)
      .map(m => this.buildMilestoneKpi(m))
      .sort((a, b) => new Date(a.endDate).getTime() - new Date(b.endDate).getTime());

    const bySubject = new Map<string, Review[]>();
    for (const r of this.reviews) {
      if (!r.subjectUserId) continue;
      if (!bySubject.has(r.subjectUserId)) bySubject.set(r.subjectUserId, []);
      bySubject.get(r.subjectUserId)!.push(r);
    }

    this.publishedCount = this._allUsers.filter(u => {
      const userReviews = bySubject.get(u.userId!) ?? [];
      return userReviews.some(r => r.reviewType === 'MANAGER' && r.status === 'PUBLISHED');
    }).length;

    this.publishedPct = this._allUsers.length > 0
      ? Math.round((this.publishedCount / this._allUsers.length) * 100)
      : 0;

    const totalCompleted = this.kpiCards.reduce((s, k) => s + k.completed, 0);
    const totalPossible  = this.kpiCards.reduce((s, k) => s + k.total, 0);
    this.healthScore = totalPossible > 0
      ? Math.round((totalCompleted / totalPossible) * 100)
      : 0;

    this.buildMatrix();
    this.buildOutstandingActions(milestones);
    this.buildPeerReviewSection();
  }

  private buildMilestoneKpi(m: CycleMilestone): MilestoneKpi {
    const activeIds = new Set(this._allUsers.map(u => u.userId));

    const linkedReviews = this.reviews.filter(r =>
      r.milestoneId === m.milestoneId && r.subjectUserId && activeIds.has(r.subjectUserId));
    const effectiveType: string | null = linkedReviews.length > 0
      ? (linkedReviews[0].reviewType as string)
      : this.reviewTypeForMilestone(m);

    const isPeer = effectiveType === 'PEER_360';

    let total: number;
    let completed: number;

    if (isPeer) {
      // PEER_360: total = number of peer assignments (linked first, fallback to unlinked)
      const peerPool = linkedReviews.length > 0
        ? linkedReviews
        : this.reviews.filter(r => !r.milestoneId && r.reviewType === 'PEER_360' && r.subjectUserId && activeIds.has(r.subjectUserId));
      total = peerPool.length;
      completed = peerPool.filter(r => r.status === 'COMPLETED' || r.status === 'PUBLISHED').length;
    } else {
      // SELF / MANAGER: total = all active employees.
      // Merge linked (milestoneId match) + unlinked (same reviewType, no milestoneId) reviews,
      // deduplicating by subjectUserId so partial milestoneId tagging doesn't undercount.
      total = this._allUsers.length;
      const reviewType = effectiveType ?? this.reviewTypeForMilestone(m);
      const unlinked = reviewType
        ? this.reviews.filter(r => !r.milestoneId && r.reviewType === reviewType && r.subjectUserId && activeIds.has(r.subjectUserId))
        : [];
      const bySubject = new Map<string, Review>();
      for (const r of [...unlinked, ...linkedReviews]) {
        // linked reviews win over unlinked for the same subject
        if (!bySubject.has(r.subjectUserId!) || r.milestoneId === m.milestoneId) {
          bySubject.set(r.subjectUserId!, r);
        }
      }
      completed = Array.from(bySubject.values())
        .filter(r => r.status === 'COMPLETED' || r.status === 'PUBLISHED').length;
    }
    const pct = total > 0 ? Math.round((completed / total) * 100) : 0;
    return {
      milestoneId: m.milestoneId,
      name: m.name,
      completed: Math.min(completed, total),
      total,
      pct,
      status: this.calcMilestoneStatus(m, completed, total),
      startDate: m.startDate,
      endDate: m.endDate,
    };
  }

  private calcMilestoneStatus(m: CycleMilestone, completed: number, total: number): KpiStatus {
    const today = new Date(); today.setHours(0, 0, 0, 0);
    const end = new Date(m.endDate);
    const start = new Date(m.startDate);
    if (today > end) return completed >= total ? 'COMPLETED' : 'OVERDUE';
    if (today < start) return 'NOT_STARTED';
    const window = end.getTime() - start.getTime();
    const elapsed = today.getTime() - start.getTime();
    const doneFraction = total > 0 ? completed / total : 0;
    return doneFraction >= (elapsed / window) - 0.1 ? 'ON_TRACK' : 'AT_RISK';
  }

  private buildMatrix(): void {
    if (!this.selectedCycle) return;
    const milestones = this.milestones;
    const bySubject = new Map<string, Review[]>();
    for (const r of this.reviews) {
      if (!r.subjectUserId) continue;
      if (!bySubject.has(r.subjectUserId)) bySubject.set(r.subjectUserId, []);
      bySubject.get(r.subjectUserId)!.push(r);
    }
    const userMap = new Map<string, User>();
    for (const u of this._rawUsers) { if (u.userId) userMap.set(u.userId, u); }

    let groups: Map<string, User[]>;
    if (this.matrixTab === 'team') {
      groups = new Map();
for (const u of this._allUsers) {
        const managerId = this.employeeManagerMap.get(u.userId!) ?? (u as any).managerId ?? u.reportingTo ?? null;
        const mgr = managerId ? userMap.get(managerId) : null;
        const key = mgr ? `${mgr.firstName} ${mgr.lastName}`.trim() : 'No Manager';
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key)!.push(u);
      }
    } else if (this.matrixTab === 'discipline') {
      groups = new Map();
      for (const u of this._allUsers) {
        const key = u.discipline?.trim() || 'Unspecified';
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key)!.push(u);
      }
    } else {
      groups = new Map();
      for (const u of this._allUsers) {
        const key = u.discipline?.trim() || 'Unspecified';
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key)!.push(u);
      }
    }

    this.matrixRows = Array.from(groups.entries()).map(([group, users]) => {
      const headcount = users.length;
      const userIds = new Set(users.map(u => u.userId));
      const milestoneData = milestones.map(m => {
        const linkedInGroup = this.reviews.filter(r =>
          r.milestoneId === m.milestoneId && r.subjectUserId && userIds.has(r.subjectUserId));
        const effectiveType: string | null = linkedInGroup.length > 0
          ? (linkedInGroup[0].reviewType as string)
          : this.reviewTypeForMilestone(m);
        const isPeer = effectiveType === 'PEER_360';

        if (isPeer) {
          const peerPool = linkedInGroup.length > 0
            ? linkedInGroup
            : this.reviews.filter(r => !r.milestoneId && r.reviewType === 'PEER_360' && r.subjectUserId && userIds.has(r.subjectUserId));
          return { completed: peerPool.filter(r => r.status === 'COMPLETED' || r.status === 'PUBLISHED').length, total: peerPool.length };
        }

        // Merge linked + unlinked, deduplicate by subjectUserId
        const reviewType = effectiveType ?? this.reviewTypeForMilestone(m);
        const unlinkedInGroup = reviewType
          ? this.reviews.filter(r => !r.milestoneId && r.reviewType === reviewType && r.subjectUserId && userIds.has(r.subjectUserId))
          : [];
        const bySubject = new Map<string, Review>();
        for (const r of [...unlinkedInGroup, ...linkedInGroup]) {
          if (!bySubject.has(r.subjectUserId!) || r.milestoneId === m.milestoneId) {
            bySubject.set(r.subjectUserId!, r);
          }
        }
        const completed = Math.min(
          Array.from(bySubject.values()).filter(r => r.status === 'COMPLETED' || r.status === 'PUBLISHED').length,
          headcount
        );
        return { completed, total: headcount };
      });
      const published = users.filter(u => {
        const ur = bySubject.get(u.userId!) ?? [];
        return ur.some(r => r.reviewType === 'MANAGER' && r.status === 'PUBLISHED');
      }).length;
      const totalDone = milestoneData.reduce((s, md) => s + md.completed, 0);
      const totalPossible = milestoneData.reduce((s, md) => s + md.total, 0);
      const overallPct = totalPossible > 0 ? Math.round((totalDone / totalPossible) * 100) : 0;
      const status = this.worstOf(milestones.map((m, i) =>
        this.calcMilestoneStatus(m, milestoneData[i].completed, headcount)));
      return { group, headcount, milestones: milestoneData, published, overallPct, status };
    });
  }

  private reviewTypeForMilestone(m: CycleMilestone): string | null {
    if (m.templateSnapshot?.reviewType) return m.templateSnapshot.reviewType as string;
    if (m.assignedRoles?.includes('MANAGER')) return 'MANAGER';
    if (m.assignedRoles?.includes('EMPLOYEE')) return 'SELF';
    return null;
  }

  private buildPeerReviewSection(): void {
    const activeIds = new Set(this._allUsers.map(u => u.userId));
    const userMap = new Map<string, User>();
    for (const u of this._rawUsers) { if (u.userId) userMap.set(u.userId, u); }

    const peerReviews = this.reviews.filter(r =>
      r.reviewType === 'PEER_360' && r.subjectUserId && activeIds.has(r.subjectUserId));

    const bySubject = new Map<string, Review[]>();
    for (const r of peerReviews) {
      if (!bySubject.has(r.subjectUserId!)) bySubject.set(r.subjectUserId!, []);
      bySubject.get(r.subjectUserId!)!.push(r);
    }

    this.peerReviewRows = Array.from(bySubject.entries()).map(([subjectId, reviews]) => {
      const subject = userMap.get(subjectId);
      const managerId = this.employeeManagerMap.get(subjectId) ?? (subject as any)?.managerId ?? subject?.reportingTo ?? null;
      const manager = managerId ? userMap.get(managerId) : null;
      const completedPeers = reviews.filter(r => r.status === 'COMPLETED' || r.status === 'PUBLISHED').length;
      const status: PeerReviewRow['status'] =
        completedPeers === reviews.length ? 'Completed' :
        completedPeers > 0 ? 'In Progress' : 'Not Started';

      return {
        employeeId: subjectId,
        employeeName: subject ? `${subject.firstName} ${subject.lastName}`.trim() : subjectId,
        managerName: manager ? `${manager.firstName} ${manager.lastName}`.trim() : '—',
        totalPeers: reviews.length,
        completedPeers,
        status,
        peers: reviews.map(r => {
          const peer = r.authorUserId ? userMap.get(r.authorUserId) : null;
          return {
            name: peer ? `${peer.firstName} ${peer.lastName}`.trim() : r.authorUserId ?? '—',
            status: r.status ?? 'NOT_STARTED',
          };
        }),
        expanded: false,
      };
    }).sort((a, b) => a.employeeName.localeCompare(b.employeeName));

    this.flatPeerRows = this.peerReviewRows.flatMap(row =>
      row.peers.map(p => ({
        peerName: p.name,
        employeeName: row.employeeName,
        managerName: row.managerName,
        status: p.status,
      }))
    );
  }

  get filteredFlatPeerRows(): FlatPeerRow[] {
    if (this.peerFilter === 'completed')
      return this.flatPeerRows.filter(r => r.status === 'COMPLETED' || r.status === 'PUBLISHED');
    if (this.peerFilter === 'in_progress')
      return this.flatPeerRows.filter(r => r.status === 'IN_PROGRESS');
    if (this.peerFilter === 'not_started')
      return this.flatPeerRows.filter(r => r.status !== 'COMPLETED' && r.status !== 'PUBLISHED' && r.status !== 'IN_PROGRESS');
    return this.flatPeerRows;
  }

  togglePeerRow(row: PeerReviewRow): void { row.expanded = !row.expanded; }

  private worstOf(statuses: KpiStatus[]): KpiStatus {
    if (statuses.includes('OVERDUE')) return 'OVERDUE';
    if (statuses.includes('AT_RISK')) return 'AT_RISK';
    if (statuses.includes('ON_TRACK')) return 'ON_TRACK';
    if (statuses.includes('COMPLETED')) return 'COMPLETED';
    return 'NOT_STARTED';
  }

  private buildOutstandingActions(_milestones: CycleMilestone[]): void {
    const milestones = this.milestones;
    const today = new Date(); today.setHours(0, 0, 0, 0);
    const userMap = new Map<string, User>();
    for (const u of this._rawUsers) { if (u.userId) userMap.set(u.userId, u); }
    const actions: OutstandingAction[] = [];

    for (const m of milestones) {
      const end = new Date(m.endDate);
      const daysOverdue = Math.floor((today.getTime() - end.getTime()) / 86400000);
      if (daysOverdue < -7) continue;

      for (const u of this._allUsers) {
        const review = this.reviews.find(r =>
          r.milestoneId === m.milestoneId && r.subjectUserId === u.userId
        );
        if (review?.status === 'COMPLETED' || review?.status === 'PUBLISHED') continue;

        const reviewType = review?.reviewType ?? 'SELF';
        const managerId = this.employeeManagerMap.get(u.userId!) ?? u.reportingTo ?? null;
        const manager = managerId ? userMap.get(managerId) : null;
        const isManagerReview = reviewType === 'MANAGER';
        const responsibleParty = isManagerReview && manager ? manager : u;

        actions.push({
          employeeName: `${u.firstName} ${u.lastName}`.trim(),
          employeeId: u.userId!,
          milestoneName: m.name,
          milestoneId: m.milestoneId,
          reviewType,
          dueDate: m.endDate,
          daysOverdue,
          responsiblePartyName: `${responsibleParty.firstName} ${responsibleParty.lastName}`.trim(),
          responsiblePartyId: responsibleParty.userId!,
          reminderSent: false,
          reminderCooldownUntil: null,
        });
      }
    }
    this.allActions = actions.sort((a, b) => b.daysOverdue - a.daysOverdue);
  }

  // ── Velocity chart ─────────────────────────────────────────────────────────

  get chartHeight(): number {
    return Math.max(220, this.kpiCards.length * 68);
  }

  private initVelocityChart(): void {
    const canvas = this.velocityCanvasRef?.nativeElement;
    if (!canvas) return;

    this.destroyChart();
    if (!this.kpiCards.length) return;

    const maxX = Math.max(...this.kpiCards.map(k => k.total), 1);
    const palette = CHART_PALETTE;
    const kpiCards = this.kpiCards;

    this.chartInstance = new Chart(canvas, {
      type: 'bar',
      data: {
        labels: kpiCards.map(k => k.name),
        datasets: [
          {
            label: 'Completed',
            data: kpiCards.map(k => k.completed),
            backgroundColor: kpiCards.map((_, i) => palette[i % palette.length]),
            borderRadius: 4,
            borderSkipped: false,
            barPercentage: 0.55,
          },
          {
            label: 'Remaining',
            data: kpiCards.map(k => k.total - k.completed),
            backgroundColor: 'rgba(226,232,240,0.55)',
            borderRadius: 4,
            borderSkipped: false,
            barPercentage: 0.55,
          },
        ],
      },
      options: {
        indexAxis: 'y',
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { display: false },
          tooltip: {
            callbacks: {
              title: (items) => items[0]?.label ?? '',
              label: (ctx) => {
                const k = kpiCards[ctx.dataIndex];
                if (!k) return '';
                return ctx.datasetIndex === 0
                  ? `  Completed: ${k.completed} / ${k.total}  (${k.pct}%)`
                  : `  Remaining: ${k.total - k.completed}`;
              },
            },
          },
        },
        scales: {
          x: {
            stacked: true,
            min: 0,
            max: maxX,
            border: { display: false },
            grid: { color: 'rgba(226,232,240,0.7)' },
            ticks: { color: '#94a3b8', font: { size: 11 }, maxTicksLimit: 6 },
          },
          y: {
            stacked: true,
            border: { display: false },
            grid: { display: false },
            ticks: { color: '#2E2E2E', font: { size: 12.5, weight: '600' as any }, padding: 8 },
          },
        },
      },
      plugins: [{
        id: 'pctLabels',
        afterDraw(chart: Chart) {
          const ctx = chart.ctx;
          const meta = chart.getDatasetMeta(0);
          meta.data.forEach((bar: any, i: number) => {
            const k = kpiCards[i];
            if (!k || k.pct === 0) return;
            const label = `${k.pct}%`;
            ctx.save();
            ctx.fillStyle = '#626262';
            ctx.font = '600 11px system-ui, sans-serif';
            ctx.textBaseline = 'middle';
            ctx.textAlign = 'left';
            ctx.fillText(label, bar.x + 7, bar.y);
            ctx.restore();
          });
        },
      }],
    });
  }

  private destroyChart(): void {
    if (this.chartInstance) {
      this.chartInstance.destroy();
      this.chartInstance = null;
    }
  }

  // ── Getters & helpers ──────────────────────────────────────────────────────

  get allUsers(): User[] { return this._allUsers; }

  get overdueCount(): number { return this.allActions.filter(a => a.daysOverdue > 0).length; }

  get filteredActions(): OutstandingAction[] {
    return this.allActions.filter(a => {
      if (this.actionFilter === 'overdue') return a.daysOverdue > 0;
      if (this.actionFilter === 'this_week') return a.daysOverdue <= 0 && a.daysOverdue >= -7;
      return true;
    });
  }

  get cycleLabel(): string {
    if (!this.selectedCycle) return '';
    const s = new Date(this.selectedCycle.startDate);
    const e = new Date(this.selectedCycle.dueDate);
    const fmt = (d: Date) => d.toLocaleDateString('en-AU', { day: 'numeric', month: 'short', year: 'numeric' });
    return `${this.selectedCycle.cycleTitle} · ${fmt(s)} – ${fmt(e)}`;
  }

  get milestones(): CycleMilestone[] {
    return (this.selectedCycle?.milestones ?? []).filter(m => !!m.templateSnapshot?.reviewType);
  }

  get kpiGridCols(): string {
    const total = (this.selectedCycle?.milestones?.length ?? 0) + 2;
    // ≤5 → single row; >5 → two balanced rows
    const cols = total <= 5 ? total : Math.ceil(total / 2);
    return `repeat(${cols}, 1fr)`;
  }

  setMatrixTab(tab: MatrixTab): void {
    this.matrixTab = tab;
    this.buildMatrix();
  }

  sortMatrix(col: string): void {
    if (this.matrixSortCol === col) {
      this.matrixSortDir = this.matrixSortDir === 'asc' ? 'desc' : 'asc';
    } else {
      this.matrixSortCol = col;
      this.matrixSortDir = 'asc';
    }
    this.matrixRows = [...this.matrixRows].sort((a, b) => {
      let av: any, bv: any;
      if (col === 'group') { av = a.group; bv = b.group; }
      else if (col === 'headcount') { av = a.headcount; bv = b.headcount; }
      else if (col === 'published') { av = a.published; bv = b.published; }
      else if (col === 'overallPct') { av = a.overallPct; bv = b.overallPct; }
      else {
        const idx = parseInt(col.replace('m_', ''), 10);
        av = a.milestones[idx] ? a.milestones[idx].completed / (a.milestones[idx].total || 1) : 0;
        bv = b.milestones[idx] ? b.milestones[idx].completed / (b.milestones[idx].total || 1) : 0;
      }
      const cmp = typeof av === 'string' ? av.localeCompare(bv) : av - bv;
      return this.matrixSortDir === 'asc' ? cmp : -cmp;
    });
  }

  sendReminder(action: OutstandingAction): void {
    action.reminderSent = true;
    action.reminderCooldownUntil = new Date(Date.now() + 24 * 3600 * 1000);
  }

  isReminderDisabled(action: OutstandingAction): boolean {
    if (!action.reminderSent) return false;
    return action.reminderCooldownUntil ? new Date() < action.reminderCooldownUntil : false;
  }

  formatDate(d: string): string {
    if (!d) return '—';
    return new Date(d).toLocaleDateString('en-AU', { day: 'numeric', month: 'short', year: 'numeric' });
  }

  statusClass(s: KpiStatus): string {
    return { ON_TRACK: 'chip-on-track', AT_RISK: 'chip-at-risk', OVERDUE: 'chip-overdue',
      COMPLETED: 'chip-completed', NOT_STARTED: 'chip-not-started' }[s] ?? 'chip-not-started';
  }

  statusLabel(s: KpiStatus): string {
    return { ON_TRACK: 'On Track', AT_RISK: 'At Risk', OVERDUE: 'Overdue',
      COMPLETED: 'Completed', NOT_STARTED: 'Not Started' }[s] ?? 'Not Started';
  }
}
