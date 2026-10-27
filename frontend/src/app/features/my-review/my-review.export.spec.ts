import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';

import { AuthService } from '../../core/auth/auth.service';
import { EmployeeService } from '../../services/employees.services';
import { MyReviewComponent } from './my-review.component';

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

describe('MyReviewComponent exportHistoryToPdf — XSS hardening', () => {
  let component: MyReviewComponent;

  beforeEach(async () => {
    const employeeService = jasmine.createSpyObj<EmployeeService>('EmployeeService', [
      'getCycles', 'getMyReviews', 'getMySubjectReviews', 'getUserPublic',
    ]);
    employeeService.getCycles.and.returnValue(of([]));
    employeeService.getMyReviews.and.returnValue(of([]));
    employeeService.getMySubjectReviews.and.returnValue(of([]));
    employeeService.getUserPublic.and.returnValue(of({ userId: 'u', firstName: 'U', lastName: 'X', role: 'EMPLOYEE' } as any));

    await TestBed.configureTestingModule({
      imports: [MyReviewComponent],
      providers: [
        { provide: EmployeeService, useValue: employeeService },
        { provide: AuthService, useValue: { getTokenPayload: () => ({ sub: 'user-1' }), isAdmin: () => false, getUserEmployeeType: () => 'EMPLOYEE' } },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({}), queryParamMap: convertToParamMap({}) } } },
        { provide: Router, useValue: jasmine.createSpyObj<Router>('Router', ['navigate']) },
      ],
    }).compileComponents();

    component = TestBed.createComponent(MyReviewComponent).componentInstance;
  });

  it('escapes a malicious (cross-author) answer and sandboxes the print iframe', () => {
    component.selectedHistoryType = 'self';
    (component as any).selectedModalReview = { answers: [{ questionId: 'q1', response: PAYLOAD }] };
    const item: any = { review: { answers: [] }, cycle: { cycleTitle: 'FY26' }, selfReviewTime: '2026-01-01' };

    const cap = installCapture();
    component.exportHistoryToPdf(item);

    expect(cap.written).not.toContain('<img src=x onerror');
    expect(cap.written).toContain(ESCAPED);
    expect(cap.iframe.sandbox).toContain('allow-same-origin');
    expect(cap.iframe.sandbox).not.toContain('allow-scripts');
    expect(cap.written).not.toContain('<script');
  });
});
