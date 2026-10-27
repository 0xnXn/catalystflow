import { Component, Input, Output, EventEmitter, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ReviewType, REVIEW_TYPES, REVIEW_TYPE_LABELS } from '../../models/template.model';

export interface SaveTemplateResult {
  saved: boolean;
  templateName?: string;
  reviewType?: ReviewType;
  description?: string;
}

@Component({
  selector: 'app-template-save-prompt',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './template-save-prompt.component.html',
  styleUrl: './template-save-prompt.component.css'
})
export class TemplateSavePromptComponent implements OnInit {
  @Input() editMode = false;
  @Input() initialName?: string;
  @Input() initialType?: ReviewType;
  @Input() initialDescription?: string;

  @Output() result = new EventEmitter<SaveTemplateResult>();
  @Output() cancel = new EventEmitter<void>();

  readonly reviewTypes = REVIEW_TYPES;
  readonly reviewTypeLabels = REVIEW_TYPE_LABELS;

  templateName = '';
  reviewType: ReviewType = 'SELF';
  description = '';
  nameError = '';
  saving = false;

  ngOnInit(): void {
    if (this.initialName)        this.templateName = this.initialName;
    if (this.initialType)        this.reviewType   = this.initialType;
    if (this.initialDescription) this.description  = this.initialDescription;
  }

  onBackdrop(): void {
    if (this.editMode) {
      this.cancel.emit();
    } else {
      this.onSkip();
    }
  }

  onSave(): void {
    this.nameError = '';
    if (!this.templateName.trim()) {
      this.nameError = 'Template name is required.';
      return;
    }
    if (this.templateName.trim().length > 100) {
      this.nameError = 'Name must be 100 characters or fewer.';
      return;
    }
    this.result.emit({
      saved: true,
      templateName: this.templateName.trim(),
      reviewType: this.reviewType,
      description: this.description.trim() || undefined,
    });
  }

  onSkip(): void {
    this.result.emit({
      saved: false,
      reviewType: this.reviewType,
      description: this.description.trim() || undefined,
    });
  }
}
