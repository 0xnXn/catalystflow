import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';

import { EditCycleModalComponent } from './edit-cycle-modal.component';

const baseCycle = {
  cycleId: 'cyc001',
  cycleTitle: 'Q1 2025 Review',
  startDate: '2025-01-01',
  dueDate: '2025-03-31',
  status: 'DRAFT',
  milestones: [
    { milestoneId: 'ms001', name: 'Self Review', startDate: '2025-01-01', endDate: '2025-01-31', description: 'Employees submit reviews', trigger: 'EMPLOYEE_SELF_REVIEW', assignedRoles: ['EMPLOYEE'] },
    { milestoneId: 'ms002', name: 'Manager Review', startDate: '2025-02-01', endDate: '2025-02-28', description: '', trigger: 'MANAGER_REVIEW', assignedRoles: ['MANAGER'] }
  ]
};

// Relative-date milestone fixtures (de-time-bomb). The component derives milestone
// state from the REAL today (new Date().toISOString()), so hardcoded fixture dates
// rot: the 2026-06-02 failures were "current"/"future" milestones whose hardcoded
// dates had slipped into the past. Express fixtures relative to today so each is
// always genuinely past/current/future. Wide offsets (±30–60d) keep UTC/DST edges
// from flipping a state.
function isoDay(offsetDays: number): string {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + offsetDays);
  return d.toISOString().split('T')[0];
}

function createComponent(cycle: any): EditCycleModalComponent {
  const fixture = TestBed.createComponent(EditCycleModalComponent);
  fixture.componentInstance.cycle = cycle;
  fixture.detectChanges();
  return fixture.componentInstance;
}

describe('EditCycleModalComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [EditCycleModalComponent, CommonModule, FormsModule],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
  });

  it('should be created', () => {
    const component = createComponent({ ...baseCycle });
    expect(component).toBeTruthy();
  });

  // ── ngOnInit ──────────────────────────────────────────────────

  describe('ngOnInit()', () => {
    it('should populate editCycle from cycle input', () => {
      const component = createComponent({ ...baseCycle });
      expect(component.editCycle.cycleTitle).toBe('Q1 2025 Review');
      expect(component.editCycle.startDate).toBe('2025-01-01');
      expect(component.editCycle.dueDate).toBe('2025-03-31');
    });

    it('should copy milestones from cycle input', () => {
      const component = createComponent({ ...baseCycle });
      expect(component.milestones.length).toBe(2);
      expect(component.milestones[0].name).toBe('Self Review');
    });

    it('should default milestone trigger to NONE when missing', () => {
      const cycle = {
        ...baseCycle,
        milestones: [{ name: 'Phase 1', startDate: '2025-01-01', endDate: '2025-01-31' }]
      };
      const component = createComponent(cycle);
      expect(component.milestones[0].trigger).toBe('NONE');
    });
  });

  // ── status helpers ────────────────────────────────────────────

  describe('status helpers', () => {
    it('isDraft should be true for DRAFT cycle', () => {
      const component = createComponent({ ...baseCycle, status: 'DRAFT' });
      expect(component.isDraft).toBeTrue();
      expect(component.isScheduled).toBeFalse();
    });

    it('isScheduled should be true for SCHEDULED cycle', () => {
      const component = createComponent({ ...baseCycle, status: 'SCHEDULED' });
      expect(component.isScheduled).toBeTrue();
    });

    it('isActive should be true for ACTIVE cycle', () => {
      const component = createComponent({ ...baseCycle, status: 'ACTIVE' });
      expect(component.isActive).toBeTrue();
    });

    it('isCompleted should be true for COMPLETED cycle', () => {
      const component = createComponent({ ...baseCycle, status: 'COMPLETED' });
      expect(component.isCompleted).toBeTrue();
    });
  });

  // ── edit permissions ──────────────────────────────────────────

  describe('edit permissions', () => {
    it('canEditCycleStart should be true for DRAFT', () => {
      const component = createComponent({ ...baseCycle, status: 'DRAFT' });
      expect(component.canEditCycleStart).toBeTrue();
    });

    it('canEditCycleStart should be true for SCHEDULED', () => {
      const component = createComponent({ ...baseCycle, status: 'SCHEDULED' });
      expect(component.canEditCycleStart).toBeTrue();
    });

    it('canEditCycleStart should be false for ACTIVE', () => {
      const component = createComponent({ ...baseCycle, status: 'ACTIVE' });
      expect(component.canEditCycleStart).toBeFalse();
    });

    it('canEditCycleEnd should be true for COMPLETED (extend only)', () => {
      const component = createComponent({ ...baseCycle, status: 'COMPLETED' });
      expect(component.canEditCycleEnd).toBeTrue();
    });

    it('canEditCycleEnd should be true for DRAFT', () => {
      const component = createComponent({ ...baseCycle, status: 'DRAFT' });
      expect(component.canEditCycleEnd).toBeTrue();
    });

    it('canAddMilestone should be false for COMPLETED', () => {
      const component = createComponent({ ...baseCycle, status: 'COMPLETED' });
      expect(component.canAddMilestone).toBeFalse();
    });

    it('canAddMilestone should be false when milestone count is 15', () => {
      const cycle = { ...baseCycle, status: 'DRAFT', milestones: Array(15).fill({ name: 'M', startDate: '2025-01-01', endDate: '2025-01-31', trigger: 'NONE' }) };
      const component = createComponent(cycle);
      expect(component.canAddMilestone).toBeFalse();
    });

    // Completed cycles are locked except for extending milestone end dates.
    it('should lock completed-cycle milestone name, start, delete, and template edits', () => {
      const component = createComponent({ ...baseCycle, status: 'COMPLETED' });

      expect(component.canEditMilestoneName(0)).toBeFalse();
      expect(component.canEditMilestoneStart(0)).toBeFalse();
      expect(component.canEditMilestoneEnd(0)).toBeTrue();
      expect(component.canDeleteMilestone(0)).toBeFalse();
      expect(component.canEditMilestoneTemplate(0)).toBeFalse();
    });
  });

  // ── milestoneState ────────────────────────────────────────────

  describe('milestoneState()', () => {
    it('should return future for all milestones when cycle is not ACTIVE', () => {
      const component = createComponent({ ...baseCycle, status: 'DRAFT' });
      const state = component.milestoneState(component.milestones[0]);
      expect(state).toBe('future');
    });

    it('should return completed when milestone endDate is in the past (ACTIVE cycle)', () => {
      const cycle = {
        ...baseCycle,
        status: 'ACTIVE',
        milestones: [{ name: 'Past', startDate: isoDay(-60), endDate: isoDay(-30), trigger: 'EMPLOYEE_SELF_REVIEW' }]
      };
      const component = createComponent(cycle);
      expect(component.milestoneState(component.milestones[0])).toBe('completed');
    });

    it('should return current when milestone has started but not ended (ACTIVE cycle)', () => {
      const cycle = {
        ...baseCycle,
        status: 'ACTIVE',
        milestones: [{ name: 'Current', startDate: isoDay(-30), endDate: isoDay(30), trigger: 'EMPLOYEE_SELF_REVIEW' }]
      };
      const component = createComponent(cycle);
      expect(component.milestoneState(component.milestones[0])).toBe('current');
    });

    it('should return future when milestone has not started yet (ACTIVE cycle)', () => {
      const cycle = {
        ...baseCycle,
        status: 'ACTIVE',
        milestones: [{ name: 'Future', startDate: isoDay(10), endDate: isoDay(40), trigger: 'MANAGER_REVIEW' }]
      };
      const component = createComponent(cycle);
      expect(component.milestoneState(component.milestones[0])).toBe('future');
    });
  });

  // ── canEditMilestone* (ACTIVE cycle) ──────────────────────────

  describe('canEditMilestoneName/Start/End for ACTIVE cycle', () => {
    function activeComponent(milestones: any[]) {
      return createComponent({ ...baseCycle, status: 'ACTIVE', milestones });
    }

    const completedMs = { name: 'Done', startDate: isoDay(-60), endDate: isoDay(-30), trigger: 'EMPLOYEE_SELF_REVIEW' };
    const currentMs  = { name: 'Now',  startDate: isoDay(-30), endDate: isoDay(30), trigger: 'MANAGER_REVIEW' };
    const futureMs   = { name: 'Soon', startDate: isoDay(10), endDate: isoDay(40), trigger: 'ADMIN_REVIEW' };

    it('canEditMilestoneName should be false for completed milestone', () => {
      const c = activeComponent([completedMs]);
      expect(c.canEditMilestoneName(0)).toBeFalse();
    });

    it('canEditMilestoneName should be true for current milestone', () => {
      const c = activeComponent([currentMs]);
      expect(c.canEditMilestoneName(0)).toBeTrue();
    });

    it('canEditMilestoneName should be true for future milestone', () => {
      const c = activeComponent([futureMs]);
      expect(c.canEditMilestoneName(0)).toBeTrue();
    });

    it('canEditMilestoneStart should be false for completed milestone', () => {
      const c = activeComponent([completedMs]);
      expect(c.canEditMilestoneStart(0)).toBeFalse();
    });

    it('canEditMilestoneStart should be false for current milestone (ACTIVE)', () => {
      const c = activeComponent([currentMs]);
      expect(c.canEditMilestoneStart(0)).toBeFalse();
    });

    it('canEditMilestoneStart should be true for future milestone', () => {
      const c = activeComponent([futureMs]);
      expect(c.canEditMilestoneStart(0)).toBeTrue();
    });

    it('canEditMilestoneEnd should be false for completed milestone in ACTIVE cycle', () => {
      const c = activeComponent([completedMs]);
      expect(c.canEditMilestoneEnd(0)).toBeFalse();
    });

    it('canEditMilestoneEnd should be true for current milestone', () => {
      const c = activeComponent([currentMs]);
      expect(c.canEditMilestoneEnd(0)).toBeTrue();
    });

    it('canEditMilestoneEnd should be true for future milestone', () => {
      const c = activeComponent([futureMs]);
      expect(c.canEditMilestoneEnd(0)).toBeTrue();
    });

    it('canDeleteMilestone should be false for current milestone in ACTIVE cycle', () => {
      const c = activeComponent([currentMs]);
      expect(c.canDeleteMilestone(0)).toBeFalse();
    });

    it('canDeleteMilestone should be false for completed milestone in ACTIVE cycle', () => {
      const c = activeComponent([completedMs]);
      expect(c.canDeleteMilestone(0)).toBeFalse();
    });

    it('canDeleteMilestone should be true for future milestone in ACTIVE cycle', () => {
      const c = activeComponent([futureMs]);
      expect(c.canDeleteMilestone(0)).toBeTrue();
    });
  });

  // ── canDeleteMilestone ────────────────────────────────────────

  describe('canDeleteMilestone()', () => {
    it('should return true for DRAFT milestone', () => {
      const component = createComponent({ ...baseCycle, status: 'DRAFT' });
      expect(component.canDeleteMilestone(0)).toBeTrue();
    });

    it('should return false for COMPLETED cycle', () => {
      const component = createComponent({ ...baseCycle, status: 'COMPLETED' });
      expect(component.canDeleteMilestone(0)).toBeFalse();
    });
  });

  // ── addMilestone / removeMilestone ────────────────────────────

  describe('addMilestone()', () => {
    it('should add milestone with blank endDate', () => {
      const component = createComponent({ ...baseCycle });
      component.addMilestone();
      const added = component.milestones[component.milestones.length - 1];
      expect(added.endDate).toBe('');
      expect(added.trigger).toBe('NONE');
    });

    it('should default new milestone startDate to editCycle.startDate', () => {
      const component = createComponent({ ...baseCycle });
      component.addMilestone();
      const added = component.milestones[component.milestones.length - 1];
      expect(added.startDate).toBe('2025-01-01');
    });

    it('should not add milestone when limit of 15 is reached', () => {
      const cycle = { ...baseCycle, status: 'DRAFT', milestones: Array(15).fill({ name: 'M', startDate: '2025-01-01', endDate: '2025-01-31', trigger: 'NONE' }) };
      const component = createComponent(cycle);
      component.addMilestone();
      expect(component.milestones.length).toBe(15);
    });
  });

  // Step 2 should be reachable only after required cycle details and date range pass.
  describe('goToStep2()', () => {
    function formStub(invalidFields: string[] = []): any {
      const controls = ['cycleName', 'startDate', 'endDate'].reduce((acc: any, name) => {
        acc[name] = {
          invalid: invalidFields.includes(name),
          markAsTouched: jasmine.createSpy(`${name}Touched`)
        };
        return acc;
      }, {});
      return { controls };
    }

    it('should mark fields and block navigation when a required field is invalid', () => {
      const component = createComponent({ ...baseCycle });
      const form = formStub(['cycleName']);

      component.goToStep2(form);

      expect(form.controls.cycleName.markAsTouched).toHaveBeenCalled();
      expect(form.controls.startDate.markAsTouched).toHaveBeenCalled();
      expect(component.showSubmitError).toBeTrue();
      expect(component.currentStep).toBe(1);
    });

    it('should move to milestone step when cycle details are valid', () => {
      const component = createComponent({ ...baseCycle });

      component.goToStep2(formStub());

      expect(component.showSubmitError).toBeFalse();
      expect(component.currentStep).toBe(2);
    });
  });

  describe('removeMilestone()', () => {
    it('should remove milestone at given index for DRAFT cycle', () => {
      const component = createComponent({ ...baseCycle });
      component.removeMilestone(0);
      expect(component.milestones.length).toBe(1);
      expect(component.milestones[0].name).toBe('Manager Review');
    });

    it('should not remove milestone for COMPLETED cycle', () => {
      const component = createComponent({ ...baseCycle, status: 'COMPLETED' });
      component.removeMilestone(0);
      expect(component.milestones.length).toBe(2);
    });
  });

  // ── date validation ───────────────────────────────────────────

  describe('validateCycleDates()', () => {
    it('should return false when startDate >= dueDate', () => {
      const component = createComponent({ ...baseCycle });
      component.editCycle.startDate = '2025-03-31';
      component.editCycle.dueDate = '2025-01-01';
      expect(component.validateCycleDates()).toBeFalse();
      expect(component.dateRangeError).toContain('after');
    });

    it('should return true for valid date range', () => {
      const component = createComponent({ ...baseCycle });
      component.editCycle.startDate = '2025-01-01';
      component.editCycle.dueDate = '2025-03-31';
      expect(component.validateCycleDates()).toBeTrue();
    });

    it('onCycleDateChange should clear an existing date error after fixing dates', () => {
      const component = createComponent({ ...baseCycle });
      component.editCycle.startDate = '2025-03-31';
      component.editCycle.dueDate = '2025-01-01';
      component.validateCycleDates();

      component.editCycle.startDate = '2025-01-01';
      component.editCycle.dueDate = '2025-03-31';
      component.onCycleDateChange();

      expect(component.dateRangeError).toBe('');
    });
  });

  // ── date constraints ──────────────────────────────────────────

  describe('date constraints', () => {
    it('cycleStartMax should be one day before dueDate', () => {
      const component = createComponent({ ...baseCycle });
      expect(component.cycleStartMax).toBe('2025-03-30');
    });

    it('cycleStartMax should be empty when dueDate is not set', () => {
      const component = createComponent({ ...baseCycle, dueDate: '' });
      expect(component.cycleStartMax).toBe('');
    });

    it('cycleEndMin should be one day after startDate for non-active cycle', () => {
      const component = createComponent({ ...baseCycle, status: 'DRAFT' });
      expect(component.cycleEndMin).toBe('2025-01-02');
    });

    it('cycleEndMin should be empty when a draft cycle has no startDate', () => {
      const component = createComponent({ ...baseCycle, status: 'DRAFT', startDate: '' });
      expect(component.cycleEndMin).toBe('');
    });

    it('cycleEndMin should be today for ACTIVE cycle', () => {
      const component = createComponent({ ...baseCycle, status: 'ACTIVE' });
      expect(component.cycleEndMin).toBe(component.today);
    });

    it('getMilestoneStartMin should return cycle startDate for first milestone', () => {
      const component = createComponent({ ...baseCycle });
      expect(component.getMilestoneStartMin(0)).toBe('2025-01-01');
    });

    it('getMilestoneStartMin should return cycle startDate for all milestones (parallel allowed)', () => {
      const component = createComponent({ ...baseCycle });
      expect(component.getMilestoneStartMin(1)).toBe('2025-01-01');
    });

    it('getMilestoneStartMax should return cycle dueDate', () => {
      const component = createComponent({ ...baseCycle });
      expect(component.getMilestoneStartMax(0)).toBe('2025-03-31');
    });

    it('getMilestoneEndMin should return milestone startDate for draft cycle', () => {
      const component = createComponent({ ...baseCycle });
      expect(component.getMilestoneEndMin(0)).toBe('2025-01-01');
    });

    it('getMilestoneEndMin should return today for completed cycle', () => {
      const component = createComponent({ ...baseCycle, status: 'COMPLETED' });
      expect(component.getMilestoneEndMin(0)).toBe(component.today);
    });

    it('getMilestoneEndMin should return today for an active current milestone', () => {
      const component = createComponent({
        ...baseCycle,
        status: 'ACTIVE',
        milestones: [{ name: 'Current', startDate: isoDay(-30), endDate: isoDay(30), trigger: 'MANAGER_REVIEW' }]
      });

      expect(component.getMilestoneEndMin(0)).toBe(component.today);
    });

    it('getMilestoneEndMax should return cycle dueDate (parallel milestones allowed)', () => {
      const component = createComponent({ ...baseCycle });
      expect(component.getMilestoneEndMax(0)).toBe('2025-03-31');
    });

    it('getMilestoneEndMax should return cycle dueDate for last milestone', () => {
      const component = createComponent({ ...baseCycle });
      const lastSi = component.sortedIndices.length - 1;
      expect(component.getMilestoneEndMax(lastSi)).toBe('2025-03-31');
    });
  });

  // ── gap detection ─────────────────────────────────────────────

  describe('gap detection', () => {
    it('hasGapAfter should be true when there is a gap between milestones', () => {
      const component = createComponent({ ...baseCycle });
      expect(component.hasGapAfter(0)).toBeTrue();
    });

    it('hasGapAfter should be false when milestones are back to back', () => {
      const cycle = {
        ...baseCycle,
        milestones: [
          { name: 'A', startDate: '2025-01-01', endDate: '2025-02-01', trigger: 'EMPLOYEE_SELF_REVIEW' },
          { name: 'B', startDate: '2025-02-01', endDate: '2025-02-28', trigger: 'MANAGER_REVIEW' }
        ]
      };
      const component = createComponent(cycle);
      expect(component.hasGapAfter(0)).toBeFalse();
    });

    it('hasGapAfter should be false when milestones overlap', () => {
      const cycle = {
        ...baseCycle,
        milestones: [
          { name: 'A', startDate: '2025-01-01', endDate: '2025-02-15', trigger: 'EMPLOYEE_SELF_REVIEW' },
          { name: 'B', startDate: '2025-01-20', endDate: '2025-02-28', trigger: 'MANAGER_REVIEW' }
        ]
      };
      const component = createComponent(cycle);
      expect(component.hasGapAfter(0)).toBeFalse();
    });

    it('hasGapAfter should be false for the last item and when dates are missing', () => {
      const component = createComponent({
        ...baseCycle,
        milestones: [
          { name: 'No End', startDate: '2025-01-01', endDate: '', trigger: 'EMPLOYEE_SELF_REVIEW' },
          { name: 'No Start', startDate: '', endDate: '2025-02-28', trigger: 'MANAGER_REVIEW' }
        ]
      });

      expect(component.hasGapAfter(0)).toBeFalse();
      expect(component.hasGapAfter(1)).toBeFalse();
    });
  });

  describe('sortedIndices and trackBy', () => {
    it('should sort milestones with missing start dates last', () => {
      const component = createComponent({
        ...baseCycle,
        milestones: [
          { name: 'No Date', startDate: '', endDate: '', trigger: 'NONE' },
          { name: 'Later', startDate: '2025-02-01', endDate: '', trigger: 'NONE' },
          { name: 'Earlier', startDate: '2025-01-01', endDate: '', trigger: 'NONE' }
        ]
      });

      expect(component.sortedIndices).toEqual([2, 1, 0]);
      expect(component.trackByOrigIdx(0, 2)).toBe(2);
    });
  });

  describe('template drawer branches', () => {
    const snapshot: any = {
      templateId: 'tpl-1',
      templateName: 'Self Template',
      reviewType: 'SELF',
      questions: [{ questionId: 'q1', text: 'Question', type: 'TEXT', options: [], required: true, order: 1 }],
      snapshotCreatedAt: '2026-01-01T00:00:00Z'
    };

    // Drawer mode/index state decides whether a milestone builds, browses, or edits a template.
    it('should open each drawer mode and close the drawer', () => {
      const component = createComponent({ ...baseCycle });

      component.openBuildDrawer(0);
      expect(component.drawerOpen()).toBeTrue();
      expect(component.drawerMode()).toBe('build');
      expect(component.drawerMilestoneIdx()).toBe(0);

      component.openBrowseDrawer(1);
      expect(component.drawerMode()).toBe('browse');
      expect(component.drawerMilestoneIdx()).toBe(1);

      component.openEditDrawer(1);
      expect(component.drawerMode()).toBe('edit');

      component.onDrawerClosed();
      expect(component.drawerOpen()).toBeFalse();
    });

    it('should attach, expose, and remove a template snapshot for a valid milestone index', () => {
      const component = createComponent({ ...baseCycle });

      component.openEditDrawer(0);
      component.onTemplateAttached(snapshot);

      expect(component.milestones[0].templateSnapshot).toEqual(snapshot);
      expect(component.drawerOpen()).toBeFalse();
      expect(component.activeEditQuestions.length).toBe(1);

      component.removeTemplate(0);
      expect(component.milestones[0].templateSnapshot).toBeUndefined();
    });

    it('should ignore template attachment when drawer index is invalid', () => {
      const component = createComponent({ ...baseCycle });

      component.drawerMilestoneIdx.set(-1);
      component.onTemplateAttached(snapshot);
      expect(component.milestones[0].templateSnapshot).toBeUndefined();
      expect(component.activeEditQuestions).toEqual([]);

      component.drawerMilestoneIdx.set(99);
      component.onTemplateAttached(snapshot);
      expect(component.milestones[0].templateSnapshot).toBeUndefined();
      expect(component.activeEditQuestions).toEqual([]);
    });

    it('should only allow template edits for future active milestones', () => {
      const component = createComponent({
        ...baseCycle,
        status: 'ACTIVE',
        milestones: [
          { name: 'Past', startDate: isoDay(-60), endDate: isoDay(-30), trigger: 'EMPLOYEE_SELF_REVIEW' },
          { name: 'Future', startDate: isoDay(30), endDate: isoDay(60), trigger: 'MANAGER_REVIEW' }
        ]
      });

      expect(component.canEditMilestoneTemplate(0)).toBeFalse();
      expect(component.canEditMilestoneTemplate(1)).toBeTrue();
    });
  });

  // ── onSaveDraft ───────────────────────────────────────────────

  describe('onSaveDraft()', () => {
    it('should emit saved event with publishAfter false', () => {
      const component = createComponent({ ...baseCycle });
      const emitted: any[] = [];
      component.saved.subscribe(v => emitted.push(v));

      const mockForm: any = { control: { markAllAsTouched: () => {} } };
      component.onSaveDraft(mockForm);

      expect(emitted.length).toBe(1);
      expect(emitted[0].publishAfter).toBeFalse();
    });

    it('should set showSubmitError when dates are invalid', () => {
      const component = createComponent({ ...baseCycle });
      component.editCycle.startDate = '2025-03-31';
      component.editCycle.dueDate = '2025-01-01';
      const mockForm: any = { control: { markAllAsTouched: () => {} } };
      component.onSaveDraft(mockForm);
      expect(component.showSubmitError).toBeTrue();
    });
  });

  // ── onPublish validation ──────────────────────────────────────

  describe('onPublish() validation', () => {
    const validForm: any = { control: { markAllAsTouched: () => {} }, invalid: false };

    it('should set publishError when cycleTitle is blank', () => {
      const component = createComponent({ ...baseCycle });
      component.editCycle.cycleTitle = '';
      component.onPublish(validForm);
      expect(component.publishError).toContain('Cycle name');
    });

    it('should set publishError when no milestones exist', () => {
      const component = createComponent({ ...baseCycle, milestones: [] });
      component.onPublish(validForm);
      expect(component.publishError).toContain('milestone');
    });

    it('should set publishError when publish dates are missing or invalid', () => {
      const component = createComponent({ ...baseCycle });

      component.editCycle.startDate = '';
      component.onPublish(validForm);
      expect(component.publishError).toContain('Valid start and end dates');

      component.editCycle.startDate = '2025-03-31';
      component.editCycle.dueDate = '2025-01-01';
      component.onPublish(validForm);
      expect(component.publishError).toContain('Valid start and end dates');
    });

    it('should set publishError when a milestone has NONE trigger', () => {
      const cycle = {
        ...baseCycle,
        milestones: [{ name: 'Phase 1', startDate: '2025-01-01', endDate: '2025-01-31', trigger: 'NONE' }]
      };
      const component = createComponent(cycle);
      component.onPublish(validForm);
      expect(component.publishError).toContain('role');
    });

    it('should show publish confirm when all validation passes', () => {
      const component = createComponent({ ...baseCycle });
      component.onPublish(validForm);
      expect(component.showPublishConfirm).toBeTrue();
    });

    it('should show a form error and request scroll when publish form is invalid', fakeAsync(() => {
      const component = createComponent({ ...baseCycle });
      const invalidElement = document.createElement('input');
      invalidElement.className = 'invalid';
      spyOn(invalidElement, 'scrollIntoView');
      spyOn(document, 'querySelector').and.returnValue(invalidElement);

      component.onPublish({ control: { markAllAsTouched: () => {} }, invalid: true } as any);
      tick(50);

      expect(component.publishError).toContain('Fix all highlighted fields');
      expect(invalidElement.scrollIntoView).toHaveBeenCalled();
    }));
  });

  // ── confirmPublish / cancelPublish ────────────────────────────

  describe('publish confirmation', () => {
    it('should emit saved with publishAfter true on confirmPublish', () => {
      const component = createComponent({ ...baseCycle });
      const emitted: any[] = [];
      component.saved.subscribe(v => emitted.push(v));

      const validForm: any = { control: { markAllAsTouched: () => {} }, invalid: false };
      component.onPublish(validForm);
      component.confirmPublish();

      expect(emitted[0].publishAfter).toBeTrue();
      expect(component.showPublishConfirm).toBeFalse();
    });

    it('should clear confirm state on cancelPublish', () => {
      const component = createComponent({ ...baseCycle });
      component.showPublishConfirm = true;
      component.cancelPublish();
      expect(component.showPublishConfirm).toBeFalse();
    });

    it('should close confirmation without emitting when there is no pending payload', () => {
      const component = createComponent({ ...baseCycle });
      const emitted: any[] = [];
      component.saved.subscribe(v => emitted.push(v));
      component.showPublishConfirm = true;

      component.confirmPublish();

      expect(emitted).toEqual([]);
      expect(component.showPublishConfirm).toBeFalse();
    });
  });

  // ── onSaveChanges ─────────────────────────────────────────────

  describe('onSaveChanges()', () => {
    it('should emit saved event when form is valid', () => {
      const component = createComponent({ ...baseCycle });
      const emitted: any[] = [];
      component.saved.subscribe(v => emitted.push(v));

      const mockForm: any = { control: { markAllAsTouched: () => {} }, invalid: false };
      component.onSaveChanges(mockForm);

      expect(emitted.length).toBe(1);
      expect(emitted[0].publishAfter).toBeFalse();
    });

    it('should set showSubmitError when form is invalid', () => {
      const component = createComponent({ ...baseCycle });
      const mockForm: any = { control: { markAllAsTouched: () => {} }, invalid: true };
      component.onSaveChanges(mockForm);
      expect(component.showSubmitError).toBeTrue();
    });

    it('should set showSubmitError and scroll when dates are invalid', fakeAsync(() => {
      const component = createComponent({ ...baseCycle });
      const invalidElement = document.createElement('input');
      invalidElement.className = 'invalid';
      spyOn(invalidElement, 'scrollIntoView');
      spyOn(document, 'querySelector').and.returnValue(invalidElement);
      component.editCycle.startDate = '2025-03-31';
      component.editCycle.dueDate = '2025-01-01';

      component.onSaveChanges({ control: { markAllAsTouched: () => {} }, invalid: false } as any);
      tick(50);

      expect(component.showSubmitError).toBeTrue();
      expect(invalidElement.scrollIntoView).toHaveBeenCalled();
    }));
  });

  // ── buildPayload ──────────────────────────────────────────────

  describe('buildPayload (via emitted value)', () => {
    it('should include assignedRoles mapped from trigger', () => {
      const component = createComponent({ ...baseCycle });
      const emitted: any[] = [];
      component.saved.subscribe(v => emitted.push(v));

      const mockForm: any = { control: { markAllAsTouched: () => {} } };
      component.onSaveDraft(mockForm);

      const ms = emitted[0].milestones;
      expect(ms[0].assignedRoles).toEqual(['EMPLOYEE']);
      expect(ms[1].assignedRoles).toEqual(['MANAGER']);
    });

    it('should filter out milestones with blank names', () => {
      const cycle = {
        ...baseCycle,
        milestones: [
          { name: 'Valid', startDate: '2025-01-01', endDate: '2025-01-31', trigger: 'EMPLOYEE_SELF_REVIEW' },
          { name: '   ', startDate: '2025-02-01', endDate: '2025-02-28', trigger: 'NONE' }
        ]
      };
      const component = createComponent(cycle);
      const emitted: any[] = [];
      component.saved.subscribe(v => emitted.push(v));

      const mockForm: any = { control: { markAllAsTouched: () => {} } };
      component.onSaveDraft(mockForm);

      expect(emitted[0].milestones.length).toBe(1);
    });

    it('should map ADMIN_REVIEW trigger to assignedRoles ADMIN', () => {
      const cycle = {
        ...baseCycle,
        milestones: [{ name: 'Admin Phase', startDate: '2025-01-01', endDate: '2025-01-31', trigger: 'ADMIN_REVIEW' }]
      };
      const component = createComponent(cycle);
      const emitted: any[] = [];
      component.saved.subscribe(v => emitted.push(v));

      const mockForm: any = { control: { markAllAsTouched: () => {} } };
      component.onSaveDraft(mockForm);

      expect(emitted[0].milestones[0].assignedRoles).toEqual(['ADMIN']);
    });

    it('should map NONE trigger to empty assignedRoles array', () => {
      const cycle = {
        ...baseCycle,
        milestones: [{ name: 'No Role', startDate: '2025-01-01', endDate: '2025-01-31', trigger: 'NONE' }]
      };
      const component = createComponent(cycle);
      const emitted: any[] = [];
      component.saved.subscribe(v => emitted.push(v));

      const mockForm: any = { control: { markAllAsTouched: () => {} } };
      component.onSaveDraft(mockForm);

      expect(emitted[0].milestones[0].assignedRoles).toEqual([]);
    });

    it('should trim cycle title and map unknown triggers to no roles', () => {
      const cycle = {
        ...baseCycle,
        milestones: [{ name: 'Unknown Role', startDate: '2025-01-01', endDate: '2025-01-31', trigger: 'UNKNOWN' as any }]
      };
      const component = createComponent(cycle);
      const emitted: any[] = [];
      component.saved.subscribe(v => emitted.push(v));
      component.editCycle.cycleTitle = '  Trimmed Cycle  ';

      component.onSaveDraft({ control: { markAllAsTouched: () => {} } } as any);

      expect(emitted[0].cycleTitle).toBe('Trimmed Cycle');
      expect(emitted[0].milestones[0].assignedRoles).toEqual([]);
    });
  });

  // ── close ─────────────────────────────────────────────────────

  describe('close()', () => {
    it('should emit closed event', () => {
      const component = createComponent({ ...baseCycle });
      let closed = false;
      component.closed.subscribe(() => { closed = true; });
      component.close();
      expect(closed).toBeTrue();
    });
  });
});
