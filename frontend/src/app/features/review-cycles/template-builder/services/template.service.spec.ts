import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { environment } from '../../../../../environment/environment';
import {
  CreateReviewTemplateRequest,
  ReviewTemplate,
} from '../models/template.model';
import { TemplateService } from './template.service';

describe('TemplateService', () => {
  let service: TemplateService;
  let httpMock: HttpTestingController;
  const baseUrl = `${environment.apiUrl}/templates`;

  // Shared fixture keeps the tests focused on HTTP behavior rather than setup noise.
  const template: ReviewTemplate = {
    templateId: 'tpl-1',
    templateName: 'Self Review Template',
    reviewType: 'SELF',
    description: 'Quarterly self review',
    questions: [
      {
        questionId: 'q1',
        text: 'What did you deliver?',
        type: 'TEXT',
        options: [],
        required: true,
        order: 1,
      },
    ],
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-02T00:00:00Z',
    usageCount: 2,
  };

  // Representative create payload: one real question is enough to verify body passthrough.
  const createRequest: CreateReviewTemplateRequest = {
    templateName: 'Manager Review Template',
    reviewType: 'MANAGER',
    description: 'Manager review',
    questions: [
      {
        questionId: 'q1',
        text: 'Performance summary',
        type: 'TEXT',
        options: [],
        required: true,
        order: 1,
      },
    ],
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        TemplateService,
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });

    service = TestBed.inject(TemplateService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    // Fails the spec if a request was not asserted/flushed.
    httpMock.verify();
  });

  it('loads all templates without a reviewType query param', () => {
    let result: ReviewTemplate[] | undefined;

    service.getAll().subscribe(templates => result = templates);

    const req = httpMock.expectOne(baseUrl);
    // No filter should mean no reviewType param at all, not an empty param.
    expect(req.request.method).toBe('GET');
    expect(req.request.params.has('reviewType')).toBeFalse();
    req.flush({ templates: [template], count: 1 });

    expect(result).toEqual([template]);
  });

  it('filters templates by reviewType when provided', () => {
    service.getAll('PEER_360').subscribe();

    // Use a predicate so both URL and query params are asserted together.
    const req = httpMock.expectOne(request =>
      request.url === baseUrl &&
      request.params.get('reviewType') === 'PEER_360'
    );
    expect(req.request.method).toBe('GET');
    req.flush({ templates: [], count: 0 });
  });

  it('maps missing templates array to an empty list', () => {
    let result: ReviewTemplate[] | undefined;

    service.getAll().subscribe(templates => result = templates);

    const req = httpMock.expectOne(baseUrl);
    // Backend contract should include templates, but UI stays safe if it is absent.
    req.flush({ count: 0 });

    expect(result).toEqual([]);
  });

  it('gets a template by id', () => {
    let result: ReviewTemplate | undefined;

    service.getById('tpl-1').subscribe(value => result = value);

    const req = httpMock.expectOne(`${baseUrl}/tpl-1`);
    expect(req.request.method).toBe('GET');
    req.flush(template);

    expect(result).toEqual(template);
  });

  it('creates a template with the request body', () => {
    let result: ReviewTemplate | undefined;

    service.create(createRequest).subscribe(value => result = value);

    const req = httpMock.expectOne(baseUrl);
    // Create/update should not reshape the request; the service passes typed payloads through.
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(createRequest);
    req.flush({ ...template, ...createRequest, templateId: 'tpl-created' });

    expect(result?.templateId).toBe('tpl-created');
    expect(result?.reviewType).toBe('MANAGER');
  });

  it('updates a template by id with a partial body', () => {
    let result: ReviewTemplate | undefined;
    const patch: Partial<CreateReviewTemplateRequest> = {
      templateName: 'Updated Template',
      description: 'Updated description',
    };

    service.update('tpl-1', patch).subscribe(value => result = value);

    const req = httpMock.expectOne(`${baseUrl}/tpl-1`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(patch);
    req.flush({ ...template, ...patch });

    expect(result?.templateName).toBe('Updated Template');
    expect(result?.description).toBe('Updated description');
  });

  it('deletes a template by id', () => {
    let completed = false;

    service.delete('tpl-1').subscribe(() => completed = true);

    const req = httpMock.expectOne(`${baseUrl}/tpl-1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);

    expect(completed).toBeTrue();
  });

  it('checks name availability without excludeId', () => {
    let result: boolean | undefined;

    service.checkNameAvailable('Quarterly Review').subscribe(value => result = value);

    // New-template validation only sends the candidate name.
    const req = httpMock.expectOne(request =>
      request.url === `${baseUrl}/check-name` &&
      request.params.get('name') === 'Quarterly Review' &&
      !request.params.has('excludeId')
    );
    expect(req.request.method).toBe('GET');
    req.flush({ available: true });

    expect(result).toBeTrue();
  });

  it('checks name availability with excludeId when editing', () => {
    let result: boolean | undefined;

    service.checkNameAvailable('Quarterly Review', 'tpl-1').subscribe(value => result = value);

    // Edit validation includes excludeId so the current template name does not collide with itself.
    const req = httpMock.expectOne(request =>
      request.url === `${baseUrl}/check-name` &&
      request.params.get('name') === 'Quarterly Review' &&
      request.params.get('excludeId') === 'tpl-1'
    );
    expect(req.request.method).toBe('GET');
    req.flush({ available: false });

    expect(result).toBeFalse();
  });
});
