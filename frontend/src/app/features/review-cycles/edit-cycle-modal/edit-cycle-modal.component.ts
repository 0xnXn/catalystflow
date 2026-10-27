import { Component, Input, Output, EventEmitter, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, NgForm } from '@angular/forms';
import { MilestoneData, MilestoneTrigger } from '../create-cycle-modal/create-cycle-modal.component';
import { TemplateSnapshot } from '../template-builder/models/template.model';
import { TemplateBuilderDrawerComponent, DrawerMode } from '../template-builder/components/template-builder-drawer/template-builder-drawer.component';
import { MilestoneTemplateSectionComponent } from '../template-builder/components/milestone-template-section/milestone-template-section.component';

@Component({
  selector: 'app-edit-cycle-modal',
  standalone: true,
  imports: [CommonModule, FormsModule, TemplateBuilderDrawerComponent, MilestoneTemplateSectionComponent],
  templateUrl: './edit-cycle-modal.component.html',
  styleUrls: [
    '../modal-shared.css',
    '../create-cycle-modal/create-cycle-modal.component.css',
    './edit-cycle-modal.component.css'
  ]
})
export class EditCycleModalComponent implements OnInit {
  @Input() cycle!: any;
  @Output() closed = new EventEmitter<void>();
  @Output() saved  = new EventEmitter<any>();

  editCycle = { cycleTitle: '', startDate: '', dueDate: '' };
  milestones: MilestoneData[] = [];
  dateRangeError = '';
  showSubmitError = false;
  currentStep = 1;

  goToStep2(form: NgForm): void {
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

  readonly today = new Date().toISOString().split('T')[0];

  ngOnInit() {
    this.editCycle = {
      cycleTitle: this.cycle.cycleTitle ?? '',
      startDate:  this.cycle.startDate  ?? '',
      dueDate:    this.cycle.dueDate    ?? '',
    };
    this.milestones = (this.cycle.milestones ?? []).map((m: any) => ({
      ...m,
      trigger: (m.trigger as MilestoneTrigger) ?? 'NONE'
    }));
  }

  // ── STATUS HELPERS ────────────────────────────────────────────
  get status():      string  { return this.cycle?.status ?? ''; }
  get isDraft():     boolean { return this.status === 'DRAFT'; }
  get isScheduled(): boolean { return this.status === 'SCHEDULED'; }
  get isActive():    boolean { return this.status === 'ACTIVE'; }
  get isCompleted(): boolean { return this.status === 'COMPLETED'; }

  get canEditCycleStart(): boolean { return this.isDraft || this.isScheduled; }
  get canEditCycleEnd():   boolean { return true; }
  get canAddMilestone():   boolean { return !this.isCompleted && this.milestones.length < 15; }

  // ── MILESTONE STATE ───────────────────────────────────────────
  milestoneState(m: MilestoneData): 'completed' | 'current' | 'future' {
    if (!this.isActive) return 'future';
    if (m.endDate   && m.endDate   <  this.today) return 'completed';
    if (m.startDate && m.startDate <= this.today) return 'current';
    return 'future';
  }

  canEditMilestoneName(origIdx: number): boolean {
    if (this.isCompleted) return false;
    return this.milestoneState(this.milestones[origIdx]) !== 'completed';
  }

  canEditMilestoneStart(origIdx: number): boolean {
    if (this.isCompleted) return false;
    if (this.isActive) return this.milestoneState(this.milestones[origIdx]) === 'future';
    return true;
  }

  canEditMilestoneEnd(origIdx: number): boolean {
    if (this.isCompleted) return true;
    return this.milestoneState(this.milestones[origIdx]) !== 'completed';
  }

  canDeleteMilestone(origIdx: number): boolean {
    if (this.isCompleted) return false;
    const s = this.milestoneState(this.milestones[origIdx]);
    if (this.isActive && (s === 'completed' || s === 'current')) return false;
    return true;
  }

  // ── SORTED INDICES ────────────────────────────────────────────
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

  // ── DATE UTILITIES ────────────────────────────────────────────
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

  get cycleStartMax(): string {
    return this.editCycle.dueDate ? this.dayOffset(this.editCycle.dueDate, -1) : '';
  }
  get cycleEndMin(): string {
    if (this.isActive || this.isCompleted) return this.today;
    return this.editCycle.startDate ? this.dayOffset(this.editCycle.startDate, 1) : '';
  }

  getMilestoneStartMin(si: number): string { return this.editCycle.startDate || ''; }
  getMilestoneStartMax(si: number): string { return this.editCycle.dueDate || ''; }

  getMilestoneEndMin(si: number): string {
    if (this.isCompleted) return this.today;
    const m = this.ms(si);
    if (this.isActive && this.milestoneState(m) === 'current') return this.today;
    return m.startDate || '';
  }
  getMilestoneEndMax(si: number): string { return this.editCycle.dueDate || ''; }

  // ── GAP ───────────────────────────────────────────────────────
  hasGapAfter(si: number): boolean {
    if (si >= this.sortedIndices.length - 1) return false;
    const curr = this.ms(si);
    const next = this.ms(si + 1);
    return !!(curr.endDate && next.startDate && curr.endDate < next.startDate);
  }

  // ── TEMPLATE DRAWER ───────────────────────────────────────────
  drawerOpen         = signal(false);
  drawerMode         = signal<DrawerMode>('build');
  drawerMilestoneIdx = signal<number>(-1);

  canEditMilestoneTemplate(origIdx: number): boolean {
    if (this.isCompleted) return false;
    if (this.isActive && this.milestoneState(this.milestones[origIdx]) !== 'future') return false;
    return true;
  }

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

  // ── MILESTONE ACTIONS ─────────────────────────────────────────
  addMilestone() {
    if (this.milestones.length >= 15) return;
    // BUG-008: on an ACTIVE cycle the cycle start is in the past, so seeding it here
    // forced the new milestone into 'current' state — which locks BOTH its start date
    // and its template (a dead end: no template can ever be attached). Seed a future
    // start (tomorrow) on active cycles so milestoneState() resolves to 'future' and the
    // start + template stay editable. Draft/scheduled cycles keep the cycle-start prefill.
    const start = this.isActive ? this.dayOffset(this.today, 1) : (this.editCycle.startDate || '');
    this.milestones.push({ name: '', startDate: start, endDate: '', description: '', trigger: 'NONE' });
  }

  removeMilestone(origIdx: number) {
    if (!this.canDeleteMilestone(origIdx)) return;
    this.milestones.splice(origIdx, 1);
  }

  // ── VALIDATION ────────────────────────────────────────────────
  validateCycleDates(): boolean {
    if (this.editCycle.startDate && this.editCycle.dueDate &&
        this.editCycle.startDate >= this.editCycle.dueDate) {
      this.dateRangeError = 'End date must be after start date.';
      return false;
    }
    this.dateRangeError = '';
    return true;
  }

  onCycleDateChange() { this.validateCycleDates(); }

  // ── SUBMIT ────────────────────────────────────────────────────
  publishError = '';
  showPublishConfirm = false;
  private pendingPublishPayload: any = null;

  private triggerToRoles(trigger: MilestoneTrigger): string[] {
    const map: Record<MilestoneTrigger, string[]> = {
      'NONE':                 [],
      'EMPLOYEE_SELF_REVIEW': ['EMPLOYEE'],
      'MANAGER_REVIEW':       ['MANAGER'],
      'ADMIN_REVIEW':         ['ADMIN'],
    };
    return map[trigger] ?? [];
  }

  private buildPayload(publishAfter: boolean): any {
    return {
      ...this.cycle,
      cycleTitle:  this.editCycle.cycleTitle.trim(),
      startDate:   this.editCycle.startDate,
      dueDate:     this.editCycle.dueDate,
      milestones:  this.milestones
        .filter(m => m.name.trim())
        .map(m => ({ ...m, assignedRoles: this.triggerToRoles(m.trigger) })),
      publishAfter,
    };
  }

  onSaveDraft(form: NgForm) {
    const datesValid = this.validateCycleDates();
    if (!datesValid) {
      this.showSubmitError = true;
      return;
    }
    this.showSubmitError = false;
    this.publishError = '';
    this.saved.emit(this.buildPayload(false));
  }

  onPublish(form: NgForm) {
    form.control.markAllAsTouched();
    const datesValid = this.validateCycleDates();
    const validMilestones = this.milestones.filter(m => m.name.trim());

    if (!this.editCycle.cycleTitle.trim()) {
      this.publishError = 'Cycle name is required to publish.';
      return;
    }
    if (!this.editCycle.startDate || !this.editCycle.dueDate || !datesValid) {
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
          '.edit-cycle-modal .invalid, .edit-cycle-modal .ng-invalid.ng-touched'
        )?.scrollIntoView({ behavior: 'smooth', block: 'center' });
      }, 50);
      return;
    }

    this.showSubmitError = false;
    this.publishError = '';
    this.pendingPublishPayload = this.buildPayload(true);
    this.showPublishConfirm = true;
  }

  confirmPublish() {
    if (this.pendingPublishPayload) {
      this.saved.emit(this.pendingPublishPayload);
    }
    this.showPublishConfirm = false;
  }

  cancelPublish() {
    this.showPublishConfirm = false;
    this.pendingPublishPayload = null;
  }

  onSaveChanges(form: NgForm) {
    form.control.markAllAsTouched();
    const datesValid = this.validateCycleDates();
    if (form.invalid || !datesValid) {
      this.showSubmitError = true;
      setTimeout(() => {
        document.querySelector<HTMLElement>(
          '.edit-cycle-modal .invalid, .edit-cycle-modal .ng-invalid.ng-touched'
        )?.scrollIntoView({ behavior: 'smooth', block: 'center' });
      }, 50);
      return;
    }
    this.showSubmitError = false;
    this.saved.emit(this.buildPayload(false));
  }

  close() { this.closed.emit(); }
}
