import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';

import { MilestoneTemplateSectionComponent } from './milestone-template-section.component';
import { TemplateSnapshot } from '../../models/template.model';

describe('MilestoneTemplateSectionComponent', () => {
  let fixture: ComponentFixture<MilestoneTemplateSectionComponent>;
  let component: MilestoneTemplateSectionComponent;

  function snapshot(overrides: Partial<TemplateSnapshot> = {}): TemplateSnapshot {
    return {
      templateId: 'template-1',
      templateName: 'Manager Growth Template',
      reviewType: 'MANAGER',
      description: 'Questions used for manager milestone reviews.',
      snapshotCreatedAt: '2026-05-23T00:00:00.000Z',
      questions: [
        {
          questionId: 'q-1',
          text: 'What impact did this employee make?',
          type: 'TEXT',
          options: [],
          required: true,
          order: 1,
        },
        {
          questionId: 'q-2',
          text: 'Select strengths',
          type: 'CHECKBOX',
          options: ['Ownership', 'Communication'],
          required: false,
          order: 2,
        },
      ],
      ...overrides,
    };
  }

  function buildComponent(inputs: Partial<MilestoneTemplateSectionComponent> = {}) {
    TestBed.configureTestingModule({
      imports: [MilestoneTemplateSectionComponent],
    });

    fixture = TestBed.createComponent(MilestoneTemplateSectionComponent);
    component = fixture.componentInstance;
    Object.assign(component, inputs);
    fixture.detectChanges();
    return { fixture, component };
  }

  // Empty editable milestones must expose both attach paths to the parent modal.
  it('renders create and browse actions when no template is attached', () => {
    buildComponent();
    spyOn(component.createTemplate, 'emit');
    spyOn(component.browseTemplates, 'emit');

    const buttons = fixture.debugElement.queryAll(By.css('.mts-opt-btn'));
    buttons[0].triggerEventHandler('click');
    buttons[1].triggerEventHandler('click');

    expect(fixture.nativeElement.textContent).toContain('Attach a review template');
    expect(fixture.nativeElement.textContent).toContain('Create Template');
    expect(fixture.nativeElement.textContent).toContain('Use Saved Template');
    expect(component.createTemplate.emit).toHaveBeenCalled();
    expect(component.browseTemplates.emit).toHaveBeenCalled();
  });

  // Readonly mode is used by locked milestones, so creation controls should disappear.
  it('shows a locked empty state without editable actions', () => {
    buildComponent({ readonly: true });

    expect(fixture.nativeElement.textContent).toContain('Locked');
    expect(fixture.nativeElement.textContent).toContain('No template was attached to this milestone.');
    expect(fixture.debugElement.query(By.css('.mts-opt-btn'))).toBeNull();
  });

  // Attached templates show the frozen snapshot summary and emit edit/remove only when unlocked.
  it('renders attached template details and emits edit/remove actions', () => {
    buildComponent({ snapshot: snapshot() });
    spyOn(component.editTemplate, 'emit');
    spyOn(component.removeTemplate, 'emit');

    fixture.debugElement.query(By.css('.mts-btn-edit')).triggerEventHandler('click');
    fixture.debugElement.query(By.css('.mts-btn-remove')).triggerEventHandler('click');

    expect(fixture.nativeElement.textContent).toContain('Attached');
    expect(fixture.nativeElement.textContent).toContain('Manager Growth Template');
    expect(fixture.nativeElement.textContent).toContain('Manager Review');
    expect(fixture.nativeElement.textContent).toContain('2 questions');
    expect(fixture.nativeElement.textContent).toContain('Questions used for manager milestone reviews.');
    expect(component.editTemplate.emit).toHaveBeenCalled();
    expect(component.removeTemplate.emit).toHaveBeenCalled();
  });

  it('hides edit/remove actions when an attached template is readonly', () => {
    buildComponent({ snapshot: snapshot(), readonly: true });

    expect(fixture.nativeElement.textContent).toContain('Locked');
    expect(fixture.debugElement.query(By.css('.mts-btn-edit'))).toBeNull();
    expect(fixture.debugElement.query(By.css('.mts-btn-remove'))).toBeNull();
    expect(fixture.debugElement.query(By.css('.mts-attached')).classes['mts-attached-locked']).toBeTrue();
  });

  // Question expansion is the main detail view for reviewing an attached snapshot.
  it('toggles section and question list visibility', () => {
    buildComponent({ snapshot: snapshot() });

    expect(fixture.debugElement.query(By.css('.mts-body'))).not.toBeNull();
    component.toggleSection();
    fixture.detectChanges();
    expect(fixture.debugElement.query(By.css('.mts-body'))).toBeNull();

    component.toggleSection();
    component.toggleQuestions();
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Hide questions');
    expect(text).toContain('1.');
    expect(text).toContain('What impact did this employee make?');
    expect(text).toContain('Long Text');
    expect(text).toContain('*');
  });

  // Snapshot fallback values keep incomplete imported templates readable.
  it('falls back for missing template name and untitled questions', () => {
    buildComponent({
      snapshot: snapshot({
        templateName: '',
        description: undefined,
        questions: [
          {
            questionId: 'q-1',
            text: '',
            type: 'RATING',
            options: [],
            required: false,
            order: 1,
          },
        ],
      }),
    });
    component.toggleQuestions();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Custom Template');
    expect(fixture.nativeElement.textContent).toContain('1 question');
    expect(fixture.nativeElement.textContent).toContain('(Untitled)');
    expect(fixture.nativeElement.textContent).toContain('Star Rating');
  });

  // Badge class mapping is small but business-visible because each review type is color-coded.
  it('maps review types to badge classes and returns empty for unknown values', () => {
    buildComponent();

    expect(component.reviewTypeBadgeClass('SELF')).toBe('mts-badge-self');
    expect(component.reviewTypeBadgeClass('PEER_360')).toBe('mts-badge-360');
    expect(component.reviewTypeBadgeClass('MANAGER')).toBe('mts-badge-manager');
    expect(component.reviewTypeBadgeClass('UNKNOWN' as any)).toBe('');
  });
});
