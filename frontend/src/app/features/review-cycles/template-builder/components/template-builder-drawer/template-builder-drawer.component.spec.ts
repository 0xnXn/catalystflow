import { SimpleChange } from '@angular/core';
import { of, throwError } from 'rxjs';

import { TemplateBuilderDrawerComponent } from './template-builder-drawer.component';
import { ReviewTemplate, TemplateQuestion } from '../../models/template.model';
import { TemplateService } from '../../services/template.service';

describe('TemplateBuilderDrawerComponent', () => {
  let component: TemplateBuilderDrawerComponent;
  let templateService: jasmine.SpyObj<TemplateService>;

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

  function template(overrides: Partial<ReviewTemplate> = {}): ReviewTemplate {
    return {
      templateId: 'tpl-1',
      templateName: 'Manager Template',
      reviewType: 'MANAGER',
      description: 'Manager review questions',
      questions: [question()],
      createdAt: '2026-01-01T00:00:00.000Z',
      updatedAt: '2026-01-02T00:00:00.000Z',
      ...overrides,
    };
  }

  function buildComponent() {
    templateService = jasmine.createSpyObj<TemplateService>('TemplateService', ['create', 'update']);
    component = new TemplateBuilderDrawerComponent(templateService);
    return { component, templateService };
  }

  function openDrawer() {
    component.ngOnChanges({
      open: new SimpleChange(false, true, false),
    });
  }

  // Opening resets transient state and seeds questions differently by mode.
  it('resets build mode when opened', () => {
    buildComponent();
    component.mode = 'build';
    component.questions.set([question()]);
    component.step.set('save-prompt');
    component.saving.set(true);
    component.saveError.set('Previous error');

    openDrawer();

    expect(component.step()).toBe('questions');
    expect(component.questions()).toEqual([]);
    expect(component.saving()).toBeFalse();
    expect(component.saveError()).toBe('');
  });

  it('hydrates edit mode from editingTemplate before falling back to existingQuestions', () => {
    buildComponent();
    const edited = template({ questions: [question({ questionId: 'from-template' })] });
    component.mode = 'edit';
    component.editingTemplate = edited;
    component.existingQuestions = [question({ questionId: 'fallback' })];

    openDrawer();

    expect(component.questions().map(q => q.questionId)).toEqual(['from-template']);
    expect(component.questions()).not.toBe(edited.questions);

    component.editingTemplate = undefined;
    openDrawer();
    expect(component.questions().map(q => q.questionId)).toEqual(['fallback']);
  });

  // Step transitions guard the parent drawer flow from builder to preview to save prompt.
  it('moves through questions, preview, and save prompt steps', () => {
    buildComponent();

    component.onQuestionsNext([question()]);
    expect(component.questions().length).toBe(1);
    expect(component.step()).toBe('preview');
    expect(component.currentStep).toBe(2);

    component.onSaveAndAttach();
    expect(component.step()).toBe('save-prompt');
    expect(component.currentStep).toBe(3);

    component.onSavePromptCancel();
    expect(component.step()).toBe('preview');

    component.onPreviewBack();
    expect(component.step()).toBe('questions');
  });

  it('returns browse mode as step zero', () => {
    buildComponent();
    component.mode = 'browse';

    expect(component.currentStep).toBe(0);
  });

  // Unsaved attach is intentional: users can attach a one-off snapshot without creating a reusable template.
  it('attaches without saving when the save prompt is skipped', () => {
    buildComponent();
    spyOn(component.templateAttached, 'emit');
    spyOn(component.closed, 'emit');
    component.questions.set([question()]);

    component.onSavePromptResult({
      saved: false,
      reviewType: 'PEER_360',
      description: 'Attach only',
    }, templateService);

    const snapshot = (component.templateAttached.emit as jasmine.Spy).calls.mostRecent().args[0];
    expect(snapshot).toEqual(jasmine.objectContaining({
      templateId: undefined,
      templateName: undefined,
      reviewType: 'PEER_360',
      description: 'Attach only',
      questions: component.questions(),
    }));
    expect(snapshot.snapshotCreatedAt).toBeTruthy();
    expect(component.closed.emit).toHaveBeenCalled();
  });

  // Save creates a reusable template in build mode, then emits the frozen snapshot.
  it('creates a template and attaches the saved snapshot', () => {
    buildComponent();
    const saved = template({ templateId: 'created-1', templateName: 'Created Template', reviewType: 'SELF' });
    templateService.create.and.returnValue(of(saved));
    spyOn(component.templateAttached, 'emit');
    spyOn(component.closed, 'emit');
    component.mode = 'build';
    component.questions.set([question()]);

    component.onSavePromptResult({
      saved: true,
      templateName: 'Created Template',
      reviewType: 'SELF',
      description: 'Reusable',
    }, templateService);

    expect(templateService.create).toHaveBeenCalledWith({
      templateName: 'Created Template',
      reviewType: 'SELF',
      description: 'Reusable',
      questions: component.questions(),
    });
    expect(component.saving()).toBeFalse();
    expect(component.templateAttached.emit).toHaveBeenCalledWith(jasmine.objectContaining({
      templateId: 'created-1',
      templateName: 'Created Template',
      reviewType: 'SELF',
    }));
    expect(component.closed.emit).toHaveBeenCalled();
  });

  it('updates an existing template in edit mode', () => {
    buildComponent();
    const edited = template({ templateId: 'tpl-edit', templateName: 'Updated Manager Template' });
    templateService.update.and.returnValue(of(edited));
    spyOn(component.templateAttached, 'emit');
    component.mode = 'edit';
    component.editingTemplate = template({ templateId: 'tpl-edit' });
    component.questions.set([question()]);

    component.onSavePromptResult({
      saved: true,
      templateName: 'Updated Manager Template',
      reviewType: 'MANAGER',
    }, templateService);

    expect(templateService.update).toHaveBeenCalledWith('tpl-edit', jasmine.objectContaining({
      templateName: 'Updated Manager Template',
      reviewType: 'MANAGER',
    }));
    expect(templateService.create).not.toHaveBeenCalled();
    expect(component.templateAttached.emit).toHaveBeenCalledWith(jasmine.objectContaining({
      templateId: 'tpl-edit',
      templateName: 'Updated Manager Template',
    }));
  });

  // Error handling keeps users in the save prompt and shows the most specific backend message.
  it('surfaces backend save errors and falls back for update errors', () => {
    buildComponent();
    component.mode = 'build';
    templateService.create.and.returnValue(throwError(() => ({ error: { message: 'Name already exists' } })));

    component.onSavePromptResult({
      saved: true,
      templateName: 'Duplicate',
      reviewType: 'SELF',
    }, templateService);

    expect(component.saving()).toBeFalse();
    expect(component.saveError()).toBe('Name already exists');

    component.mode = 'edit';
    component.editingTemplate = template({ templateId: 'tpl-1' });
    templateService.update.and.returnValue(throwError(() => ({})));

    component.onSavePromptResult({
      saved: true,
      templateName: 'Updated',
      reviewType: 'MANAGER',
    }, templateService);

    expect(component.saveError()).toBe('Failed to update template. Please try again.');
  });

  it('attaches a selected saved template and closes the drawer', () => {
    buildComponent();
    const selected = template();
    spyOn(component.templateAttached, 'emit');
    spyOn(component.closed, 'emit');

    component.onBrowseSelect(selected);

    expect(component.templateAttached.emit).toHaveBeenCalledWith(jasmine.objectContaining({
      templateId: 'tpl-1',
      templateName: 'Manager Template',
      reviewType: 'MANAGER',
      description: 'Manager review questions',
      questions: selected.questions,
    }));
    expect(component.closed.emit).toHaveBeenCalled();
  });

  it('emits closed from close', () => {
    buildComponent();
    spyOn(component.closed, 'emit');

    component.close();

    expect(component.closed.emit).toHaveBeenCalled();
  });
});
