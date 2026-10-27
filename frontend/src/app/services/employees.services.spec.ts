import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting
} from '@angular/common/http/testing';

import { environment } from '../../environment/environment';
import { User } from '../models/employee.model';
import { Review } from '../models/review.model';
import { EmployeeService, ManagerGateStatus, ReviewCycle } from './employees.services';

describe('EmployeeService', () => {
  let service: EmployeeService;
  let httpMock: HttpTestingController;
  const baseUrl = environment.apiUrl;

  const activeUser: User = {
    userId: 'usr001',
    email: 'john@example.com',
    firstName: 'John',
    lastName: 'Smith',
    role: 'EMPLOYEE',
    position: 'Developer',
    status: 'Active',
    reportingTo: null
  };

  const review: Review = {
    reviewId: 'review-1',
    cycleId: 'cycle-1',
    userId: 'usr001',
    subjectUserId: 'usr001',
    authorUserId: 'usr001',
    reviewType: 'SELF',
    status: 'NOT_STARTED',
    answers: [],
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        EmployeeService,
        provideHttpClient(),
        provideHttpClientTesting()
      ]
    });

    service = TestBed.inject(EmployeeService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    // Ensures every service call is asserted and flushed.
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('should get users with GET /users', () => {
    const mockUsers: User[] = [
      activeUser,
      {
        userId: 'usr002',
        email: 'jane@example.com',
        firstName: 'Jane',
        lastName: 'Lee',
        role: 'EMPLOYEE',
        position: 'Designer',
        status: 'Archived',
        reportingTo: null
      }
    ];

    service.getEmployees().subscribe((users) => {
      expect(users).toEqual(mockUsers);
      expect(users.length).toBe(2);
    });

    const req = httpMock.expectOne(`${baseUrl}/users`);
    expect(req.request.method).toBe('GET');
    req.flush({ users: mockUsers });
  });

  it('should cache getEmployees until clearCache is called', () => {
    let first: User[] | undefined;
    let second: User[] | undefined;

    service.getEmployees().subscribe(users => first = users);
    service.getEmployees().subscribe(users => second = users);

    const req = httpMock.expectOne(`${baseUrl}/users`);
    // shareReplay keeps repeated subscribers from issuing duplicate /users calls.
    req.flush({ users: [activeUser] });

    expect(first).toEqual([activeUser]);
    expect(second).toEqual([activeUser]);

    service.clearCache();
    service.getEmployees().subscribe();
    httpMock.expectOne(`${baseUrl}/users`).flush({ users: [] });
  });

  it('should preload employees by subscribing to the cached users request', () => {
    service.preload();

    const req = httpMock.expectOne(`${baseUrl}/users`);
    expect(req.request.method).toBe('GET');
    req.flush({ users: [activeUser] });
  });

  it('should add user with POST /users and clear the employee cache', () => {
    service.getEmployees().subscribe();
    httpMock.expectOne(`${baseUrl}/users`).flush({ users: [] });

    service.addEmployee(activeUser).subscribe((user) => {
      expect(user).toEqual(activeUser);
      expect(user.email).toBe('john@example.com');
    });

    const req = httpMock.expectOne(`${baseUrl}/users`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(activeUser);
    req.flush(activeUser);

    service.getEmployees().subscribe();
    httpMock.expectOne(`${baseUrl}/users`).flush({ users: [activeUser] });
  });

  it('should update user with PUT /users/:id and clear the employee cache', () => {
    const updatedUser: User = {
      ...activeUser,
      id: 3,
      role: 'MANAGER',
      position: 'Senior QA Engineer',
    };

    service.getEmployees().subscribe();
    httpMock.expectOne(`${baseUrl}/users`).flush({ users: [activeUser] });

    service.updateEmployee(updatedUser).subscribe((user) => {
      expect(user).toEqual(updatedUser);
      expect(user.position).toBe('Senior QA Engineer');
    });

    const req = httpMock.expectOne(`${baseUrl}/users/${updatedUser.userId}`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(updatedUser);
    req.flush(updatedUser);

    service.getEmployees().subscribe();
    httpMock.expectOne(`${baseUrl}/users`).flush({ users: [updatedUser] });
  });

  it('should deactivate user with PUT /users/:id and status: Archived', () => {
    const id = 'usr001';
    const archived: User = { ...activeUser, status: 'Archived' };

    service.deleteEmployee(id).subscribe((user) => {
      expect(user.status).toBe('Archived');
    });

    const req = httpMock.expectOne(`${baseUrl}/users/${id}`);
    expect(req.request.method).toBe('PUT');
    // Backend has no DELETE /users endpoint, so deletion is a status flip.
    expect(req.request.body).toEqual({ status: 'Archived' });
    req.flush(archived);
  });

  it('should get one user by id', () => {
    service.getUserById('usr001').subscribe(user => expect(user).toEqual(activeUser));

    const req = httpMock.expectOne(`${baseUrl}/users/usr001`);
    expect(req.request.method).toBe('GET');
    req.flush(activeUser);
  });

  it('should map team and all-user response shapes safely', () => {
    let team: User[] | undefined;
    let wrappedUsers: User[] | undefined;
    let arrayUsers: User[] | undefined;

    service.getMyTeam('mgr-1').subscribe(users => team = users);
    httpMock.expectOne(`${baseUrl}/users/mgr-1/team`).flush({});
    expect(team).toEqual([]);

    service.getAllUsers().subscribe(users => wrappedUsers = users);
    httpMock.expectOne(`${baseUrl}/users`).flush({ users: [activeUser] });
    expect(wrappedUsers).toEqual([activeUser]);

    service.getAllUsers().subscribe(users => arrayUsers = users);
    httpMock.expectOne(`${baseUrl}/users`).flush([activeUser]);
    expect(arrayUsers).toEqual([activeUser]);

    service.getAllUsers().subscribe(users => expect(users).toEqual([]));
    httpMock.expectOne(`${baseUrl}/users`).flush({});
  });

  it('should get team members by reportingTo', () => {
    service.getTeamMembers('mgr-1').subscribe(users => expect(users).toEqual([activeUser]));

    const req = httpMock.expectOne(`${baseUrl}/users?reportingTo=mgr-1`);
    expect(req.request.method).toBe('GET');
    req.flush({ users: [activeUser] });

    service.getTeamMembers('mgr-1').subscribe(users => expect(users).toEqual([activeUser]));
    httpMock.expectOne(`${baseUrl}/users?reportingTo=mgr-1`).flush([activeUser]);

    service.getTeamMembers('mgr-1').subscribe(users => expect(users).toEqual([]));
    httpMock.expectOne(`${baseUrl}/users?reportingTo=mgr-1`).flush({});
  });

  it('should get cycles with optional status and map wrapped or array payloads', () => {
    const cycle: ReviewCycle = {
      cycleId: 'cycle-1',
      cycleTitle: 'FY26',
      startDate: '2026-01-01',
      dueDate: '2026-03-31',
      milestones: [],
      status: 'ACTIVE',
    };

    service.getCycles('ACTIVE').subscribe(cycles => expect(cycles).toEqual([cycle]));
    httpMock.expectOne(`${baseUrl}/cycles?status=ACTIVE`).flush({ cycles: [cycle] });

    service.getCycles().subscribe(cycles => expect(cycles).toEqual([cycle]));
    httpMock.expectOne(`${baseUrl}/cycles`).flush([cycle]);

    service.getCycles().subscribe(cycles => expect(cycles).toEqual([]));
    httpMock.expectOne(`${baseUrl}/cycles`).flush({});
  });

  it('should map review list endpoints from wrapped or array payloads', () => {
    service.getMyReviews('ignored-by-endpoint').subscribe(reviews => expect(reviews).toEqual([review]));
    // getMyReviews intentionally calls assigned-to-me; auth context comes from backend token.
    httpMock.expectOne(`${baseUrl}/reviews/assigned-to-me`).flush({ reviews: [review] });

    service.getMyReviews('ignored-by-endpoint').subscribe(reviews => expect(reviews).toEqual([review]));
    httpMock.expectOne(`${baseUrl}/reviews/assigned-to-me`).flush([review]);

    service.getMyReviews('ignored-by-endpoint').subscribe(reviews => expect(reviews).toEqual([]));
    httpMock.expectOne(`${baseUrl}/reviews/assigned-to-me`).flush({});

    service.getMySubjectReviews().subscribe(reviews => expect(reviews).toEqual([review]));
    httpMock.expectOne(`${baseUrl}/reviews/my-review`).flush([review]);

    service.getMySubjectReviews().subscribe(reviews => expect(reviews).toEqual([]));
    httpMock.expectOne(`${baseUrl}/reviews/my-review`).flush({});

    service.getReviewsByCycle('cycle-1').subscribe(reviews => expect(reviews).toEqual([review]));
    httpMock.expectOne(`${baseUrl}/reviews?cycleId=cycle-1`).flush({ reviews: [review] });

    service.getReviewsByCycle('cycle-1').subscribe(reviews => expect(reviews).toEqual([review]));
    httpMock.expectOne(`${baseUrl}/reviews?cycleId=cycle-1`).flush([review]);

    service.getReviewsByCycle('cycle-1').subscribe(reviews => expect(reviews).toEqual([]));
    httpMock.expectOne(`${baseUrl}/reviews?cycleId=cycle-1`).flush({});

    service.getReviewsBySubject('usr001').subscribe(reviews => expect(reviews).toEqual([review]));
    httpMock.expectOne(`${baseUrl}/reviews?subjectUserId=usr001`).flush([review]);

    service.getReviewsBySubject('usr001').subscribe(reviews => expect(reviews).toEqual([]));
    httpMock.expectOne(`${baseUrl}/reviews?subjectUserId=usr001`).flush({});
  });

  it('should create and update reviews with the review payload', () => {
    service.createReview(review).subscribe(result => expect(result).toEqual(review));
    const createReq = httpMock.expectOne(`${baseUrl}/reviews`);
    expect(createReq.request.method).toBe('POST');
    expect(createReq.request.body).toEqual(review);
    createReq.flush(review);

    service.updateReview(review).subscribe(result => expect(result).toEqual(review));
    const updateReq = httpMock.expectOne(`${baseUrl}/reviews/review-1`);
    expect(updateReq.request.method).toBe('PUT');
    expect(updateReq.request.body).toEqual(review);
    updateReq.flush(review);
  });

  it('should get templates by id from the templates endpoint', () => {
    const template = { templateId: 'tpl-1', templateName: 'Self', questions: [] };

    service.getTemplateById('tpl-1').subscribe(result => expect(result).toEqual(template));

    const req = httpMock.expectOne(`${baseUrl}/templates/tpl-1`);
    expect(req.request.method).toBe('GET');
    req.flush(template);
  });

  it('should get the server-authoritative manager gate with encoded query params', () => {
    const gate: ManagerGateStatus = {
      open: false,
      cycleId: 'cycle 1',
      subjectUserId: 'subject/1',
      selfReview: { status: 'COMPLETED', reviewId: 'self-1' },
      peer360: {
        required: true,
        totalAssigned: 2,
        completedCount: 1,
        incompleteAuthorUserIds: ['peer-2'],
      },
      reasons: [{ type: 'peer360_incomplete' }],
    };

    service.getManagerGate('cycle 1', 'subject/1').subscribe(result => expect(result).toEqual(gate));

    const req = httpMock.expectOne(
      `${baseUrl}/reviews/manager-gate?cycleId=cycle+1&subjectUserId=subject%2F1`
    );
    expect(req.request.method).toBe('GET');
    // This endpoint replaces query-param trust in the UI with backend authority.
    req.flush(gate);
  });

  // getTeams() removed: team structure is derived locally from /users data.
});
