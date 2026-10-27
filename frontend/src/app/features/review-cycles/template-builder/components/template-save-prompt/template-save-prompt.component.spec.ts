import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';

import { TemplateSavePromptComponent, SaveTemplateResult } from './template-save-prompt.component';

describe('TemplateSavePromptComponent', () => {
  let fixture: ComponentFixture<TemplateSavePromptComponent>;
  let component: TemplateSavePromptComponent;

  function buildComponent(inputs: Partial<TemplateSavePromptComponent> = {}) {
    TestBed.configureTestingModule({
      imports: [TemplateSavePromptComponent],
    });

    fixture = TestBed.createComponent(TemplateSavePromptComponent);
    component = fixture.componentInstance;
    Object.assign(component, inputs);
    fixture.detectChanges();
    return { fixture, component };
  }

  // Initial values are important in edit mode because the prompt doubles as
  // the "review template details before saving" dialog.
  it('hydrates initial name, type, and description on init', () => {
    buildComponent({
      initialName: 'Manager Check-in',
      initialType: 'MANAGER',
      initialDescription: 'Quarterly manager review',
    });

    expect(component.templateName).toBe('Manager Check-in');
    expect(component.reviewType).toBe('MANAGER');
    expect(component.description).toBe('Quarterly manager review');
  });

  it('keeps defaults when optional initial values are not provided', () => {
    buildComponent();

    expect(component.templateName).toBe('');
    expect(component.reviewType).toBe('SELF');
    expect(component.description).toBe('');
  });

  // Save validation protects the parent from receiving unusable template names.
  it('blocks saving when the template name is blank', () => {
    buildComponent();
    spyOn(component.result, 'emit');
    component.templateName = '   ';

    component.onSave();

    expect(component.nameError).toBe('Template name is required.');
    expect(component.result.emit).not.toHaveBeenCalled();
  });

  it('blocks saving when the template name is longer than 100 characters', () => {
    buildComponent();
    spyOn(component.result, 'emit');
    component.templateName = 'x'.repeat(101);

    component.onSave();

    expect(component.nameError).toBe('Name must be 100 characters or fewer.');
    expect(component.result.emit).not.toHaveBeenCalled();
  });

  // Successful save trims text and omits empty descriptions so callers can
  // distinguish "blank optional field" from a meaningful description.
  it('emits a saved result with trimmed fields', () => {
    buildComponent();
    spyOn(component.result, 'emit');
    component.templateName = '  Self Review Template  ';
    component.reviewType = 'SELF';
    component.description = '  Used for self assessments  ';

    component.onSave();

    expect(component.nameError).toBe('');
    expect(component.result.emit).toHaveBeenCalledOnceWith({
      saved: true,
      templateName: 'Self Review Template',
      reviewType: 'SELF',
      description: 'Used for self assessments',
    });
  });

  it('emits undefined description when saved with a blank description', () => {
    buildComponent();
    spyOn(component.result, 'emit');
    component.templateName = 'Peer Template';
    component.reviewType = 'PEER_360';
    component.description = '   ';

    component.onSave();

    const payload = (component.result.emit as jasmine.Spy).calls.mostRecent().args[0] as SaveTemplateResult;
    expect(payload).toEqual({
      saved: true,
      templateName: 'Peer Template',
      reviewType: 'PEER_360',
      description: undefined,
    });
  });

  // Skip is only available outside edit mode and tells the parent to attach
  // the milestone without saving a reusable template.
  it('emits an unsaved result when skipping', () => {
    buildComponent();
    spyOn(component.result, 'emit');
    component.reviewType = 'MANAGER';
    component.description = '  attach only  ';

    component.onSkip();

    expect(component.result.emit).toHaveBeenCalledOnceWith({
      saved: false,
      reviewType: 'MANAGER',
      description: 'attach only',
    });
  });

  it('omits blank description when skipping', () => {
    buildComponent();
    spyOn(component.result, 'emit');
    component.description = ' ';

    component.onSkip();

    expect(component.result.emit).toHaveBeenCalledOnceWith({
      saved: false,
      reviewType: 'SELF',
      description: undefined,
    });
  });

  // Backdrop behavior changes by mode: edit mode cancels, create mode skips.
  it('uses backdrop click to skip in create mode', () => {
    buildComponent({ editMode: false });
    spyOn(component, 'onSkip');

    component.onBackdrop();

    expect(component.onSkip).toHaveBeenCalled();
  });

  it('uses backdrop click to cancel in edit mode', () => {
    buildComponent({ editMode: true });
    spyOn(component.cancel, 'emit');

    component.onBackdrop();

    expect(component.cancel.emit).toHaveBeenCalled();
  });

  // Template smoke tests cover the visible mode-specific controls and the
  // inline name-error reset bound to the input event.
  it('renders create-mode copy and clears name errors on input', () => {
    buildComponent();
    component.nameError = 'Template name is required.';
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Save template for future use?');
    expect(text).toContain('Skip & Attach Only');

    const input = fixture.debugElement.query(By.css('input.sp-input')).nativeElement as HTMLInputElement;
    input.value = 'New name';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(component.nameError).toBe('');
    expect(fixture.nativeElement.textContent).not.toContain('Template name is required.');
  });

  it('renders edit-mode copy and emits cancel from the cancel button', () => {
    buildComponent({ editMode: true });
    spyOn(component.cancel, 'emit');
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Update template details');
    expect(text).toContain('Cancel');
    expect(text).not.toContain('Skip & Attach Only');

    fixture.debugElement.query(By.css('.sp-btn-skip')).triggerEventHandler('click');

    expect(component.cancel.emit).toHaveBeenCalled();
  });
});
