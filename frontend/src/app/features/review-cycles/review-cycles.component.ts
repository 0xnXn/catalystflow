import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { CreateCycleModalComponent, CreateCycleData } from './create-cycle-modal/create-cycle-modal.component';
import { EditCycleModalComponent } from './edit-cycle-modal/edit-cycle-modal.component';
import { ReviewCyclesService } from '../../services/review-cycles.service';
import { SkeletonComponent } from '../../shared/skeleton/skeleton.component';

@Component({
  selector: 'app-review-cycles',
  standalone: true,
  imports: [CommonModule, FormsModule, CreateCycleModalComponent, EditCycleModalComponent, SkeletonComponent],
  templateUrl: './review-cycles.component.html',
  styleUrls: ['./review-cycles.component.css']
})
export class ReviewCyclesComponent implements OnInit {
  isCreateModalOpen = false;
  // Default to true so the skeleton shows on the very first render before
  // loadCycles() finishes — otherwise the empty-state flashes briefly.
  isLoading = true;
  searchTerm = '';

  cycles: any[] = [];
  filteredCycles: any[] = [];
  expandedCycleId: string | null = null;
  deleteConfirm: { show: boolean; cycle: any } = { show: false, cycle: null };
  endConfirm: { show: boolean; cycle: any } = { show: false, cycle: null };
  editTarget: any = null;

  notification: { show: boolean; message: string; type: 'success' | 'error' } = {
    show: false, message: '', type: 'success'
  };

  constructor(private cyclesService: ReviewCyclesService) {}

  ngOnInit() { this.loadCycles(); }

  loadCycles() {
    this.isLoading = true;
    this.cyclesService.getCycles().subscribe({
      next: (data: any) => {
        this.cycles = Array.isArray(data) ? data : (data.cycles ?? []);
        this.filter();
        this.isLoading = false;
      },
      error: () => {
        this.showNotification('Failed to load cycles.', 'error');
        this.isLoading = false;
      }
    });
  }

  filter() {
    const s = this.searchTerm.toLowerCase();
    const statusOrder: Record<string, number> = { ACTIVE: 0, DRAFT: 1, COMPLETED: 2, SCHEDULED: 3 };
    const base = s
      ? this.cycles.filter(c =>
          c.cycleTitle?.toLowerCase().includes(s) || c.status?.toLowerCase().includes(s)
        )
      : [...this.cycles];

    this.filteredCycles = base.sort((a, b) => {
      const statusDiff = (statusOrder[a.status] ?? 99) - (statusOrder[b.status] ?? 99);
      if (statusDiff !== 0) return statusDiff;
      return new Date(b.startDate).getTime() - new Date(a.startDate).getTime();
    });
  }

  toggleExpand(cycleId: string) {
    this.expandedCycleId = this.expandedCycleId === cycleId ? null : cycleId;
  }

  get totalCount()     { return this.cycles.length; }
  get draftCount()     { return this.cycles.filter(c => c.status === 'DRAFT').length; }
  get activeCount()    { return this.cycles.filter(c => c.status === 'ACTIVE').length; }
  get completedCount() { return this.cycles.filter(c => c.status === 'COMPLETED').length; }

  get currentCycles(): any[] {
    return this.filteredCycles.filter(c => c.status !== 'COMPLETED');
  }

  get pastCycles(): any[] {
    return this.filteredCycles.filter(c => c.status === 'COMPLETED');
  }

  private today = new Date().toISOString().split('T')[0];

  isMilestoneDone(m: any, cycleStatus?: string): boolean {
    // If parent cycle is completed, all milestones are done
    if (cycleStatus === 'COMPLETED') return true;
    if (m.status === 'COMPLETED' || m.status === 'COMPLETE') return true;
    // Auto-mark as done if end date has passed
    if (m.endDate) {
      const parts = m.endDate.match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/);
      const end = parts
        ? `${parts[3]}-${parts[2].padStart(2,'0')}-${parts[1].padStart(2,'0')}`
        : m.endDate;
      return end < this.today;
    }
    return false;
  }

  completedMilestones(cycle: any): number {
    return (cycle.milestones ?? []).filter((m: any) =>
      this.isMilestoneDone(m, cycle.status)
    ).length;
  }

  formatDate(dateStr?: string): string {
    if (!dateStr) return '—';
    const parts = dateStr.match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/);
    const d = parts
      ? new Date(+parts[3], +parts[2] - 1, +parts[1])
      : new Date(dateStr);
    if (isNaN(d.getTime())) return dateStr;
    return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
  }

  openCreateModal()  { this.isCreateModalOpen = true; }
  closeCreateModal() { this.isCreateModalOpen = false; }

  openEditModal(cycle: any)  { this.editTarget = cycle; }
  closeEditModal()            { this.editTarget = null; }

  onCycleSaved(updated: any): void {
    this.cyclesService.updateCycle(updated.cycleId, updated).subscribe({
      next: () => {
        if (updated.publishAfter) {
          this.cyclesService.publishCycle(updated.cycleId).subscribe({
            next: () => {
              this.editTarget = null;
              this.loadCycles();
              this.showNotification(`"${updated.cycleTitle}" published and scheduled.`);
            },
            error: () => this.showNotification('Saved but publish failed.', 'error')
          });
        } else {
          this.editTarget = null;
          this.loadCycles();
          this.showNotification(`"${updated.cycleTitle}" saved as draft.`);
        }
      },
      error: () => {
        this.showNotification('Failed to save changes.', 'error');
      }
    });
  }

  onCycleCreated(data: CreateCycleData): void {
    this.cyclesService.createCycle(data).subscribe({
      next: (created: any) => {
        this.isCreateModalOpen = false;
        if (data.publishAfter) {
          this.cyclesService.publishCycle(created.cycleId).subscribe({
            next: () => {
              this.loadCycles();
              this.showNotification(`"${data.cycleTitle}" published and scheduled.`);
            },
            error: () => this.showNotification('Cycle saved but publish failed.', 'error')
          });
        } else {
          this.loadCycles();
          this.showNotification(`"${data.cycleTitle}" saved as draft.`);
        }
      },
      error: () => {
        this.showNotification('Failed to create cycle.', 'error');
      }
    });
  }

  openDeleteConfirm(cycle: any) { this.deleteConfirm = { show: true, cycle }; }
  closeDeleteConfirm()           { this.deleteConfirm = { show: false, cycle: null }; }

  confirmDelete() {
    const cycle = this.deleteConfirm.cycle;
    this.closeDeleteConfirm();
    this.cyclesService.deleteCycle(cycle.cycleId).subscribe({
      next: () => {
        this.loadCycles();
        this.showNotification(`"${cycle.cycleTitle}" deleted.`);
      },
      error: () => {
        this.showNotification('Failed to delete cycle.', 'error');
      }
    });
  }

  /** SCHEDULED → ACTIVE — direct, no confirm modal (the action is reversible-ish via end-cycle). */
  activateCycle(cycle: any) {
    this.cyclesService.activateCycle(cycle.cycleId).subscribe({
      next: () => {
        this.loadCycles();
        this.showNotification(`"${cycle.cycleTitle}" activated.`);
      },
      error: (err: any) => {
        const msg = err?.error?.message ?? 'Failed to activate cycle.';
        this.showNotification(msg, 'error');
      }
    });
  }

  /** ACTIVE → COMPLETED — gated behind a confirmation modal because it's terminal. */
  openEndConfirm(cycle: any) { this.endConfirm = { show: true, cycle }; }
  closeEndConfirm()           { this.endConfirm = { show: false, cycle: null }; }

  confirmEnd() {
    const cycle = this.endConfirm.cycle;
    this.closeEndConfirm();
    this.cyclesService.completeCycle(cycle.cycleId).subscribe({
      next: () => {
        this.loadCycles();
        this.showNotification(`"${cycle.cycleTitle}" ended. Status is now COMPLETED.`);
      },
      error: (err: any) => {
        const msg = err?.error?.message ?? 'Failed to end cycle.';
        this.showNotification(msg, 'error');
      }
    });
  }

  showNotification(message: string, type: 'success' | 'error' = 'success') {
    this.notification = { show: true, message, type };
    setTimeout(() => { this.notification.show = false; }, 4000);
  }
}
