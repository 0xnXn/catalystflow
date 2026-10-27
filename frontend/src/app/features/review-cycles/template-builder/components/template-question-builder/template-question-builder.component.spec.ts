import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import * as XLSX from 'xlsx';

import { TemplateQuestionBuilderComponent } from './template-question-builder.component';
import { TemplateQuestion } from '../../models/template.model';

describe('TemplateQuestionBuilderComponent', () => {
  let fixture: ComponentFixture<TemplateQuestionBuilderComponent>;
  let component: TemplateQuestionBuilderComponent;

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

  function buildComponent(initialQuestions: TemplateQuestion[] = []) {
    TestBed.configureTestingModule({
      imports: [TemplateQuestionBuilderComponent],
    });

    fixture = TestBed.createComponent(TemplateQuestionBuilderComponent);
    component = fixture.componentInstance;
    component.initialQuestions = initialQuestions;
    fixture.detectChanges();
    return { fixture, component };
  }

  // Initial state starts from the provided array but is owned by the builder
  // signal, so add/remove operations do not replace the parent collection.
  it('loads initial questions into local signal state', () => {
    const initial = [question({ questionId: 'q-1' })];

    buildComponent(initial);

    expect(component.questions().length).toBe(1);
    expect(component.questions()).not.toBe(initial);
    expect(component.questions()[0].questionId).toBe('q-1');
  });

  it('starts empty when no initial questions are provided', () => {
    buildComponent();

    expect(component.questions()).toEqual([]);
    expect(fixture.nativeElement.textContent).toContain('No questions yet');
  });

  // Add/remove/clear operations must keep question order stable for snapshots.
  it('adds questions with generated options only for option-based types', () => {
    buildComponent();
    spyOn(component.questionsChange, 'emit');

    component.addQuestion('TEXT');
    component.addQuestion('CHECKBOX');

    expect(component.questions().map(q => q.order)).toEqual([1, 2]);
    expect(component.questions()[0].options).toEqual([]);
    expect(component.questions()[1].options).toEqual(['Option 1', 'Option 2']);
    expect(component.questionsChange.emit).toHaveBeenCalledTimes(2);
  });

  it('removes a question and renumbers the remaining questions', () => {
    buildComponent([
      question({ questionId: 'q-1', order: 1 }),
      question({ questionId: 'q-2', order: 2 }),
      question({ questionId: 'q-3', order: 3 }),
    ]);
    spyOn(component.questionsChange, 'emit');

    component.removeQuestion('q-2');

    expect(component.questions().map(q => q.questionId)).toEqual(['q-1', 'q-3']);
    expect(component.questions().map(q => q.order)).toEqual([1, 2]);
    expect(component.questionsChange.emit).toHaveBeenCalledOnceWith(component.questions());
  });

  it('clears all questions only after confirmation', () => {
    buildComponent([question()]);
    spyOn(component.questionsChange, 'emit');
    spyOn(window, 'confirm').and.returnValues(false, true);

    component.clearAll();
    expect(component.questions().length).toBe(1);
    expect(component.questionsChange.emit).not.toHaveBeenCalled();

    component.clearAll();
    expect(component.questions()).toEqual([]);
    expect(component.questionsChange.emit).toHaveBeenCalledOnceWith([]);
  });

  // Type changes are business-sensitive because they add/remove answer options.
  it('changes type to an option-based type and creates default options when missing', () => {
    const base = question({ questionId: 'q-1', options: [] });
    buildComponent([base]);
    spyOn(component.questionsChange, 'emit');

    component.onTypeChange(base, 'SINGLE_SELECT');

    expect(component.questions()[0]).toEqual(jasmine.objectContaining({
      type: 'SINGLE_SELECT',
      options: ['Option 1', 'Option 2'],
    }));
    expect(component.questionsChange.emit).toHaveBeenCalled();
  });

  it('preserves existing options when switching between option-based types', () => {
    const base = question({ questionId: 'q-1', type: 'CHECKBOX', options: ['Yes', 'No'] });
    buildComponent([base]);

    component.onTypeChange(base, 'MULTI_SELECT');

    expect(component.questions()[0].type).toBe('MULTI_SELECT');
    expect(component.questions()[0].options).toEqual(['Yes', 'No']);
  });

  it('clears options when changing to a non-option type', () => {
    const base = question({ questionId: 'q-1', type: 'CHECKBOX', options: ['A', 'B'] });
    buildComponent([base]);

    component.onTypeChange(base, 'RATING');

    expect(component.questions()[0].type).toBe('RATING');
    expect(component.questions()[0].options).toEqual([]);
  });

  // Option editing is the main interactive path for checkbox/select questions.
  it('adds, edits, and removes options', () => {
    const base = question({ questionId: 'q-1', type: 'CHECKBOX', options: ['A', 'B'] });
    buildComponent([base]);
    spyOn(component.questionsChange, 'emit');

    component.addOption(base);
    expect(component.questions()[0].options).toEqual(['A', 'B', 'Option 3']);

    component.onOptionChange(component.questions()[0], 1, 'Updated');
    expect(component.questions()[0].options).toEqual(['A', 'Updated', 'Option 3']);

    component.removeOption(component.questions()[0], 0);
    expect(component.questions()[0].options).toEqual(['Updated', 'Option 3']);
    expect(component.questionsChange.emit).toHaveBeenCalledTimes(3);
  });

  it('updates question text, required flag, and ideal response via onQuestionChange', () => {
    buildComponent([question()]);
    spyOn(component.questionsChange, 'emit');
    const updated = {
      ...component.questions()[0],
      text: 'New question',
      required: false,
      idealResponse: 'A strong answer includes evidence.',
    };

    component.onQuestionChange(updated);

    expect(component.questions()[0]).toEqual(updated);
    expect(component.questionsChange.emit).toHaveBeenCalledOnceWith([updated]);
  });

  // Reordering is persisted by the order field, not just by array position.
  it('reorders questions after drag and drop and rewrites order numbers', () => {
    buildComponent([
      question({ questionId: 'q-1', order: 1 }),
      question({ questionId: 'q-2', order: 2 }),
      question({ questionId: 'q-3', order: 3 }),
    ]);
    spyOn(component.questionsChange, 'emit');

    component.onDrop({ previousIndex: 0, currentIndex: 2 } as any);

    expect(component.questions().map(q => q.questionId)).toEqual(['q-2', 'q-3', 'q-1']);
    expect(component.questions().map(q => q.order)).toEqual([1, 2, 3]);
    expect(component.questionsChange.emit).toHaveBeenCalled();
  });

  // The AI rubric panel is future-facing UI, but its open state controls
  // whether the idealResponse textarea can update a question.
  it('toggles the ideal response panel by question id', () => {
    buildComponent([question()]);

    component.toggleIdeal('q-1');
    expect(component.idealOpen['q-1']).toBeTrue();

    component.toggleIdeal('q-1');
    expect(component.idealOpen['q-1']).toBeFalse();
  });

  // Excel import maps spreadsheet columns into TemplateQuestion rows and appends
  // them after existing questions.
  it('imports questions from a selected spreadsheet file', () => {
    buildComponent([question({ questionId: 'existing', order: 1 })]);
    spyOn(component.questionsChange, 'emit');
    spyOn(crypto, 'randomUUID').and.returnValues('import-1' as `${string}-${string}-${string}-${string}-${string}`, 'import-2' as `${string}-${string}-${string}-${string}-${string}`);
    const sheet = XLSX.utils.json_to_sheet([
      { Question: 'Imported text', Type: 'text', Required: 'no', 'Ideal Response': 'Evidence' },
      { Question: 'Pick one', Type: 'single_select', Options: 'Yes, No', Required: 'yes' },
    ]);
    const workbook = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(workbook, sheet, 'Sheet1');
    const binary = XLSX.write(workbook, { bookType: 'xlsx', type: 'binary' });

    class MockFileReader {
      onload: ((event: any) => void) | null = null;
      readAsBinaryString(): void {
        this.onload?.({ target: { result: binary } });
      }
    }
    const originalFileReader = (window as any).FileReader;
    (window as any).FileReader = MockFileReader as any;

    const input = document.createElement('input');
    Object.defineProperty(input, 'files', { value: [new File(['x'], 'questions.xlsx')] });

    try {
      component.onFileSelected({ target: input } as any);
    } finally {
      (window as any).FileReader = originalFileReader;
    }

    expect(component.questions().map(q => q.questionId)).toEqual(['existing', 'import-1', 'import-2']);
    expect(component.questions()[1]).toEqual(jasmine.objectContaining({
      text: 'Imported text',
      type: 'TEXT',
      options: [],
      required: false,
      order: 2,
      idealResponse: 'Evidence',
    }));
    expect(component.questions()[2].options).toEqual(['Yes', 'No']);
    expect(input.value).toBe('');
    expect(component.questionsChange.emit).toHaveBeenCalled();
  });

  it('does nothing when file selection is cancelled', () => {
    buildComponent([question()]);
    spyOn(component.questionsChange, 'emit');

    component.onFileSelected({ target: document.createElement('input') } as any);

    expect(component.questions().length).toBe(1);
    expect(component.questionsChange.emit).not.toHaveBeenCalled();
  });

  it('uses spreadsheet import defaults when optional columns are missing', () => {
    buildComponent();
    spyOn(component.questionsChange, 'emit');
    spyOn(crypto, 'randomUUID').and.returnValue('import-default' as `${string}-${string}-${string}-${string}-${string}`);
    const sheet = XLSX.utils.json_to_sheet([{ Question: 'Defaulted question' }]);
    const workbook = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(workbook, sheet, 'Sheet1');
    const binary = XLSX.write(workbook, { bookType: 'xlsx', type: 'binary' });

    class MockFileReader {
      onload: ((event: any) => void) | null = null;
      readAsBinaryString(): void {
        this.onload?.({ target: { result: binary } });
      }
    }
    const originalFileReader = (window as any).FileReader;
    (window as any).FileReader = MockFileReader as any;

    const input = document.createElement('input');
    Object.defineProperty(input, 'files', { value: [new File(['x'], 'defaults.xlsx')] });

    try {
      component.onFileSelected({ target: input } as any);
    } finally {
      (window as any).FileReader = originalFileReader;
    }

    expect(component.questions()[0]).toEqual(jasmine.objectContaining({
      questionId: 'import-default',
      text: 'Defaulted question',
      type: 'TEXT',
      options: [],
      required: true,
      order: 1,
      idealResponse: undefined,
    }));
    expect(component.questionsChange.emit).toHaveBeenCalled();
  });

  it('handles option edits when a question starts without options', () => {
    const base = question({ questionId: 'q-1', type: 'CHECKBOX', options: undefined as any });
    buildComponent([base]);

    component.addOption(base);
    expect(component.questions()[0].options).toEqual(['Option 1']);

    component.removeOption({ ...component.questions()[0], options: undefined as any }, 0);
    expect(component.questions()[0].options).toEqual([]);
  });

  // Next is guarded so the parent only moves to preview with at least one question.
  it('emits next only when questions exist', () => {
    buildComponent();
    spyOn(component.next, 'emit');

    component.onNext();
    expect(component.next.emit).not.toHaveBeenCalled();

    component.addQuestion('TEXT');
    component.onNext();
    expect(component.next.emit).toHaveBeenCalledOnceWith(component.questions());
  });

  it('returns stable trackBy values', () => {
    buildComponent();

    expect(component.trackById(0, question({ questionId: 'abc' }))).toBe('abc');
    expect(component.trackByIdx(3)).toBe(3);
  });

  // Template smoke coverage checks the empty state, add buttons, option UI,
  // and disabled/enabled preview action without asserting styling internals.
  it('renders empty state and enables preview after adding a question from the template', () => {
    buildComponent();

    expect(fixture.nativeElement.textContent).toContain('0 questions');
    expect(fixture.debugElement.query(By.css('.qb-btn-primary')).nativeElement.disabled).toBeTrue();

    const addTextButton = fixture.debugElement.queryAll(By.css('.qb-add-btn'))[0];
    addTextButton.triggerEventHandler('click');
    fixture.detectChanges();

    expect(component.questions().length).toBe(1);
    expect(fixture.nativeElement.textContent).toContain('1 question');
    expect(fixture.debugElement.query(By.css('.qb-card'))).not.toBeNull();
    expect(fixture.debugElement.query(By.css('.qb-btn-primary')).nativeElement.disabled).toBeFalse();
  });

  it('renders option controls for option-based questions', () => {
    buildComponent([question({ type: 'CHECKBOX', options: ['A', 'B'] })]);
    fixture.detectChanges();

    expect(fixture.debugElement.query(By.css('.qb-options'))).not.toBeNull();
    expect(fixture.debugElement.queryAll(By.css('.qb-option-row')).length).toBe(2);
    expect(fixture.nativeElement.textContent).toContain('AI Rubric');
  });
});
