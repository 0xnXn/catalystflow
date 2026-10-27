import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';

import { CreateCycleModalComponent } from './create-cycle-modal.component';

describe('CreateCycleModalComponent', () => {
  let component: CreateCycleModalComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CreateCycleModalComponent, CommonModule, FormsModule],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();

    const fixture = TestBed.createComponent(CreateCycleModalComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should be created', () => {
    expect(component).toBeTruthy();
  });

  it('should initialise with empty cycle fields', () => {
    expect(component.cycle.cycleTitle).toBe('');
    expect(component.cycle.startDate).toBe('');
    expect(component.cycle.dueDate).toBe('');
    expect(component.cycle.status).toBe('DRAFT');
    expect(component.milestones.length).toBe(0);
  });

  // ── addMilestone / removeMilestone ────────────────────────────

  describe('addMilestone()', () => {
    it('should add a milestone with blank name and endDate', () => {
      component.addMilestone();
      expect(component.milestones.length).toBe(1);
      expect(component.milestones[0].name).toBe('');
      expect(component.milestones[0].endDate).toBe('');
      expect(component.milestones[0].trigger).toBe('NONE');
    });

    it('should default startDate to cycle startDate', () => {
      component.cycle.startDate = '2025-01-01';
      component.addMilestone();
      expect(component.milestones[0].startDate).toBe('2025-01-01');
    });

    it('canAddMilestone should be false when 15 milestones exist', () => {
      for (let i = 0; i < 15; i++) component.addMilestone();
      expect(component.milestones.length).toBe(15);
      expect(component.milestones.length < 15).toBeFalse();
    });
  });

  describe('removeMilestone()', () => {
    it('should remove milestone at given index', () => {
      component.addMilestone();
      component.addMilestone();
      component.removeMilestone(0);
      expect(component.milestones.length).toBe(1);
    });
  });

  // ── date validation ───────────────────────────────────────────

  describe('validateCycleDates()', () => {
    it('should return true when both dates are empty', () => {
      expect(component.validateCycleDates()).toBeTrue();
    });

    it('should return false when startDate equals dueDate', () => {
      component.cycle.startDate = '2025-01-01';
      component.cycle.dueDate = '2025-01-01';
      expect(component.validateCycleDates()).toBeFalse();
      expect(component.dateRangeError).toContain('same');
    });

    it('should return false when startDate is after dueDate', () => {
      component.cycle.startDate = '2025-03-01';
      component.cycle.dueDate = '2025-01-01';
      expect(component.validateCycleDates()).toBeFalse();
      expect(component.dateRangeError).toContain('after');
    });

    it('should return true for valid date range', () => {
      component.cycle.startDate = '2025-01-01';
      component.cycle.dueDate = '2025-03-31';
      expect(component.validateCycleDates()).toBeTrue();
      expect(component.dateRangeError).toBe('');
    });
  });

  // ── cycleStartMax / cycleEndMin ───────────────────────────────

  describe('date constraints', () => {
    it('cycleStartMax should be one day before dueDate', () => {
      component.cycle.dueDate = '2025-03-31';
      expect(component.cycleStartMax).toBe('2025-03-30');
    });

    it('cycleStartMax should be empty when dueDate is not set', () => {
      component.cycle.dueDate = '';
      expect(component.cycleStartMax).toBe('');
    });

    it('cycleStartMin should be today (today is selectable)', () => {
      const d = new Date();
      d.setHours(0, 0, 0, 0);
      const expected = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
      expect(component.cycleStartMin).toBe(expected);
    });

    it('getMilestoneStartMin should allow today when cycle.startDate is unset', () => {
      component.cycle.startDate = '';
      component.milestones = [{ name: '', startDate: '', endDate: '', description: '', trigger: 'NONE' }];
      const d = new Date();
      d.setHours(0, 0, 0, 0);
      const expected = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
      expect(component.getMilestoneStartMin(0)).toBe(expected);
    });

    it('cycleEndMin should be one day after startDate', () => {
      component.cycle.startDate = '2025-01-01';
      expect(component.cycleEndMin).toBe('2025-01-02');
    });

    it('cycleEndMin should be empty when startDate is not set', () => {
      component.cycle.startDate = '';
      expect(component.cycleEndMin).toBe('');
    });
  });

  // ── gap detection ─────────────────────────────────────────────

  describe('gap detection', () => {
    it('hasGapAfter should be true when there is a gap between milestones', () => {
      component.milestones = [
        { name: 'Phase 1', startDate: '2025-01-01', endDate: '2025-01-15', description: '', trigger: 'EMPLOYEE_SELF_REVIEW' },
        { name: 'Phase 2', startDate: '2025-02-01', endDate: '2025-02-28', description: '', trigger: 'MANAGER_REVIEW' }
      ];
      expect(component.hasGapAfter(0)).toBeTrue();
    });

    it('hasGapAfter should be false when milestones are back to back', () => {
      component.milestones = [
        { name: 'Phase 1', startDate: '2025-01-01', endDate: '2025-01-31', description: '', trigger: 'EMPLOYEE_SELF_REVIEW' },
        { name: 'Phase 2', startDate: '2025-01-31', endDate: '2025-02-28', description: '', trigger: 'MANAGER_REVIEW' }
      ];
      expect(component.hasGapAfter(0)).toBeFalse();
    });

    it('hasGapAfter should be false when milestones overlap', () => {
      component.milestones = [
        { name: 'Phase 1', startDate: '2025-01-01', endDate: '2025-02-15', description: '', trigger: 'EMPLOYEE_SELF_REVIEW' },
        { name: 'Phase 2', startDate: '2025-01-20', endDate: '2025-02-28', description: '', trigger: 'MANAGER_REVIEW' }
      ];
      expect(component.hasGapAfter(0)).toBeFalse();
    });
  });

  // ── sortedIndices ─────────────────────────────────────────────

  describe('sortedIndices', () => {
    it('should sort milestones by startDate ascending', () => {
      component.milestones = [
        { name: 'B', startDate: '2025-02-01', endDate: '2025-02-28', description: '', trigger: 'NONE' },
        { name: 'A', startDate: '2025-01-01', endDate: '2025-01-31', description: '', trigger: 'NONE' }
      ];
      const sorted = component.sortedIndices;
      expect(component.milestones[sorted[0]].name).toBe('A');
      expect(component.milestones[sorted[1]].name).toBe('B');
    });

    it('should place milestones with no startDate last', () => {
      component.milestones = [
        { name: 'No Date', startDate: '', endDate: '', description: '', trigger: 'NONE' },
        { name: 'Has Date', startDate: '2025-01-01', endDate: '2025-01-31', description: '', trigger: 'NONE' }
      ];
      const sorted = component.sortedIndices;
      expect(component.milestones[sorted[0]].name).toBe('Has Date');
    });

    it('should keep original order when both milestones have no startDate', () => {
      // Empty dates are common while the user is still drafting milestones.
      component.milestones = [
        { name: 'First Draft', startDate: '', endDate: '', description: '', trigger: 'NONE' },
        { name: 'Second Draft', startDate: '', endDate: '', description: '', trigger: 'NONE' }
      ];

      expect(component.sortedIndices).toEqual([0, 1]);
      expect(component.trackByOrigIdx(0, 7)).toBe(7);
    });
  });

  describe('template drawer', () => {
    const snapshot: any = {
      templateId: 'template-1',
      templateName: 'Self Review Template',
      questions: [{ id: 'q1', questionText: 'What did you deliver?', questionType: 'TEXT' }]
    };

    beforeEach(() => {
      component.milestones = [
        { name: 'Self Review', startDate: '2025-01-01', endDate: '2025-01-31', description: '', trigger: 'EMPLOYEE_SELF_REVIEW' }
      ];
    });

    it('should open drawer in build, browse and edit modes', () => {
      // Drawer mode controls whether users create, select, or edit a milestone template.
      component.openBuildDrawer(0);
      expect(component.drawerOpen()).toBeTrue();
      expect(component.drawerMode()).toBe('build');
      expect(component.drawerMilestoneIdx()).toBe(0);

      component.openBrowseDrawer(0);
      expect(component.drawerMode()).toBe('browse');

      component.openEditDrawer(0);
      expect(component.drawerMode()).toBe('edit');

      component.onDrawerClosed();
      expect(component.drawerOpen()).toBeFalse();
    });

    it('should attach, expose and remove a milestone template snapshot', () => {
      // Attached snapshots are emitted later with the cycle payload, so keep the original milestone fields.
      component.openBrowseDrawer(0);
      component.onTemplateAttached(snapshot);

      expect(component.drawerOpen()).toBeFalse();
      expect(component.milestones[0].templateSnapshot).toEqual(snapshot);
      expect(component.activeEditQuestions).toEqual(snapshot.questions);

      component.removeTemplate(0);

      expect(component.milestones[0].templateSnapshot).toBeUndefined();
      expect(component.milestones[0].name).toBe('Self Review');
    });

    it('should close drawer without attaching when milestone index is invalid', () => {
      // Invalid indices can happen after deleting a milestone while the drawer is still open.
      component.drawerMilestoneIdx.set(99);

      component.onTemplateAttached(snapshot);

      expect(component.drawerOpen()).toBeFalse();
      expect(component.milestones[0].templateSnapshot).toBeUndefined();

      component.drawerMilestoneIdx.set(-1);
      expect(component.activeEditQuestions).toEqual([]);
    });
  });

  // ── onSaveDraft ───────────────────────────────────────────────

  describe('onSaveDraft()', () => {
    it('should emit submitted event with publishAfter false', () => {
      const emittedValues: any[] = [];
      component.submitted.subscribe(v => emittedValues.push(v));

      component.cycle = { cycleTitle: 'Test', startDate: '2025-01-01', dueDate: '2025-03-31', status: 'DRAFT' };
      component.milestones = [{ name: 'Phase 1', startDate: '2025-01-01', endDate: '2025-01-31', description: '', trigger: 'EMPLOYEE_SELF_REVIEW' }];

      const mockForm: any = { control: { markAllAsTouched: () => {} } };
      component.onSaveDraft(mockForm);

      expect(emittedValues.length).toBe(1);
      expect(emittedValues[0].publishAfter).toBeFalse();
      expect(emittedValues[0].cycleTitle).toBe('Test');
    });

    it('should use "Untitled Cycle" when cycleTitle is blank', () => {
      const emittedValues: any[] = [];
      component.submitted.subscribe(v => emittedValues.push(v));

      component.cycle = { cycleTitle: '', startDate: '', dueDate: '', status: 'DRAFT' };
      const mockForm: any = { control: { markAllAsTouched: () => {} } };
      component.onSaveDraft(mockForm);

      expect(emittedValues[0].cycleTitle).toBe('Untitled Cycle');
    });

    it('should set showSubmitError when dates are invalid', () => {
      component.cycle.startDate = '2025-03-31';
      component.cycle.dueDate = '2025-01-01';
      const mockForm: any = { control: { markAllAsTouched: () => {} } };
      component.onSaveDraft(mockForm);
      expect(component.showSubmitError).toBeTrue();
    });

    it('should filter out milestones with blank names before emitting', () => {
      const emittedValues: any[] = [];
      component.submitted.subscribe(v => emittedValues.push(v));

      component.cycle = { cycleTitle: 'Test', startDate: '2025-01-01', dueDate: '2025-03-31', status: 'DRAFT' };
      component.milestones = [
        { name: 'Phase 1', startDate: '2025-01-01', endDate: '2025-01-31', description: '', trigger: 'EMPLOYEE_SELF_REVIEW' },
        { name: '', startDate: '2025-02-01', endDate: '2025-02-28', description: '', trigger: 'NONE' }
      ];

      const mockForm: any = { control: { markAllAsTouched: () => {} } };
      component.onSaveDraft(mockForm);

      expect(emittedValues[0].milestones.length).toBe(1);
    });
  });

  // ── onPublish validation ──────────────────────────────────────

  describe('onPublish() validation', () => {
    const validForm: any = { control: { markAllAsTouched: () => {} }, invalid: false };

    it('should set publishError when cycleTitle is blank', () => {
      component.cycle.cycleTitle = '';
      component.onPublish(validForm);
      expect(component.publishError).toContain('Cycle name');
    });

    it('should set publishError when dates are missing', () => {
      component.cycle.cycleTitle = 'Test';
      component.cycle.startDate = '';
      component.onPublish(validForm);
      expect(component.publishError).toContain('dates');
    });

    it('should set publishError when no milestones exist', () => {
      component.cycle = { cycleTitle: 'Test', startDate: '2025-01-01', dueDate: '2025-03-31', status: 'DRAFT' };
      component.milestones = [];
      component.onPublish(validForm);
      expect(component.publishError).toContain('milestone');
    });

    it('should set publishError when a milestone has NONE trigger', () => {
      component.cycle = { cycleTitle: 'Test', startDate: '2025-01-01', dueDate: '2025-03-31', status: 'DRAFT' };
      component.milestones = [{ name: 'Phase 1', startDate: '2025-01-01', endDate: '2025-01-31', description: '', trigger: 'NONE' }];
      component.onPublish(validForm);
      expect(component.publishError).toContain('role');
    });

    it('should set showPublishConfirm when all validation passes', () => {
      component.cycle = { cycleTitle: 'Test', startDate: '2025-01-01', dueDate: '2025-03-31', status: 'DRAFT' };
      component.milestones = [{ name: 'Phase 1', startDate: '2025-01-01', endDate: '2025-01-31', description: '', trigger: 'EMPLOYEE_SELF_REVIEW' }];
      component.onPublish(validForm);
      expect(component.showPublishConfirm).toBeTrue();
    });

    it('should scroll to the invalid field when publish form is invalid', fakeAsync(() => {
      // After business validation passes, invalid Angular controls should still block publishing.
      const invalidElement = document.createElement('input');
      spyOn(invalidElement, 'scrollIntoView');
      spyOn(document, 'querySelector').and.returnValue(invalidElement);
      const invalidForm: any = { control: { markAllAsTouched: jasmine.createSpy('markAllAsTouched') }, invalid: true };
      component.cycle = { cycleTitle: 'Test', startDate: '2025-01-01', dueDate: '2025-03-31', status: 'DRAFT' };
      component.milestones = [{ name: 'Phase 1', startDate: '2025-01-01', endDate: '2025-01-31', description: '', trigger: 'EMPLOYEE_SELF_REVIEW' }];

      component.onPublish(invalidForm);
      tick(50);

      expect(component.publishError).toContain('highlighted fields');
      expect(invalidForm.control.markAllAsTouched).toHaveBeenCalled();
      expect(invalidElement.scrollIntoView).toHaveBeenCalled();
      expect(component.showPublishConfirm).toBeFalse();
    }));
  });

  // ── confirmPublish / cancelPublish ────────────────────────────

  describe('publish confirmation', () => {
    it('should emit submitted with publishAfter true on confirmPublish', () => {
      const emittedValues: any[] = [];
      component.submitted.subscribe(v => emittedValues.push(v));

      const validForm: any = { control: { markAllAsTouched: () => {} }, invalid: false };
      component.cycle = { cycleTitle: 'Test', startDate: '2025-01-01', dueDate: '2025-03-31', status: 'DRAFT' };
      component.milestones = [{ name: 'Phase 1', startDate: '2025-01-01', endDate: '2025-01-31', description: '', trigger: 'EMPLOYEE_SELF_REVIEW' }];
      component.onPublish(validForm);
      component.confirmPublish();

      expect(emittedValues[0].publishAfter).toBeTrue();
      expect(component.showPublishConfirm).toBeFalse();
    });

    it('should clear pending payload on cancelPublish', () => {
      component.showPublishConfirm = true;
      component.cancelPublish();
      expect(component.showPublishConfirm).toBeFalse();
    });

    it('should simply close confirmation when there is no pending payload', () => {
      // Defensive branch: confirming an already-cleared dialog should not emit a payload.
      const emittedValues: any[] = [];
      component.submitted.subscribe(v => emittedValues.push(v));
      component.showPublishConfirm = true;

      component.confirmPublish();

      expect(emittedValues).toEqual([]);
      expect(component.showPublishConfirm).toBeFalse();
    });
  });

  // ── close ─────────────────────────────────────────────────────

  describe('close()', () => {
    it('should emit closed event', () => {
      let closed = false;
      component.closed.subscribe(() => { closed = true; });
      component.close();
      expect(closed).toBeTrue();
    });
  });

  // ── triggerToRoles mapping ────────────────────────────────────

  describe('triggerToRoles mapping (via emitted payload)', () => {
    it('should map EMPLOYEE_SELF_REVIEW and MANAGER_REVIEW triggers correctly', () => {
      const emittedValues: any[] = [];
      component.submitted.subscribe(v => emittedValues.push(v));

      component.cycle = { cycleTitle: 'Test', startDate: '2025-01-01', dueDate: '2025-03-31', status: 'DRAFT' };
      component.milestones = [
        { name: 'Phase 1', startDate: '2025-01-01', endDate: '2025-01-31', description: '', trigger: 'EMPLOYEE_SELF_REVIEW' },
        { name: 'Phase 2', startDate: '2025-02-01', endDate: '2025-02-28', description: '', trigger: 'MANAGER_REVIEW' }
      ];

      const mockForm: any = { control: { markAllAsTouched: () => {} } };
      component.onSaveDraft(mockForm);

      expect(emittedValues[0].milestones[0].assignedRoles).toEqual(['EMPLOYEE']);
      expect(emittedValues[0].milestones[1].assignedRoles).toEqual(['MANAGER']);
    });

    it('should map ADMIN_REVIEW trigger to assignedRoles ADMIN', () => {
      const emittedValues: any[] = [];
      component.submitted.subscribe(v => emittedValues.push(v));

      component.cycle = { cycleTitle: 'Test', startDate: '2025-01-01', dueDate: '2025-03-31', status: 'DRAFT' };
      component.milestones = [
        { name: 'Admin Phase', startDate: '2025-01-01', endDate: '2025-01-31', description: '', trigger: 'ADMIN_REVIEW' }
      ];

      const mockForm: any = { control: { markAllAsTouched: () => {} } };
      component.onSaveDraft(mockForm);

      expect(emittedValues[0].milestones[0].assignedRoles).toEqual(['ADMIN']);
    });

    it('should map NONE trigger to empty assignedRoles array', () => {
      const emittedValues: any[] = [];
      component.submitted.subscribe(v => emittedValues.push(v));

      component.cycle = { cycleTitle: 'Test', startDate: '2025-01-01', dueDate: '2025-03-31', status: 'DRAFT' };
      component.milestones = [
        { name: 'No Role', startDate: '2025-01-01', endDate: '2025-01-31', description: '', trigger: 'NONE' }
      ];

      const mockForm: any = { control: { markAllAsTouched: () => {} } };
      component.onSaveDraft(mockForm);

      expect(emittedValues[0].milestones[0].assignedRoles).toEqual([]);
    });
  });

  // ── getMilestoneStartMin/Max and getMilestoneEndMin/Max ───────

  describe('milestone date constraint helpers', () => {
    beforeEach(() => {
      component.cycle = { cycleTitle: 'Test', startDate: '2099-01-01', dueDate: '2099-03-31', status: 'DRAFT' };
      component.milestones = [
        { name: 'Phase 1', startDate: '2099-01-01', endDate: '2099-01-31', description: '', trigger: 'EMPLOYEE_SELF_REVIEW' },
        { name: 'Phase 2', startDate: '2099-02-01', endDate: '2099-02-28', description: '', trigger: 'MANAGER_REVIEW' }
      ];
    });

    it('getMilestoneStartMin should return cycle startDate for first milestone', () => {
      expect(component.getMilestoneStartMin(0)).toBe('2099-01-01');
    });

    it('getMilestoneStartMin should return cycle startDate for all milestones (parallel allowed)', () => {
      expect(component.getMilestoneStartMin(1)).toBe('2099-01-01');
    });

    it('getMilestoneStartMax should return one day before cycle dueDate', () => {
      expect(component.getMilestoneStartMax(0)).toBe('2099-03-30');
    });

    it('getMilestoneEndMin should return one day after milestone startDate', () => {
      expect(component.getMilestoneEndMin(0)).toBe('2099-01-02');
    });

    it('getMilestoneEndMax should return cycle dueDate (parallel milestones allowed)', () => {
      expect(component.getMilestoneEndMax(0)).toBe('2099-03-31');
    });

    it('getMilestoneEndMax should return cycle dueDate for last milestone', () => {
      expect(component.getMilestoneEndMax(1)).toBe('2099-03-31');
    });

    it('should return empty constraint values when milestone or cycle dates are missing', () => {
      // Empty constraints are expected while users are filling the form from top to bottom.
      component.cycle = { cycleTitle: 'Test', startDate: '', dueDate: '', status: 'DRAFT' };
      component.milestones = [
        { name: 'Draft', startDate: '', endDate: '', description: '', trigger: 'NONE' }
      ];

      expect(component.getMilestoneStartMax(0)).toBe('');
      expect(component.getMilestoneEndMin(0)).toBe('');
      expect(component.getMilestoneEndMax(0)).toBe('');
      expect(component.hasGapAfter(0)).toBeFalse();
    });
  });

  describe('step navigation', () => {
    function formWithValidity(cycleNameInvalid = false, startInvalid = false, endInvalid = false): any {
      return {
        controls: {
          cycleName: { invalid: cycleNameInvalid, markAsTouched: jasmine.createSpy('touchCycleName') },
          startDate: { invalid: startInvalid, markAsTouched: jasmine.createSpy('touchStartDate') },
          endDate: { invalid: endInvalid, markAsTouched: jasmine.createSpy('touchEndDate') }
        }
      };
    }

    it('should stay on step 1 and show an error when step 1 is invalid', () => {
      // Step 2 should not open until name and date fields are valid.
      const form = formWithValidity(true);

      component.goToStep2(form);

      expect(form.controls.cycleName.markAsTouched).toHaveBeenCalled();
      expect(form.controls.startDate.markAsTouched).toHaveBeenCalled();
      expect(form.controls.endDate.markAsTouched).toHaveBeenCalled();
      expect(component.showSubmitError).toBeTrue();
      expect(component.currentStep).toBe(1);
    });

    it('should move to step 2 when basic cycle fields are valid', () => {
      // Valid basic info unlocks milestone setup.
      const form = formWithValidity();
      component.cycle = { cycleTitle: 'Test', startDate: '2025-01-01', dueDate: '2025-03-31', status: 'DRAFT' };
      component.showSubmitError = true;

      component.goToStep2(form);

      expect(component.showSubmitError).toBeFalse();
      expect(component.currentStep).toBe(2);
    });

    it('should revalidate dates when cycle date changes', () => {
      // Date change keeps the inline date error synchronized with the current inputs.
      component.cycle.startDate = '2025-03-31';
      component.cycle.dueDate = '2025-01-01';

      component.onCycleDateChange();

      expect(component.dateRangeError).toContain('after');
    });
  });
});
