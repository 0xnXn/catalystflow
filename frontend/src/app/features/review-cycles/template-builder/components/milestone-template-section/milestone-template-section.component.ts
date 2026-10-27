import { Component, Input, Output, EventEmitter, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  TemplateSnapshot, REVIEW_TYPE_LABELS, QUESTION_TYPE_LABELS, ReviewType
} from '../../models/template.model';

@Component({
  selector: 'app-milestone-template-section',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './milestone-template-section.component.html',
  styleUrl: './milestone-template-section.component.css'
})
export class MilestoneTemplateSectionComponent {
  @Input() snapshot: TemplateSnapshot | undefined | null = null;
  /** When true: template is visible but cannot be created, changed or removed. */
  @Input() readonly = false;
  @Output() createTemplate  = new EventEmitter<void>();
  @Output() browseTemplates = new EventEmitter<void>();
  @Output() editTemplate    = new EventEmitter<void>();
  @Output() removeTemplate  = new EventEmitter<void>();

  readonly reviewTypeLabels   = REVIEW_TYPE_LABELS;
  readonly questionTypeLabels = QUESTION_TYPE_LABELS;

  expanded    = signal(true);
  qqExpanded  = signal(false);

  toggleSection(): void  { this.expanded.set(!this.expanded()); }
  toggleQuestions(): void { this.qqExpanded.set(!this.qqExpanded()); }

  reviewTypeBadgeClass(type: ReviewType): string {
    const map: Record<ReviewType, string> = {
      SELF:     'mts-badge-self',
      PEER_360: 'mts-badge-360',
      MANAGER:  'mts-badge-manager',
    };
    return map[type] ?? '';
  }
}
