import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { ReviewService } from './review.services';
import { environment } from '../../environment/environment';

describe('ReviewService', () => {
  let service: ReviewService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        ReviewService,
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });

    service = TestBed.inject(ReviewService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('posts the review body wrapped under `body` to the /reviews endpoint', () => {
    const responsePayload = { reviewId: 'r-1' };

    service.submitReview('My self-review text.').subscribe(result => {
      expect(result).toEqual(responsePayload);
    });

    const req = httpMock.expectOne(`${environment.apiUrl}/reviews`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ body: 'My self-review text.' });
    req.flush(responsePayload);
  });

  it('propagates HTTP errors to the subscriber', () => {
    let errorStatus = 0;

    service.submitReview('text').subscribe({
      next: () => fail('expected error'),
      error: err => { errorStatus = err.status; },
    });

    const req = httpMock.expectOne(`${environment.apiUrl}/reviews`);
    req.flush({ message: 'boom' }, { status: 500, statusText: 'Server Error' });

    expect(errorStatus).toBe(500);
  });

  it('sends an empty body when the caller passes an empty string', () => {
    service.submitReview('').subscribe();

    const req = httpMock.expectOne(`${environment.apiUrl}/reviews`);
    expect(req.request.body).toEqual({ body: '' });
    req.flush({});
  });
});
