import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, shareReplay, tap } from 'rxjs';
import { environment } from '../../environment/environment';
import { EmployeeStatus, User } from '../models/employee.model';
import { Review, ReviewTemplate } from '../models/review.model';
import { map } from 'rxjs/operators';

export type MilestoneStatus = 'NOT_STARTED' | 'OPEN' | 'IN_PROGRESS' | 'COMPLETE' | 'COMPLETED';
export type CycleStatus = 'DRAFT' | 'SCHEDULED' | 'ACTIVE' | 'COMPLETED';

export interface PublicUser {
  userId: string;
  firstName: string;
  lastName: string;
  role: string;
}

export interface CycleMilestone {
  milestoneId: string;
  name: string;
  assignedRoles: string[];
  startDate: string;
  endDate: string;
  status: MilestoneStatus;
  templateSnapshot?: { templateId?: string; reviewType?: string; questions: import('../models/review.model').Question[] };
}

export interface ReviewCycle {
  cycleId: string;
  cycleTitle: string;
  templateId?: string;
  startDate: string;
  dueDate: string;
  milestones: CycleMilestone[];
  status: CycleStatus;
  createdAt?: string;
  updatedAt?: string;
}

@Injectable({
  providedIn: 'root'
})
export class EmployeeService {
  private cache$: Observable<User[]> | null = null;

  constructor(private https: HttpClient) {}

  getEmployees(): Observable<User[]> {
    if (!this.cache$) {
      this.cache$ = this.https.get<{ users: User[] }>(environment.apiUrl + '/users')
        .pipe(
          map(res => res.users),
          shareReplay(1)
        );
    }
    return this.cache$;
  }

  clearCache(): void {
    this.cache$ = null;
  }

  preload(): void {
    this.getEmployees().subscribe();
  }

  addEmployee(user: User): Observable<User> {
    return this.https.post<User>(environment.apiUrl + '/users', user).pipe(
      tap(() => this.clearCache())
    );
  }

  updateEmployee(user: User): Observable<User> {
    return this.https.put<User>(environment.apiUrl + '/users/' + user.userId, user).pipe(
      tap(() => this.clearCache())
    );
  }

  /**
   * Deactivates a user. The backend has no DELETE /users endpoint — deactivation is a
   * status flip via PUT so the audit trail and Cognito disable stay in sync.
   */
  deleteEmployee(userId: string): Observable<User> {
    return this.https.put<User>(
      environment.apiUrl + '/users/' + userId,
      { status: 'Archived' as EmployeeStatus }
    ).pipe(tap(() => this.clearCache()));
  }

  getUserById(userId: string): Observable<User> {
    return this.https.get<User>(environment.apiUrl + '/users/' + userId);
  }

  // Minimal name/role projection for the case where the caller is not
  // authorized to read the full /users/{userId} record (e.g. an EMPLOYEE
  // looking up their manager's display name on the dashboard). Use this
  // instead of getUserById whenever only firstName + lastName + role are
  // rendered.
  getUserPublic(userId: string): Observable<PublicUser> {
    return this.https.get<PublicUser>(environment.apiUrl + '/users/' + userId + '/public');
  }

  getMyTeam(userId: string): Observable<User[]> {
    return this.https.get<{ users: User[] }>(environment.apiUrl + '/users/' + userId + '/team')
      .pipe(map(res => res.users ?? []));
  }

  getAllUsers(): Observable<User[]> {
    return this.https.get<any>(environment.apiUrl + '/users').pipe(
      map(res => Array.isArray(res) ? res : (res.users ?? []))
    );
  }

  getTeamMembers(managerId: string): Observable<User[]> {
    return this.https.get<any>(`${environment.apiUrl}/users?reportingTo=${managerId}`).pipe(
      map(res => Array.isArray(res) ? res : (res.users ?? []))
    );
  }

  getCycles(status?: string): Observable<ReviewCycle[]> {
    const url = status
      ? `${environment.apiUrl}/cycles?status=${status}`
      : `${environment.apiUrl}/cycles`;
    return this.https.get<any>(url).pipe(
      map(res => Array.isArray(res) ? res : (res.cycles ?? []))
    );
  }

  // ── Reviews ──────────────────────────────────────────────────────────────

  getMyReviews(userId: string): Observable<Review[]> {
    return this.https.get<any>(`${environment.apiUrl}/reviews/assigned-to-me`).pipe(
      map(res => Array.isArray(res) ? res : (res.reviews ?? []))
    );
  }

  /** Reviews where user is the SUBJECT — includes SELF + MANAGER (no PEER_360). Used for history. */
  getMySubjectReviews(): Observable<Review[]> {
    return this.https.get<any>(`${environment.apiUrl}/reviews/my-review`).pipe(
      map(res => Array.isArray(res) ? res : (res.reviews ?? []))
    );
  }
  getReviewsByCycle(cycleId: string): Observable<Review[]> {
    return this.https.get<any>(`${environment.apiUrl}/reviews?cycleId=${cycleId}`).pipe(
      map(res => Array.isArray(res) ? res : (res.reviews ?? []))
    );
  }

  getReviewsBySubject(subjectUserId: string): Observable<Review[]> {
    return this.https.get<any>(`${environment.apiUrl}/reviews?subjectUserId=${subjectUserId}`).pipe(
      map(res => Array.isArray(res) ? res : (res.reviews ?? []))
    );
  }
  createReview(review: Review): Observable<Review> {
    return this.https.post<Review>(`${environment.apiUrl}/reviews`, review);
  }

  // Accepts a partial review so callers persisting a single field (e.g. a job
  // pointer) can send only { reviewId, version, <field> }. The backend applies
  // non-null fields only, so a minimal body avoids re-sending status/answers —
  // critical because PUBLISHED is not a valid UpdateReviewRequest.status value
  // and echoing it back makes the PUT 400.
  updateReview(review: Partial<Review>): Observable<Review> {
    return this.https.put<Review>(`${environment.apiUrl}/reviews/${review.reviewId}`, review);
  }

  getTemplateById(templateId: string): Observable<import('../models/review.model').ReviewTemplate> {
    return this.https.get<import('../models/review.model').ReviewTemplate>(
      `${environment.apiUrl}/templates/${templateId}`
    );
  }

  // AI endpoints intentionally NOT on this service — see {@link AiReviewService}
  // for the typed equivalents: startAnalysis / pollAnalysis, startInsights /
  // pollInsights, startManagerGolden
  // / pollManagerGolden, startManagerQuestionSuggest / pollManagerQuestionSuggest.
  // Keep all AI calls co-located there so a future contract change touches one file.

  /**
   * Returns the server-authoritative manager-review submission gate for a
   * given (cycle, subject) pair. The shape mirrors backend ManagerGateStatus
   * — frontend uses `open` to enable/disable the submit button and `reasons`
   * to render a helpful tooltip when blocked.
   *
   * Replaces the previous `canSubmit` query-param hack which the backend
   * couldn't actually trust.
   */
  getManagerGate(cycleId: string, subjectUserId: string): Observable<ManagerGateStatus> {
    const params = new URLSearchParams({ cycleId, subjectUserId });
    return this.https.get<ManagerGateStatus>(
      `${environment.apiUrl}/reviews/manager-gate?${params.toString()}`);
  }
}

// ── Manager gate types ──────────────────────────────────────────────────────

export type ManagerGateReasonType =
  | 'self_missing'
  | 'self_incomplete'
  | 'peer360_unassigned'
  | 'peer360_incomplete';

export interface ManagerGateReason {
  type: ManagerGateReasonType;
  status?: string;
  missingCount?: number;
}

export interface ManagerGateStatus {
  open: boolean;
  cycleId: string;
  subjectUserId: string;
  selfReview: { status: string | null; reviewId: string | null };
  peer360: {
    required: boolean;
    totalAssigned: number;
    completedCount: number;
    incompleteAuthorUserIds: string[];
  };
  reasons: ManagerGateReason[];
}
