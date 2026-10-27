import {
  Component, Input, Output, EventEmitter, OnChanges, SimpleChanges, signal
} from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  ReviewTemplate, ReviewType, TemplateQuestion, TemplateSnapshot,
  CreateReviewTemplateRequest
} from '../../models/template.model';
import { TemplateService } from '../../services/template.service';
import { TemplateQuestionBuilderComponent } from '../template-question-builder/template-question-builder.component';
import { TemplatePreviewComponent } from '../template-preview/template-preview.component';
import { TemplateSavePromptComponent, SaveTemplateResult } from '../template-save-prompt/template-save-prompt.component';
import { SavedTemplatesGridComponent } from '../saved-templates-grid/saved-templates-grid.component';

export type DrawerMode = 'build' | 'browse' | 'edit';

type BuildStep = 'questions' | 'preview' | 'save-prompt';

@Component({
  selector: 'app-template-builder-drawer',
  standalone: true,
  imports: [
    CommonModule,
    TemplateQuestionBuilderComponent,
    TemplatePreviewComponent,
    TemplateSavePromptComponent,
    SavedTemplatesGridComponent,
  ],
  templateUrl: './template-builder-drawer.component.html',
  styleUrl: './template-builder-drawer.component.css'
})
export class TemplateBuilderDrawerComponent implements OnChanges {
  /** Whether the drawer is open */
  @Input() open = false;
  /** Initial mode when opening */
  @Input() mode: DrawerMode = 'build';
  /** For edit mode — pre-populate questions from existing snapshot */
  @Input() existingQuestions: TemplateQuestion[] = [];
  /** For edit mode — the full template being edited (name/type/description/questions/id) */
  @Input() editingTemplate?: ReviewTemplate;

  @Output() closed         = new EventEmitter<void>();
  @Output() templateAttached = new EventEmitter<TemplateSnapshot>();

  step = signal<BuildStep>('questions');
  questions = signal<TemplateQuestion[]>([]);
  saving = signal(false);
  saveError = signal('');

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['open']?.currentValue === true) {
      this.step.set('questions');
      this.saveError.set('');
      this.saving.set(false);
      if (this.mode === 'edit') {
        const seed = this.editingTemplate?.questions ?? this.existingQuestions;
        this.questions.set(seed.length ? [...seed] : []);
      } else {
        this.questions.set([]);
      }
    }
  }

  get currentStep(): number {
    if (this.mode === 'browse') return 0;
    const map: Record<BuildStep, number> = { questions: 1, preview: 2, 'save-prompt': 3 };
    return map[this.step()];
  }

  onQuestionsNext(qs: TemplateQuestion[]): void {
    this.questions.set(qs);
    this.step.set('preview');
  }

  onPreviewBack(): void { this.step.set('questions'); }

  onSaveAndAttach(): void { this.step.set('save-prompt'); }

  onSavePromptResult(result: SaveTemplateResult, svc: TemplateService): void {
    if (!result.saved) {
      this.attachSnapshot(undefined, undefined, result.reviewType, result.description);
      return;
    }
    this.saving.set(true);
    this.saveError.set('');
    const req: CreateReviewTemplateRequest = {
      templateName: result.templateName!,
      reviewType:   result.reviewType!,
      description:  result.description,
      questions:    this.questions(),
    };
    const isEdit = this.mode === 'edit' && !!this.editingTemplate?.templateId;
    const call$ = isEdit
      ? svc.update(this.editingTemplate!.templateId, req)
      : svc.create(req);
    call$.subscribe({
      next: saved => {
        this.saving.set(false);
        this.attachSnapshot(saved.templateId, saved.templateName, saved.reviewType, saved.description);
      },
      error: err => {
        this.saving.set(false);
        const fallback = isEdit
          ? 'Failed to update template. Please try again.'
          : 'Failed to save template. You can still attach without saving.';
        const msg = err?.error?.message ?? fallback;
        this.saveError.set(msg);
      }
    });
  }

  onSavePromptCancel(): void {
    this.step.set('preview');
  }

  onBrowseSelect(t: ReviewTemplate): void {
    this.attachSnapshot(t.templateId, t.templateName, t.reviewType, t.description, t.questions);
  }

  attachSnapshot(
    templateId?: string,
    templateName?: string,
    reviewType?: ReviewType,
    description?: string,
    questions?: TemplateQuestion[]
  ): void {
    const snapshot: TemplateSnapshot = {
      templateId,
      templateName,
      reviewType: reviewType ?? 'SELF',
      description,
      questions: questions ?? this.questions(),
      snapshotCreatedAt: new Date().toISOString(),
    };
    this.templateAttached.emit(snapshot);
    this.closed.emit();
  }

  close(): void { this.closed.emit(); }

  constructor(public svc: TemplateService) {}
}
