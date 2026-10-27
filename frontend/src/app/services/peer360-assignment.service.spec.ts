import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../environment/environment';
import { Review } from '../models/review.model';
import { CreatePeer360AssignmentsRequest, Peer360AssignmentResult } from '../models/peer360-assignment.model';
import { Peer360AssignmentService } from './peer360-assignment.service';

describe('Peer360AssignmentService', () => {
  let service: Peer360AssignmentService;
  let httpMock: HttpTestingController;
  const baseUrl = `${environment.apiUrl}/cycles`;

  const review: Review = {
    reviewId: 'peer-review-1',
    cycleId: 'cycle/1',
    userId: 'peer-1',
    subjectUserId: 'subject A',
    authorUserId: 'peer-1',
    reviewType: 'PEER_360',
    status: 'NOT_STARTED',
    answers: [],
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        Peer360AssignmentService,
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });

    service = TestBed.inject(Peer360AssignmentService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    // Makes sure each manager-driven 360 endpoint is explicitly asserted.
    httpMock.verify();
  });

  it('creates peer360 assignments with an encoded cycle id and idempotent batch body', () => {
    const body: CreatePeer360AssignmentsRequest = {
      subjectUserId: 'subject A',
      authorUserIds: ['peer-1', 'peer-2'],
      templateId: 'tpl-peer',
    };
    const result: Peer360AssignmentResult = {
      created: [review],
      skipped: [],
      all: [review],
    };

    service.create('cycle/1', body).subscribe(response => expect(response).toEqual(result));

    const req = httpMock.expectOne(`${baseUrl}/cycle%2F1/peer360-assignments`);
    expect(req.request.method).toBe('POST');
    // Backend owns idempotency; frontend should pass the full batch through unchanged.
    expect(req.request.body).toEqual(body);
    req.flush(result);
  });

  it('lists assignments for a subject using the subjectUserId query param', () => {
    service.list('cycle/1', 'subject A').subscribe(assignments => {
      expect(assignments).toEqual([review]);
    });

    const req = httpMock.expectOne(request =>
      request.url === `${baseUrl}/cycle%2F1/peer360-assignments` &&
      request.params.get('subjectUserId') === 'subject A'
    );
    expect(req.request.method).toBe('GET');
    req.flush([review]);
  });

  it('removes an assignment using encoded cycle and review ids', () => {
    let completed = false;

    service.remove('cycle/1', 'review/1').subscribe(() => completed = true);

    const req = httpMock.expectOne(`${baseUrl}/cycle%2F1/peer360-assignments/review%2F1`);
    expect(req.request.method).toBe('DELETE');
    // Backend only permits removal for NOT_STARTED rows; this client just targets the row.
    req.flush(null);

    expect(completed).toBeTrue();
  });
});
