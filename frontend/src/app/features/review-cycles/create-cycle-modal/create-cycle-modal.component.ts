import { Component, Output, EventEmitter, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, NgForm } from '@angular/forms';
import { TemplateSnapshot } from '../template-builder/models/template.model';
import { TemplateBuilderDrawerComponent, DrawerMode } from '../template-builder/components/template-builder-drawer/template-builder-drawer.component';
import { MilestoneTemplateSectionComponent } from '../template-builder/components/milestone-template-section/milestone-template-section.component';

export type MilestoneTrigger = 'NONE' | 'EMPLOYEE_SELF_REVIEW' | 'MANAGER_REVIEW' | 'ADMIN_REVIEW';

export interface MilestoneData {
  milestoneId?: string;
  name: string;
  startDate: string;
  endDate: string;
  description?: string;
  trigger: MilestoneTrigger;
  assignedRoles?: string[];
  templateSnapshot?: TemplateSnapshot;
}

export interface CreateCycleData {
  cycleTitle: string;
  startDate: string;
  dueDate: string;
  status: string;
  milestones: MilestoneData[];
  publishAfter?: boolean;
}

@Component({
  selector: 'app-create-cycle-modal',
  standalone: true,
  imports: [CommonModule, FormsModule, TemplateBuilderDrawerComponent, MilestoneTemplateSectionComponent],
  templateUrl: './create-cycle-modal.component.html',
  styleUrls: ['../modal-shared.css', './create-cycle-modal.component.css'],
})
export class CreateCycleModalComponent {
  @Output() closed    = new EventEmitter<void>();
  @Output() submitted = new EventEmitter<CreateCycleData>();

  cycle = { cycleTitle: '', startDate: '', dueDate: '', status: 'DRAFT' };
  milestones: MilestoneData[] = [];

  /* Drawer state */
  drawerOpen        = signal(false);
  drawerMode        = signal<DrawerMode>('build');
  drawerMilestoneIdx = signal<number>(-1);

  openBuildDrawer(origIdx: number): void {
    this.drawerMilestoneIdx.set(origIdx);
    this.drawerMode.set('build');
    this.drawerOpen.set(true);
  }

  openBrowseDrawer(origIdx: number): void {
    this.drawerMilestoneIdx.set(origIdx);
    this.drawerMode.set('browse');
    this.drawerOpen.set(true);
  }

  openEditDrawer(origIdx: number): void {
    this.drawerMilestoneIdx.set(origIdx);
    this.drawerMode.set('edit');
    this.drawerOpen.set(true);
  }

  onDrawerClosed(): void { this.drawerOpen.set(false); }

  onTemplateAttached(snapshot: TemplateSnapshot): void {
    const idx = this.drawerMilestoneIdx();
    if (idx >= 0 && idx < this.milestones.length) {
      this.milestones[idx] = { ...this.milestones[idx], templateSnapshot: snapshot };
    }
    this.drawerOpen.set(false);
  }

  removeTemplate(origIdx: number): void {
    this.milestones[origIdx] = { ...this.milestones[origIdx], templateSnapshot: undefined };
  }

  get activeEditQuestions() {
    const idx = this.drawerMilestoneIdx();
    return idx >= 0 ? (this.milestones[idx]?.templateSnapshot?.questions ?? []) : [];
  }
  dateRangeError = '';

  get sortedIndices(): number[] {
    return this.milestones
      .map((_, i) => i)
      .sort((a, b) => {
        const da = this.milestones[a].startDate;
        const db = this.milestones[b].startDate;
        if (!da && !db) return 0;
        if (!da) return 1;
        if (!db) return -1;
        return da.localeCompare(db);
      });
  }

  trackByOrigIdx(_: number, origIdx: number): number { return origIdx; }

  private ms(si: number): MilestoneData {
    return this.milestones[this.sortedIndices[si]];
  }

  private formatDate(d: Date): string {
    const mm = String(d.getMonth() + 1).padStart(2, '0');
    const dd = String(d.getDate()).padStart(2, '0');
    return `${d.getFullYear()}-${mm}-${dd}`;
  }

  private dayOffset(dateStr: string, days: number): string {
    const d = new Date(dateStr + 'T00:00:00');
    d.setDate(d.getDate() + days);
    return this.formatDate(d);
  }

  private laterDate(a: string, b: string): string {
    if (!a) return b;
    if (!b) return a;
    return a > b ? a : b;
  }

  get today(): string {
    const d = new Date();
    d.setHours(0, 0, 0, 0);
    return this.formatDate(d);
  }

  get cycleStartMin(): string { return this.today; }
  get cycleStartMax(): string { return this.cycle.dueDate ? this.dayOffset(this.cycle.dueDate, -1) : ''; }
  get cycleEndMin():   string { return this.cycle.startDate ? this.dayOffset(this.cycle.startDate, 1) : ''; }

  getMilestoneStartMin(si: number): string { return this.laterDate(this.cycle.startDate, this.today); }
  getMilestoneStartMax(si: number): string { return this.cycle.dueDate ? this.dayOffset(this.cycle.dueDate, -1) : ''; }
  getMilestoneEndMin(si: number):   string { return this.ms(si).startDate ? this.dayOffset(this.ms(si).startDate, 1) : ''; }
  getMilestoneEndMax(si: number):   string { return this.cycle.dueDate || ''; }

  hasGapAfter(si: number): boolean {
    if (si >= this.sortedIndices.length - 1) return false;
    const curr = this.ms(si);
    const next = this.ms(si + 1);
    return !!(curr.endDate && next.startDate && curr.endDate < next.startDate);
  }

  private triggerToRoles(trigger: MilestoneTrigger): string[] {
    const map: Record<MilestoneTrigger, string[]> = {
      'NONE':                 [],
      'EMPLOYEE_SELF_REVIEW': ['EMPLOYEE'],
      'MANAGER_REVIEW':       ['MANAGER'],
      'ADMIN_REVIEW':         ['ADMIN'],
    };
    return map[trigger] ?? [];
  }

  private buildMilestones(list: MilestoneData[]): MilestoneData[] {
    return list.map(m => ({ ...m, assignedRoles: this.triggerToRoles(m.trigger) }));
  }

  addMilestone() {
    this.milestones.push({ name: '', startDate: this.cycle.startDate || '', endDate: '', description: '', trigger: 'NONE' });
  }

  removeMilestone(origIdx: number) {
    this.milestones.splice(origIdx, 1);
  }

  validateCycleDates(): boolean {
    if (this.cycle.startDate && this.cycle.dueDate && this.cycle.startDate >= this.cycle.dueDate) {
      this.dateRangeError = this.cycle.startDate === this.cycle.dueDate
        ? 'Start and end date cannot be the same.'
        : 'End date must be after start date.';
      return false;
    }
    this.dateRangeError = '';
    return true;
  }

  onCycleDateChange() { this.validateCycleDates(); }

  currentStep = 1; // 1 = basic info, 2 = milestones
  showSubmitError = false;
  publishError = '';

  goToStep2(form: NgForm): void {
    // Mark step-1 fields as touched to trigger validation
    form.controls['cycleName']?.markAsTouched();
    form.controls['startDate']?.markAsTouched();
    form.controls['endDate']?.markAsTouched();
    const datesValid = this.validateCycleDates();
    const step1Valid = !form.controls['cycleName']?.invalid &&
                       !form.controls['startDate']?.invalid &&
                       !form.controls['endDate']?.invalid &&
                       datesValid;
    if (!step1Valid) { this.showSubmitError = true; return; }
    this.showSubmitError = false;
    this.currentStep = 2;
  }
  showPublishConfirm = false;
  private pendingPublishPayload: CreateCycleData | null = null;

  onSaveDraft(form: NgForm) {
    const datesValid = this.validateCycleDates();
    if (!datesValid) {
      this.showSubmitError = true;
      return;
    }
    this.showSubmitError = false;
    this.publishError = '';
    const validMilestones = this.milestones.filter(m => m.name.trim());
    this.submitted.emit({
      cycleTitle:   this.cycle.cycleTitle.trim() || 'Untitled Cycle',
      startDate:    this.cycle.startDate,
      dueDate:      this.cycle.dueDate,
      status:       'DRAFT',
      milestones:   this.buildMilestones(validMilestones),
      publishAfter: false
    });
  }

  onPublish(form: NgForm) {
    form.control.markAllAsTouched();
    const datesValid = this.validateCycleDates();
    const validMilestones = this.milestones.filter(m => m.name.trim());

    if (!this.cycle.cycleTitle.trim()) {
      this.publishError = 'Cycle name is required to publish.';
      return;
    }
    if (!this.cycle.startDate || !this.cycle.dueDate || !datesValid) {
      this.publishError = 'Valid start and end dates are required to publish.';
      return;
    }
    if (validMilestones.length === 0) {
      this.publishError = 'At least one milestone is required to publish.';
      return;
    }
    if (validMilestones.some(m => m.trigger === 'NONE')) {
      this.publishError = 'All milestones must have a role selected to publish.';
      return;
    }
    if (form.invalid) {
      this.publishError = 'Fix all highlighted fields before publishing.';
      setTimeout(() => {
        document.querySelector<HTMLElement>(
          '.cycle-modal .invalid, .cycle-modal .ng-invalid.ng-touched'
        )?.scrollIntoView({ behavior: 'smooth', block: 'center' });
      }, 50);
      return;
    }

    this.showSubmitError = false;
    this.publishError = '';
    this.pendingPublishPayload = {
      cycleTitle:   this.cycle.cycleTitle.trim(),
      startDate:    this.cycle.startDate,
      dueDate:      this.cycle.dueDate,
      status:       'DRAFT',
      milestones:   this.buildMilestones(validMilestones),
      publishAfter: true
    };
    this.showPublishConfirm = true;
  }

  confirmPublish() {
    if (this.pendingPublishPayload) {
      this.submitted.emit(this.pendingPublishPayload);
    }
    this.showPublishConfirm = false;
  }

  cancelPublish() {
    this.showPublishConfirm = false;
    this.pendingPublishPayload = null;
  }

  close() { this.closed.emit(); }
}
