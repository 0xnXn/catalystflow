import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { of, throwError } from 'rxjs';

import { ReviewCyclesComponent } from './review-cycles.component';
import { ReviewCyclesService } from '../../services/review-cycles.service';

const mockCycles = [
  {
    cycleId: 'cyc001',
    cycleTitle: 'Q1 2025 Review',
    startDate: '2025-01-01',
    dueDate: '2025-03-31',
    status: 'ACTIVE',
    milestones: []
  },
  {
    cycleId: 'cyc002',
    cycleTitle: 'Q2 2025 Review',
    startDate: '2025-04-01',
    dueDate: '2025-06-30',
    status: 'DRAFT',
    milestones: []
  },
  {
    cycleId: 'cyc003',
    cycleTitle: 'Q3 2025 Review',
    startDate: '2025-07-01',
    dueDate: '2025-09-30',
    status: 'COMPLETED',
    milestones: []
  }
];

describe('ReviewCyclesComponent', () => {
  let component: ReviewCyclesComponent;
  let serviceSpy: jasmine.SpyObj<ReviewCyclesService>;

  beforeEach(async () => {
    serviceSpy = jasmine.createSpyObj('ReviewCyclesService', [
      'getCycles', 'createCycle', 'updateCycle', 'publishCycle', 'deleteCycle',
      'activateCycle', 'completeCycle'
    ]);
    serviceSpy.getCycles.and.returnValue(of([...mockCycles]));

    await TestBed.configureTestingModule({
      imports: [ReviewCyclesComponent, CommonModule, FormsModule],
      providers: [{ provide: ReviewCyclesService, useValue: serviceSpy }]
    }).compileComponents();

    const fixture = TestBed.createComponent(ReviewCyclesComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should be created', () => {
    expect(component).toBeTruthy();
  });

  // ── ngOnInit / loadCycles ─────────────────────────────────────

  describe('loadCycles()', () => {
    it('should load cycles on init and populate filteredCycles', () => {
      expect(component.cycles.length).toBe(3);
      expect(component.filteredCycles.length).toBe(3);
      expect(component.isLoading).toBeFalse();
    });

    it('should sort cycles: ACTIVE first, then DRAFT, then COMPLETED', () => {
      expect(component.filteredCycles[0].status).toBe('ACTIVE');
      expect(component.filteredCycles[1].status).toBe('DRAFT');
      expect(component.filteredCycles[2].status).toBe('COMPLETED');
    });

    it('should show error notification when getCycles fails', () => {
      serviceSpy.getCycles.and.returnValue(throwError(() => new Error('Network error')));
      component.loadCycles();
      expect(component.notification.show).toBeTrue();
      expect(component.notification.type).toBe('error');
    });

    // Backend responses can arrive either as a raw array or as { cycles },
    // so the component normalizes both before filtering and rendering.
    it('should load cycles from an object response', () => {
      serviceSpy.getCycles.and.returnValue(of({ cycles: [mockCycles[1]] }));

      component.loadCycles();

      expect(component.cycles).toEqual([mockCycles[1]]);
      expect(component.filteredCycles).toEqual([mockCycles[1]]);
      expect(component.isLoading).toBeFalse();
    });
  });

  // ── stat counts ───────────────────────────────────────────────

  describe('stat counts', () => {
    it('should return correct totalCount', () => {
      expect(component.totalCount).toBe(3);
    });

    it('should return correct draftCount', () => {
      expect(component.draftCount).toBe(1);
    });

    it('should return correct activeCount', () => {
      expect(component.activeCount).toBe(1);
    });

    it('should return correct completedCount', () => {
      expect(component.completedCount).toBe(1);
    });
  });

  // ── filter ────────────────────────────────────────────────────

  describe('filter()', () => {
    it('should filter cycles by cycleTitle', () => {
      component.searchTerm = 'Q1';
      component.filter();
      expect(component.filteredCycles.length).toBe(1);
      expect(component.filteredCycles[0].cycleTitle).toBe('Q1 2025 Review');
    });

    it('should filter cycles by status', () => {
      component.searchTerm = 'draft';
      component.filter();
      expect(component.filteredCycles.length).toBe(1);
      expect(component.filteredCycles[0].status).toBe('DRAFT');
    });

    it('should return all cycles when searchTerm is empty', () => {
      component.searchTerm = '';
      component.filter();
      expect(component.filteredCycles.length).toBe(3);
    });

    it('should return empty array when no cycles match search', () => {
      component.searchTerm = 'zzz-nomatch';
      component.filter();
      expect(component.filteredCycles.length).toBe(0);
    });

    // Within the same status bucket, newest start date should appear first.
    it('should sort cycles with unknown statuses last and by start date descending', () => {
      component.cycles = [
        { cycleId: 'old', cycleTitle: 'Old Draft', startDate: '2025-01-01', status: 'DRAFT' },
        { cycleId: 'new', cycleTitle: 'New Draft', startDate: '2025-02-01', status: 'DRAFT' },
        { cycleId: 'unknown', cycleTitle: 'Unknown', startDate: '2025-03-01', status: 'PAUSED' }
      ];

      component.searchTerm = '';
      component.filter();

      expect(component.filteredCycles.map(c => c.cycleId)).toEqual(['new', 'old', 'unknown']);
    });
  });

  // â”€â”€ cycle grouping and milestone helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

  describe('cycle grouping and milestone helpers', () => {
    it('should separate current/upcoming cycles from past completed cycles', () => {
      expect(component.currentCycles.map(c => c.cycleId)).toEqual(['cyc001', 'cyc002']);
      expect(component.pastCycles.map(c => c.cycleId)).toEqual(['cyc003']);
    });

    // Milestones are treated as done by explicit status, completed parent cycle,
    // or when their end date is already in the past.
    it('should determine completed milestones from status, parent status, and dates', () => {
      expect(component.isMilestoneDone({ status: 'COMPLETED' }, 'ACTIVE')).toBeTrue();
      expect(component.isMilestoneDone({ status: 'COMPLETE' }, 'ACTIVE')).toBeTrue();
      expect(component.isMilestoneDone({ status: 'OPEN' }, 'COMPLETED')).toBeTrue();
      expect(component.isMilestoneDone({ status: 'OPEN', endDate: '01/01/2000' }, 'ACTIVE')).toBeTrue();
      expect(component.isMilestoneDone({ status: 'OPEN', endDate: '2999-01-01' }, 'ACTIVE')).toBeFalse();
      expect(component.isMilestoneDone({ status: 'OPEN' }, 'ACTIVE')).toBeFalse();
    });

    it('should count completed milestones safely when milestones are missing', () => {
      expect(component.completedMilestones({ status: 'ACTIVE' })).toBe(0);
      expect(component.completedMilestones({
        status: 'ACTIVE',
        milestones: [
          { status: 'COMPLETED' },
          { status: 'OPEN', endDate: '2999-01-01' },
          { status: 'OPEN', endDate: '01/01/2000' }
        ]
      })).toBe(2);
    });

    // Dates can come from old DD/MM/YYYY data, ISO strings, blank values, or
    // malformed strings from imports; all are displayed without crashing.
    it('should format supported, blank, and invalid dates', () => {
      expect(component.formatDate('01/05/2025')).toContain('2025');
      expect(component.formatDate('2025-05-01')).toContain('2025');
      expect(component.formatDate()).toBeTruthy();
      expect(component.formatDate('not-a-date')).toBe('not-a-date');
    });
  });

  // ── toggleExpand ──────────────────────────────────────────────

  describe('toggleExpand()', () => {
    it('should expand a cycle row', () => {
      component.toggleExpand('cyc001');
      expect(component.expandedCycleId).toBe('cyc001');
    });

    it('should collapse already-expanded row', () => {
      component.toggleExpand('cyc001');
      component.toggleExpand('cyc001');
      expect(component.expandedCycleId).toBeNull();
    });

    it('should switch expanded row to a different cycle', () => {
      component.toggleExpand('cyc001');
      component.toggleExpand('cyc002');
      expect(component.expandedCycleId).toBe('cyc002');
    });
  });

  // ── modal open/close ──────────────────────────────────────────

  describe('modal state', () => {
    it('should open create modal', () => {
      component.openCreateModal();
      expect(component.isCreateModalOpen).toBeTrue();
    });

    it('should close create modal', () => {
      component.isCreateModalOpen = true;
      component.closeCreateModal();
      expect(component.isCreateModalOpen).toBeFalse();
    });

    it('should open edit modal with cycle data', () => {
      component.openEditModal(mockCycles[0]);
      expect(component.editTarget).toEqual(mockCycles[0]);
    });

    it('should close edit modal and clear editTarget', () => {
      component.editTarget = mockCycles[0];
      component.closeEditModal();
      expect(component.editTarget).toBeNull();
    });
  });

  // ── onCycleCreated ────────────────────────────────────────────

  describe('onCycleCreated()', () => {
    it('should call createCycle and reload cycles on success', () => {
      serviceSpy.createCycle.and.returnValue(of({ cycleId: 'cyc004' }));
      component.isCreateModalOpen = true;

      component.onCycleCreated({
        cycleTitle: 'New Cycle',
        startDate: '2025-10-01',
        dueDate: '2025-12-31',
        status: 'DRAFT',
        milestones: [],
        publishAfter: false
      });

      expect(serviceSpy.createCycle).toHaveBeenCalled();
      expect(component.isCreateModalOpen).toBeFalse();
      expect(component.notification.show).toBeTrue();
      expect(component.notification.type).toBe('success');
    });

    it('should call publishCycle after create when publishAfter is true', () => {
      serviceSpy.createCycle.and.returnValue(of({ cycleId: 'cyc004' }));
      serviceSpy.publishCycle.and.returnValue(of({ message: 'published' }));

      component.onCycleCreated({
        cycleTitle: 'New Cycle',
        startDate: '2025-10-01',
        dueDate: '2025-12-31',
        status: 'DRAFT',
        milestones: [],
        publishAfter: true
      });

      expect(serviceSpy.publishCycle).toHaveBeenCalledWith('cyc004');
    });

    it('should show error notification when createCycle fails', () => {
      serviceSpy.createCycle.and.returnValue(throwError(() => new Error('Failed')));

      component.onCycleCreated({
        cycleTitle: 'New Cycle',
        startDate: '2025-10-01',
        dueDate: '2025-12-31',
        status: 'DRAFT',
        milestones: [],
        publishAfter: false
      });

      expect(component.notification.type).toBe('error');
    });

    it('should show error notification when publishCycle fails after create', () => {
      serviceSpy.createCycle.and.returnValue(of({ cycleId: 'cyc004' }));
      serviceSpy.publishCycle.and.returnValue(throwError(() => new Error('Publish failed')));

      component.onCycleCreated({
        cycleTitle: 'New Cycle',
        startDate: '2025-10-01',
        dueDate: '2025-12-31',
        status: 'DRAFT',
        milestones: [],
        publishAfter: true
      });

      expect(component.notification.type).toBe('error');
      expect(component.notification.message).toContain('publish failed');
    });
  });

  // ── onCycleSaved ──────────────────────────────────────────────

  describe('onCycleSaved()', () => {
    it('should call updateCycle and reload on save draft', () => {
      serviceSpy.updateCycle.and.returnValue(of({}));
      component.editTarget = mockCycles[0];

      component.onCycleSaved({ ...mockCycles[0], publishAfter: false });

      expect(serviceSpy.updateCycle).toHaveBeenCalledWith('cyc001', jasmine.any(Object));
      expect(component.editTarget).toBeNull();
    });

    it('should call publishCycle after update when publishAfter is true', () => {
      serviceSpy.updateCycle.and.returnValue(of({}));
      serviceSpy.publishCycle.and.returnValue(of({ message: 'published' }));

      component.onCycleSaved({ ...mockCycles[1], publishAfter: true });

      expect(serviceSpy.publishCycle).toHaveBeenCalledWith('cyc002');
    });

    it('should show error notification when updateCycle fails', () => {
      serviceSpy.updateCycle.and.returnValue(throwError(() => new Error('Failed')));

      component.onCycleSaved({ ...mockCycles[0], publishAfter: false });

      expect(component.notification.type).toBe('error');
    });

    it('should show error notification when publishCycle fails after save', () => {
      serviceSpy.updateCycle.and.returnValue(of({}));
      serviceSpy.publishCycle.and.returnValue(throwError(() => new Error('Publish failed')));

      component.onCycleSaved({ ...mockCycles[1], publishAfter: true });

      expect(component.notification.type).toBe('error');
      expect(component.notification.message).toContain('publish failed');
    });

    it('should sort SCHEDULED cycles after DRAFT', () => {
      const withScheduled = [
        ...mockCycles,
        { cycleId: 'cyc004', cycleTitle: 'Q4 Scheduled', startDate: '2025-10-01', dueDate: '2025-12-31', status: 'SCHEDULED', milestones: [] }
      ];
      serviceSpy.getCycles.and.returnValue(of(withScheduled));
      component.loadCycles();
      const statuses = component.filteredCycles.map(c => c.status);
      expect(statuses.indexOf('DRAFT')).toBeLessThan(statuses.indexOf('SCHEDULED'));
    });
  });

  // ── delete ────────────────────────────────────────────────────

  describe('delete flow', () => {
    it('should open delete confirm dialog with cycle', () => {
      component.openDeleteConfirm(mockCycles[1]);
      expect(component.deleteConfirm.show).toBeTrue();
      expect(component.deleteConfirm.cycle).toEqual(mockCycles[1]);
    });

    it('should close delete confirm dialog', () => {
      component.deleteConfirm = { show: true, cycle: mockCycles[1] };
      component.closeDeleteConfirm();
      expect(component.deleteConfirm.show).toBeFalse();
      expect(component.deleteConfirm.cycle).toBeNull();
    });

    it('should call deleteCycle and reload on confirm', () => {
      serviceSpy.deleteCycle.and.returnValue(of({}));
      component.deleteConfirm = { show: true, cycle: mockCycles[1] };

      component.confirmDelete();

      expect(serviceSpy.deleteCycle).toHaveBeenCalledWith('cyc002');
      expect(component.notification.show).toBeTrue();
    });

    it('should show error notification when deleteCycle fails', () => {
      serviceSpy.deleteCycle.and.returnValue(throwError(() => new Error('Failed')));
      component.deleteConfirm = { show: true, cycle: mockCycles[1] };

      component.confirmDelete();

      expect(component.notification.type).toBe('error');
    });
  });

  // â”€â”€ activate / end cycle flows â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

  describe('activate and end cycle flows', () => {
    it('should activate a scheduled cycle and reload cycles', () => {
      serviceSpy.activateCycle.and.returnValue(of({}));

      component.activateCycle({ cycleId: 'cyc004', cycleTitle: 'Scheduled Cycle' });

      expect(serviceSpy.activateCycle).toHaveBeenCalledWith('cyc004');
      expect(serviceSpy.getCycles).toHaveBeenCalled();
      expect(component.notification.message).toContain('activated');
      expect(component.notification.type).toBe('success');
    });

    it('should show backend activation errors when available', () => {
      serviceSpy.activateCycle.and.returnValue(throwError(() => ({
        error: { message: 'Cycle cannot be activated before start date.' }
      })));

      component.activateCycle({ cycleId: 'cyc004', cycleTitle: 'Scheduled Cycle' });

      expect(component.notification.type).toBe('error');
      expect(component.notification.message).toBe('Cycle cannot be activated before start date.');
    });

    it('should open and close end confirmation', () => {
      component.openEndConfirm(mockCycles[0]);
      expect(component.endConfirm).toEqual({ show: true, cycle: mockCycles[0] });

      component.closeEndConfirm();
      expect(component.endConfirm).toEqual({ show: false, cycle: null });
    });

    it('should complete an active cycle after confirmation', () => {
      serviceSpy.completeCycle.and.returnValue(of({}));
      component.endConfirm = { show: true, cycle: mockCycles[0] };

      component.confirmEnd();

      expect(serviceSpy.completeCycle).toHaveBeenCalledWith('cyc001');
      expect(component.endConfirm.show).toBeFalse();
      expect(component.notification.message).toContain('ended');
      expect(component.notification.type).toBe('success');
    });

    it('should show fallback end-cycle errors when backend message is missing', () => {
      serviceSpy.completeCycle.and.returnValue(throwError(() => ({})));
      component.endConfirm = { show: true, cycle: mockCycles[0] };

      component.confirmEnd();

      expect(component.notification.type).toBe('error');
      expect(component.notification.message).toBe('Failed to end cycle.');
    });
  });

  // ── showNotification ──────────────────────────────────────────

  describe('showNotification()', () => {
    it('should show a success notification', fakeAsync(() => {
      component.showNotification('Cycle saved.');
      expect(component.notification.show).toBeTrue();
      expect(component.notification.message).toBe('Cycle saved.');
      expect(component.notification.type).toBe('success');
      tick(4000);
      expect(component.notification.show).toBeFalse();
    }));

    it('should show an error notification', fakeAsync(() => {
      component.showNotification('Something went wrong.', 'error');
      expect(component.notification.type).toBe('error');
      tick(4000);
      expect(component.notification.show).toBeFalse();
    }));
  });
});
