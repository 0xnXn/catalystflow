import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting
} from '@angular/common/http/testing';

import { ReviewCyclesService } from './review-cycles.service';
import { environment } from '../../environment/environment';

describe('ReviewCyclesService', () => {
  let service: ReviewCyclesService;
  let httpMock: HttpTestingController;
  const baseUrl = `${environment.apiUrl}/cycles`;

  const mockCycle = {
    cycleId: 'cyc001',
    cycleTitle: 'Q1 2025 Review',
    startDate: '2025-01-01',
    dueDate: '2025-03-31',
    status: 'DRAFT',
    milestones: []
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        ReviewCyclesService,
        provideHttpClient(),
        provideHttpClientTesting()
      ]
    });

    service = TestBed.inject(ReviewCyclesService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  // ── getCycles ─────────────────────────────────────────────────

  describe('getCycles()', () => {
    it('should GET /cycles and return normalized array', () => {
      service.getCycles().subscribe(cycles => {
        expect(cycles.length).toBe(1);
        expect(cycles[0].cycleId).toBe('cyc001');
      });

      const req = httpMock.expectOne(baseUrl);
      expect(req.request.method).toBe('GET');
      req.flush([mockCycle]);
    });

    it('should unwrap { cycles: [] } response envelope', () => {
      service.getCycles().subscribe(cycles => {
        expect(cycles.length).toBe(1);
        expect(cycles[0].cycleTitle).toBe('Q1 2025 Review');
      });

      const req = httpMock.expectOne(baseUrl);
      req.flush({ cycles: [mockCycle] });
    });

    it('should normalize dd/MM/yyyy dates to yyyy-MM-dd', () => {
      const rawCycle = {
        ...mockCycle,
        startDate: '01/01/2025',
        dueDate: '31/03/2025',
        milestones: [
          { name: 'Phase 1', startDate: '01/01/2025', endDate: '15/01/2025', assignedRoles: ['EMPLOYEE'] }
        ]
      };

      service.getCycles().subscribe(cycles => {
        expect(cycles[0].startDate).toBe('2025-01-01');
        expect(cycles[0].dueDate).toBe('2025-03-31');
        expect(cycles[0].milestones[0].startDate).toBe('2025-01-01');
        expect(cycles[0].milestones[0].endDate).toBe('2025-01-15');
      });

      httpMock.expectOne(baseUrl).flush([rawCycle]);
    });

    it('should leave already-ISO dates unchanged', () => {
      service.getCycles().subscribe(cycles => {
        expect(cycles[0].startDate).toBe('2025-01-01');
        expect(cycles[0].dueDate).toBe('2025-03-31');
      });

      httpMock.expectOne(baseUrl).flush([mockCycle]);
    });

    it('should map assignedRoles to trigger for each milestone', () => {
      const raw = {
        ...mockCycle,
        milestones: [
          { name: 'Self Review', startDate: '2025-01-01', endDate: '2025-01-15', assignedRoles: ['EMPLOYEE'] },
          { name: 'Manager Review', startDate: '2025-01-16', endDate: '2025-01-31', assignedRoles: ['MANAGER'] },
          { name: 'Admin Review', startDate: '2025-02-01', endDate: '2025-02-15', assignedRoles: ['ADMIN'] },
          { name: 'No Role', startDate: '2025-02-16', endDate: '2025-02-28', assignedRoles: [] }
        ]
      };

      service.getCycles().subscribe(cycles => {
        const ms = cycles[0].milestones;
        expect(ms[0].trigger).toBe('EMPLOYEE_SELF_REVIEW');
        expect(ms[1].trigger).toBe('MANAGER_REVIEW');
        expect(ms[2].trigger).toBe('ADMIN_REVIEW');
        expect(ms[3].trigger).toBe('NONE');
      });

      httpMock.expectOne(baseUrl).flush([raw]);
    });

    it('should default trigger to NONE when assignedRoles is missing', () => {
      const raw = {
        ...mockCycle,
        milestones: [{ name: 'Phase 1', startDate: '2025-01-01', endDate: '2025-01-15' }]
      };

      service.getCycles().subscribe(cycles => {
        expect(cycles[0].milestones[0].trigger).toBe('NONE');
      });

      httpMock.expectOne(baseUrl).flush([raw]);
    });
  });

  // ── getCycle ──────────────────────────────────────────────────

  describe('getCycle()', () => {
    it('should GET /cycles/:id and return normalized cycle', () => {
      service.getCycle('cyc001').subscribe(cycle => {
        expect(cycle.cycleId).toBe('cyc001');
        expect(cycle.startDate).toBe('2025-01-01');
      });

      const req = httpMock.expectOne(`${baseUrl}/cyc001`);
      expect(req.request.method).toBe('GET');
      req.flush(mockCycle);
    });

    it('should normalize dates on single cycle fetch', () => {
      const raw = { ...mockCycle, startDate: '01/01/2025', dueDate: '31/03/2025' };

      service.getCycle('cyc001').subscribe(cycle => {
        expect(cycle.startDate).toBe('2025-01-01');
        expect(cycle.dueDate).toBe('2025-03-31');
      });

      httpMock.expectOne(`${baseUrl}/cyc001`).flush(raw);
    });
  });

  // ── createCycle ───────────────────────────────────────────────

  describe('createCycle()', () => {
    it('should POST /cycles with payload', () => {
      const payload = {
        cycleTitle: 'Q2 2025 Review',
        startDate: '2025-04-01',
        dueDate: '2025-06-30',
        status: 'DRAFT',
        milestones: [],
        publishAfter: false
      };
      const created = { ...payload, cycleId: 'cyc002' };

      service.createCycle(payload).subscribe(result => {
        expect(result).toEqual(created);
        expect(result.cycleId).toBe('cyc002');
      });

      const req = httpMock.expectOne(baseUrl);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(payload);
      req.flush(created);
    });
  });

  // ── updateCycle ───────────────────────────────────────────────

  describe('updateCycle()', () => {
    it('should PUT /cycles/:id with payload', () => {
      const payload = { ...mockCycle, cycleTitle: 'Updated Title' };

      service.updateCycle('cyc001', payload).subscribe(result => {
        expect(result).toEqual(payload);
      });

      const req = httpMock.expectOne(`${baseUrl}/cyc001`);
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual(payload);
      req.flush(payload);
    });
  });

  // ── publishCycle ──────────────────────────────────────────────

  describe('publishCycle()', () => {
    it('should POST /cycles/:id/publish with empty body', () => {
      const response = { message: 'Cycle published' };

      service.publishCycle('cyc001').subscribe(result => {
        expect(result).toEqual(response);
      });

      const req = httpMock.expectOne(`${baseUrl}/cyc001/publish`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({});
      req.flush(response);
    });
  });

  // ── deleteCycle ───────────────────────────────────────────────

  describe('deleteCycle()', () => {
    it('should DELETE /cycles/:id', () => {
      const response = { message: 'Cycle deleted' };

      service.deleteCycle('cyc001').subscribe(result => {
        expect(result).toEqual(response);
      });

      const req = httpMock.expectOne(`${baseUrl}/cyc001`);
      expect(req.request.method).toBe('DELETE');
      req.flush(response);
    });
  });

  // ── HTTP error handling ───────────────────────────────────────

  describe('HTTP error handling', () => {
    it('getCycles() should propagate server error', () => {
      let errorOccurred = false;
      service.getCycles().subscribe({
        next: () => fail('expected error'),
        error: () => { errorOccurred = true; }
      });
      httpMock.expectOne(baseUrl).flush(null, { status: 500, statusText: 'Server Error' });
      expect(errorOccurred).toBeTrue();
    });

    it('getCycle() should propagate server error', () => {
      let errorOccurred = false;
      service.getCycle('cyc001').subscribe({
        next: () => fail('expected error'),
        error: () => { errorOccurred = true; }
      });
      httpMock.expectOne(`${baseUrl}/cyc001`).flush(null, { status: 404, statusText: 'Not Found' });
      expect(errorOccurred).toBeTrue();
    });

    it('createCycle() should propagate server error', () => {
      let errorOccurred = false;
      service.createCycle({}).subscribe({
        next: () => fail('expected error'),
        error: () => { errorOccurred = true; }
      });
      httpMock.expectOne(baseUrl).flush(null, { status: 400, statusText: 'Bad Request' });
      expect(errorOccurred).toBeTrue();
    });

    it('updateCycle() should propagate server error', () => {
      let errorOccurred = false;
      service.updateCycle('cyc001', {}).subscribe({
        next: () => fail('expected error'),
        error: () => { errorOccurred = true; }
      });
      httpMock.expectOne(`${baseUrl}/cyc001`).flush(null, { status: 500, statusText: 'Server Error' });
      expect(errorOccurred).toBeTrue();
    });

    it('publishCycle() should propagate server error', () => {
      let errorOccurred = false;
      service.publishCycle('cyc001').subscribe({
        next: () => fail('expected error'),
        error: () => { errorOccurred = true; }
      });
      httpMock.expectOne(`${baseUrl}/cyc001/publish`).flush(null, { status: 500, statusText: 'Server Error' });
      expect(errorOccurred).toBeTrue();
    });

    it('deleteCycle() should propagate server error', () => {
      let errorOccurred = false;
      service.deleteCycle('cyc001').subscribe({
        next: () => fail('expected error'),
        error: () => { errorOccurred = true; }
      });
      httpMock.expectOne(`${baseUrl}/cyc001`).flush(null, { status: 500, statusText: 'Server Error' });
      expect(errorOccurred).toBeTrue();
    });

    it('ADMIN assignedRoles should map to ADMIN_REVIEW trigger', () => {
      const raw = {
        ...mockCycle,
        milestones: [{ name: 'Admin Phase', startDate: '2025-01-01', endDate: '2025-01-31', assignedRoles: ['ADMIN'] }]
      };
      service.getCycles().subscribe(cycles => {
        expect(cycles[0].milestones[0].trigger).toBe('ADMIN_REVIEW');
      });
      httpMock.expectOne(baseUrl).flush([raw]);
    });
  });
});
