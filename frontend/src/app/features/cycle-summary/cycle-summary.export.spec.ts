import { HttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';

import { EmployeeService } from '../../services/employees.services';
import { NotificationService } from '../../services/notification.service';
import { CycleSummaryComponent } from './cycle-summary.component';

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

describe('CycleSummaryComponent exportModal — XSS hardening', () => {
  let component: CycleSummaryComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CycleSummaryComponent],
      providers: [
        { provide: EmployeeService, useValue: jasmine.createSpyObj<EmployeeService>('EmployeeService', ['getEmployees', 'getCycles', 'getReviewsByCycle']) },
        { provide: NotificationService, useValue: jasmine.createSpyObj<NotificationService>('NotificationService', ['sendManualReminder']) },
        { provide: HttpClient, useValue: jasmine.createSpyObj<HttpClient>('HttpClient', ['post', 'get']) },
        { provide: Router, useValue: jasmine.createSpyObj<Router>('Router', ['navigate']) },
      ],
    }).compileComponents();

    component = TestBed.createComponent(CycleSummaryComponent).componentInstance;
  });

  it('escapes a malicious answer and sandboxes the print iframe', () => {
    component.modalType = 'self';
    component.modalTitle = 'Self Review';
    component.modalSubtitle = 'Jane Smith';
    component.modalSubmittedDate = '';
    component.modalQuestions = [{ questionId: 'q1', text: 'Tech' } as any];
    component.modalAnswers = { q1: PAYLOAD };

    const cap = installCapture();
    component.exportModal();

    expect(cap.written).not.toContain('<img src=x onerror');
    expect(cap.written).toContain(ESCAPED);
    expect(cap.iframe.sandbox).toContain('allow-same-origin');
    expect(cap.iframe.sandbox).not.toContain('allow-scripts');
    expect(cap.written).not.toContain('<script');
  });
});
