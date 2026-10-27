import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { of } from 'rxjs';

import { ManagerReviewFormComponent } from './manager-review-form.component';
import { AuthService } from '../../core/auth/auth.service';
import { EmployeeService } from '../../services/employees.services';
import { AiReviewService } from '../../services/ai-review.service';
import { Peer360AssignmentService } from '../../services/peer360-assignment.service';

const PAYLOAD = '<img src=x onerror="alert(1)">';
const ESCAPED = '&lt;img src=x onerror=&quot;alert(1)&quot;&gt;';

function installCapture() {
  const state: { written: string; iframe: any } = { written: '', iframe: null };
  state.iframe = {
    id: '', style: {} as any, sandbox: '',
    contentWindow: {
      document: { write: (s: string) => { state.written += s; }, close: () => {} },
      focus: () => {}, print: () => {},
    },
    setAttribute(name: string, value: string) { if (name === 'sandbox') (this as any).sandbox = value; },
    remove: () => {},
  };
  const origCreate = document.createElement.bind(document);
  spyOn(document, 'getElementById').and.returnValue(null);
  spyOn(document, 'createElement').and.callFake(((t: string) => (t === 'iframe' ? state.iframe : origCreate(t))) as any);
  spyOn(document.body, 'appendChild').and.callFake(((n: any) => n) as any);
  return state;
}

describe('ManagerReviewFormComponent exportToPdf — XSS hardening', () => {
  let component: ManagerReviewFormComponent;

  beforeEach(() => {
    const employeeService = jasmine.createSpyObj<EmployeeService>('EmployeeService', [
      'getManagerGate', 'getCycles', 'getUserById', 'getReviewsBySubject', 'getMyReviews', 'getTemplateById',
    ]);
    const aiReviewService = jasmine.createSpyObj<AiReviewService>('AiReviewService', [
      'startInsights', 'pollInsights', 'getDisciplines', 'startManagerGolden', 'pollManagerGolden', 'startPeer360Digest', 'pollPeer360Digest', 'getLatestPeer360Digest',
    ]);
    const peer360Service = jasmine.createSpyObj<Peer360AssignmentService>('Peer360AssignmentService', ['list']);
    const authService = jasmine.createSpyObj<AuthService>('AuthService', ['getTokenPayload']);
    authService.getTokenPayload.and.returnValue({ sub: 'mgr-1', 'cognito:groups': ['MANAGER'] } as any);

    TestBed.configureTestingModule({
      imports: [ManagerReviewFormComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: EmployeeService, useValue: employeeService },
        { provide: AiReviewService, useValue: aiReviewService },
        { provide: Peer360AssignmentService, useValue: peer360Service },
        { provide: AuthService, useValue: authService },
        { provide: Router, useValue: jasmine.createSpyObj<Router>('Router', ['navigate']) },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: { get: () => null } } } },
      ],
    });

    component = TestBed.createComponent(ManagerReviewFormComponent).componentInstance;
  });

  it('escapes a malicious employee name + answer and sandboxes the print iframe', () => {
    component.groupedQuestions = [{ category: 'General', questions: [{ questionId: 'q1', text: 'Tech', type: 'text' }] }] as any;
    component.responses = { q1: 'plain answer' };
    component.employeeName = PAYLOAD;            // raw header sink (manager-review-form.ts:1446)
    component.cycle = { cycleTitle: 'FY26' } as any;
    component.review = { status: 'COMPLETED' } as any;
    (component as any).parsedGoldenReview = null;

    const cap = installCapture();
    component.exportToPdf();

    expect(cap.written).not.toContain('<img src=x onerror');
    expect(cap.written).toContain(ESCAPED);
    expect(cap.iframe.sandbox).toContain('allow-same-origin');
    expect(cap.iframe.sandbox).not.toContain('allow-scripts');
    expect(cap.written).not.toContain('<script');
  });
});
