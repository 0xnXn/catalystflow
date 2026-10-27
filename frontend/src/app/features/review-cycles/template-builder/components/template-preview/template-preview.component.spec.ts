import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ReactiveFormsModule } from '@angular/forms';
import { FormlyModule } from '@ngx-formly/core';
import { By } from '@angular/platform-browser';

import { TemplatePreviewComponent } from './template-preview.component';
import { TemplateQuestion } from '../../models/template.model';

describe('TemplatePreviewComponent', () => {
  let fixture: ComponentFixture<TemplatePreviewComponent>;
  let component: TemplatePreviewComponent;

  function question(overrides: Partial<TemplateQuestion> = {}): TemplateQuestion {
    return {
      questionId: 'q-1',
      text: 'Describe your impact',
      type: 'TEXT',
      options: [],
      required: true,
      order: 1,
      ...overrides,
    };
  }

  function buildComponent(questions: TemplateQuestion[] = []) {
    TestBed.configureTestingModule({
      imports: [
        ReactiveFormsModule,
        FormlyModule.forRoot({
          types: [
            { name: 'textarea', component: DummyFormlyFieldComponent },
            { name: 'rating', component: DummyFormlyFieldComponent },
            { name: 'multicheckbox', component: DummyFormlyFieldComponent },
            { name: 'radio', component: DummyFormlyFieldComponent },
            { name: 'select', component: DummyFormlyFieldComponent },
          ],
        }),
        TemplatePreviewComponent,
      ],
    });

    fixture = TestBed.createComponent(TemplatePreviewComponent);
    component = fixture.componentInstance;
    component.questions = questions;
    fixture.detectChanges();
    return { fixture, component };
  }

  // Preview fields must be ordered by the persisted order field, not array position.
  it('maps questions into ordered Formly fields', () => {
    buildComponent([
      question({ questionId: 'q-2', order: 2, type: 'RATING', required: false }),
      question({ questionId: 'q-1', order: 1, type: 'TEXT', required: true }),
    ]);

    expect(component.fields.map(f => f.key)).toEqual(['q-1', 'q-2']);
    expect(component.fields[0].type).toBe('textarea');
    expect(component.fields[0].props?.['required']).toBeTrue();
    expect(component.fields[0].props?.['rows']).toBe(3);
    expect(component.fields[1].type).toBe('rating');
    expect(component.fields[1].props?.['required']).toBeFalse();
  });

  // Option-based questions should expose label/value pairs that Formly can render.
  it('maps checkbox, radio, and multi-select options', () => {
    buildComponent([
      question({ questionId: 'check', type: 'CHECKBOX', options: ['A', 'B'], order: 1 }),
      question({ questionId: 'single', type: 'SINGLE_SELECT', options: ['Yes', 'No'], order: 2 }),
      question({ questionId: 'multi', type: 'MULTI_SELECT', options: undefined as any, order: 3 }),
    ]);

    expect(component.fields[0].type).toBe('multicheckbox');
    expect(component.fields[0].props?.['options']).toEqual([
      { label: 'A', value: 'A' },
      { label: 'B', value: 'B' },
    ]);
    expect(component.fields[1].type).toBe('radio');
    expect(component.fields[1].props?.['options']).toEqual([
      { label: 'Yes', value: 'Yes' },
      { label: 'No', value: 'No' },
    ]);
    expect(component.fields[2].type).toBe('select');
    expect(component.fields[2].props?.['multiple']).toBeTrue();
    expect(component.fields[2].props?.['options']).toEqual([]);
  });

  it('falls back to textarea for unknown question types', () => {
    buildComponent([
      question({ type: 'UNKNOWN' as any, required: false }),
    ]);

    expect(component.fields[0].type).toBe('textarea');
    expect(component.fields[0].props?.['required']).toBeFalse();
  });

  // Empty previews should block saving because there is no usable review form yet.
  it('renders empty state and disables save when there are no questions', () => {
    buildComponent();

    expect(fixture.nativeElement.textContent).toContain('No questions to preview');
    expect(fixture.nativeElement.textContent).toContain('0 questions');
    expect(fixture.debugElement.query(By.css('.prev-btn-save')).nativeElement.disabled).toBeTrue();
  });

  it('renders question labels and emits footer actions', () => {
    buildComponent([
      question({ text: '', required: true }),
    ]);
    spyOn(component.back, 'emit');
    spyOn(component.saveAndAttach, 'emit');

    fixture.debugElement.query(By.css('.prev-btn-back')).triggerEventHandler('click');
    fixture.debugElement.query(By.css('.prev-btn-save')).triggerEventHandler('click');

    expect(fixture.nativeElement.textContent).toContain('1 question');
    expect(fixture.nativeElement.textContent).toContain('(Untitled question)');
    expect(fixture.nativeElement.textContent).toContain('*');
    expect(component.back.emit).toHaveBeenCalled();
    expect(component.saveAndAttach.emit).toHaveBeenCalled();
  });
});

import { Component } from '@angular/core';
import { FieldType } from '@ngx-formly/core';

@Component({
  selector: 'app-dummy-formly-field',
  standalone: true,
  template: '',
})
class DummyFormlyFieldComponent extends FieldType {}
