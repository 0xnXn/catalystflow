import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import { of } from 'rxjs';

import { AuthService } from '../../core/auth/auth.service';
import { EmployeeService } from '../../services/employees.services';
import { AiReviewService } from '../../services/ai-review.service';
import { ReviewFormComponent } from './review-form.component';

const PAYLOAD = '<img src=x onerror="alert(1)">';
const ESCAPED = '&lt;img src=x onerror=&quot;alert(1)&quot;&gt;';

/** Captures HTML written into the export iframe without touching the real DOM or printing. */
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

describe('ReviewFormComponent exportToPdf — XSS hardening', () => {
  let component: ReviewFormComponent;

  beforeEach(async () => {
    const employeeService = jasmine.createSpyObj<EmployeeService>('EmployeeService', [
      'getUserById', 'getCycles', 'getMyReviews', 'getTemplateById', 'createReview', 'updateReview', 'getUserPublic',
    ]);
    employeeService.getCycles.and.returnValue(of([]));
    employeeService.getMyReviews.and.returnValue(of([]));
    const aiReviewService = jasmine.createSpyObj<AiReviewService>('AiReviewService', [
      'startAnalysis', 'pollAnalysis', 'startInsights', 'getDisciplines',
    ]);

    await TestBed.configureTestingModule({
      imports: [ReviewFormComponent],
      providers: [
        { provide: EmployeeService, useValue: employeeService },
        { provide: AiReviewService, useValue: aiReviewService },
        { provide: AuthService, useValue: { getTokenPayload: () => ({ sub: 'user-1' }) } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ type: 'self' }), queryParamMap: convertToParamMap({ cycleId: 'cycle-1', milestoneId: 'milestone-1' }) } },
        },
        { provide: Router, useValue: jasmine.createSpyObj<Router>('Router', ['navigate']) },
      ],
    }).compileComponents();

    component = TestBed.createComponent(ReviewFormComponent).componentInstance;
  });

  it('escapes a malicious answer and sandboxes the print iframe', () => {
    component.questions = [{ questionId: 'q1', text: 'Tech', type: 'text' } as any];
    component.responses = { q1: PAYLOAD };
    component.cycle = { cycleTitle: 'FY Review' } as any;

    const cap = installCapture();
    component.exportToPdf();

    expect(cap.written).withContext('raw payload must not reach the document').not.toContain('<img src=x onerror');
    expect(cap.written).withContext('payload must be HTML-escaped').toContain(ESCAPED);
    expect(cap.iframe.sandbox).toContain('allow-same-origin');
    expect(cap.iframe.sandbox).not.toContain('allow-scripts');
    expect(cap.written).withContext('no inline script in the written document').not.toContain('<script');
  });
});
