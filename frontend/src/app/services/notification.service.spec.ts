import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';

import { environment } from '../../environment/environment';
import { NotificationService } from './notification.service';

describe('NotificationService', () => {
  let service: NotificationService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        NotificationService,
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });

    service = TestBed.inject(NotificationService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('posts manual reminder requests to the notification endpoint', () => {
    const body = {
      cycleId: 'cycle-1',
      targetType: 'SELF_REVIEW_SUBJECT' as const,
      subjectUserIds: ['user-1'],
    };
    const response = {
      created: [{ jobId: 'job-1', status: 'PENDING' as const }],
      skipped: [],
    };

    service.sendManualReminder(body).subscribe(result => {
      expect(result).toEqual(response);
    });

    const req = httpMock.expectOne(`${environment.apiUrl}/notifications/reminders/manual`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    expect(req.request.responseType).toBe('json');
    req.flush(response);
  });

  it('gets notification job status with an encoded job id', () => {
    const response = { jobId: 'MANUAL#2026-05-20#u#r', status: 'COMPLETED' as const };

    service.getJobStatus('MANUAL#2026-05-20#u#r').subscribe(result => {
      expect(result).toEqual(response);
    });

    const req = httpMock.expectOne(
      `${environment.apiUrl}/notifications/jobs/MANUAL%232026-05-20%23u%23r/status`,
    );
    expect(req.request.method).toBe('GET');
    req.flush(response);
  });
});
