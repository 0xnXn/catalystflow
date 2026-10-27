import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../environment/environment';
import { Review } from '../models/review.model';
import {
  CreatePeer360AssignmentsRequest,
  Peer360AssignmentResult,
} from '../models/peer360-assignment.model';

/**
 * Frontend client for the manager-driven 360 assignment endpoints.
 *
 * Backend routes:
 *   POST   /cycles/{cycleId}/peer360-assignments              — idempotent batch create
 *   GET    /cycles/{cycleId}/peer360-assignments?subjectUserId=...
 *                                                              — list (manager / admin)
 *   DELETE /cycles/{cycleId}/peer360-assignments/{reviewId}   — remove if NOT_STARTED
 */
@Injectable({ providedIn: 'root' })
export class Peer360AssignmentService {
  private readonly base = environment.apiUrl + '/cycles';

  constructor(private http: HttpClient) {}

  create(
    cycleId: string,
    body: CreatePeer360AssignmentsRequest,
  ): Observable<Peer360AssignmentResult> {
    return this.http.post<Peer360AssignmentResult>(
      `${this.base}/${encodeURIComponent(cycleId)}/peer360-assignments`,
      body,
    );
  }

  list(cycleId: string, subjectUserId: string): Observable<Review[]> {
    return this.http.get<Review[]>(
      `${this.base}/${encodeURIComponent(cycleId)}/peer360-assignments`,
      { params: { subjectUserId } },
    );
  }

  remove(cycleId: string, reviewId: string): Observable<void> {
    return this.http.delete<void>(
      `${this.base}/${encodeURIComponent(cycleId)}/peer360-assignments/${encodeURIComponent(reviewId)}`,
    );
  }
}
