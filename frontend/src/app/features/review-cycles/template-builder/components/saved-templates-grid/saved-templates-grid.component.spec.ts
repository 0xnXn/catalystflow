import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { of, throwError } from 'rxjs';

import { SavedTemplatesGridComponent } from './saved-templates-grid.component';
import { ReviewTemplate } from '../../models/template.model';
import { TemplateService } from '../../services/template.service';

describe('SavedTemplatesGridComponent', () => {
  let fixture: ComponentFixture<SavedTemplatesGridComponent>;
  let component: SavedTemplatesGridComponent;
  let templateService: jasmine.SpyObj<TemplateService>;

  function template(overrides: Partial<ReviewTemplate> = {}): ReviewTemplate {
    return {
      templateId: 'tpl-1',
      templateName: 'Self Review Template',
      reviewType: 'SELF',
      description: 'Reusable self review questions.',
      questions: [
        {
          questionId: 'q-1',
          text: 'Describe your impact',
          type: 'TEXT',
          options: [],
          required: true,
          order: 1,
        },
      ],
      createdAt: '2026-01-01T00:00:00.000Z',
      updatedAt: '2026-01-02T00:00:00.000Z',
      lastUsedAt: '2026-05-01T00:00:00.000Z',
      usageCount: 2,
      ...overrides,
    };
  }

  function buildComponent(templates: ReviewTemplate[] = [template()]) {
    templateService = jasmine.createSpyObj<TemplateService>('TemplateService', ['getAll']);
    templateService.getAll.and.returnValue(of(templates));

    TestBed.configureTestingModule({
      imports: [SavedTemplatesGridComponent],
      providers: [{ provide: TemplateService, useValue: templateService }],
    });

    fixture = TestBed.createComponent(SavedTemplatesGridComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    return { fixture, component, templateService };
  }

  // Loading templates is the entry path for browsing saved review forms.
  it('loads templates on init and renders the card grid', () => {
    buildComponent([
      template(),
      template({
        templateId: 'tpl-2',
        templateName: 'Manager Template',
        reviewType: 'MANAGER',
        questions: [
          {
            questionId: 'q-2',
            text: 'Rate performance',
            type: 'RATING',
            options: [],
            required: false,
            order: 1,
          },
        ],
        usageCount: 0,
      }),
    ]);

    expect(templateService.getAll).toHaveBeenCalled();
    expect(component.loading()).toBeFalse();
    expect(component.templates().length).toBe(2);
    expect(fixture.debugElement.queryAll(By.css('.stg-card')).length).toBe(2);
    expect(fixture.nativeElement.textContent).toContain('Self Review Template');
    expect(fixture.nativeElement.textContent).toContain('Used 2');
  });

  // Filters decide which saved templates can be attached to a milestone.
  it('filters templates by review type and shows filtered empty copy', () => {
    buildComponent([template({ reviewType: 'SELF' })]);

    component.setFilter('SELF');
    expect(component.filtered().length).toBe(1);

    component.setFilter('PEER_360');
    fixture.detectChanges();

    expect(component.filtered()).toEqual([]);
    expect(fixture.nativeElement.textContent).toContain('No templates found for this review type.');
  });

  // Preview expansion lets users inspect questions before selecting a template.
  it('toggles inline preview and emits the selected template', () => {
    const saved = template();
    buildComponent([saved]);
    spyOn(component.templateSelected, 'emit');

    component.toggleExpand('tpl-1');
    fixture.detectChanges();

    expect(component.expandedId()).toBe('tpl-1');
    expect(fixture.nativeElement.textContent).toContain('Describe your impact');
    expect(fixture.nativeElement.textContent).toContain('Long Text');

    component.toggleExpand('tpl-1');
    expect(component.expandedId()).toBeNull();

    component.select(saved);
    expect(component.templateSelected.emit).toHaveBeenCalledOnceWith(saved);
  });

  it('emits back from the header button', () => {
    buildComponent();
    spyOn(component.back, 'emit');

    fixture.debugElement.query(By.css('.stg-btn-back')).triggerEventHandler('click');

    expect(component.back.emit).toHaveBeenCalled();
  });

  // Service errors should leave the drawer usable and show a clear message.
  it('shows an error state when templates fail to load', () => {
    templateService = jasmine.createSpyObj<TemplateService>('TemplateService', ['getAll']);
    templateService.getAll.and.returnValue(throwError(() => new Error('network')));

    TestBed.configureTestingModule({
      imports: [SavedTemplatesGridComponent],
      providers: [{ provide: TemplateService, useValue: templateService }],
    });

    fixture = TestBed.createComponent(SavedTemplatesGridComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();

    expect(component.loading()).toBeFalse();
    expect(component.error()).toBe('Failed to load templates.');
    expect(fixture.nativeElement.textContent).toContain('Failed to load templates.');
  });

  // Formatting helpers keep metadata readable even when optional backend fields are absent.
  it('maps badge classes and formats optional dates', () => {
    buildComponent();

    expect(component.reviewTypeBadgeClass('SELF')).toBe('badge-self');
    expect(component.reviewTypeBadgeClass('PEER_360')).toBe('badge-360');
    expect(component.reviewTypeBadgeClass('MANAGER')).toBe('badge-manager');
    expect(component.reviewTypeBadgeClass('UNKNOWN' as any)).toBe('');
    expect(component.formatDate('2026-05-01T00:00:00.000Z')).toContain('2026');
    expect(component.formatDate()).toBeTruthy();
  });
});
