import { Component, Input, Output, EventEmitter, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormGroup, ReactiveFormsModule } from '@angular/forms';
import { FormlyModule, FormlyFieldConfig } from '@ngx-formly/core';
import { TemplateQuestion } from '../../models/template.model';

@Component({
  selector: 'app-template-preview',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, FormlyModule],
  templateUrl: './template-preview.component.html',
  styleUrl: './template-preview.component.css'
})
export class TemplatePreviewComponent implements OnInit {
  @Input() questions: TemplateQuestion[] = [];
  @Output() back = new EventEmitter<void>();
  @Output() saveAndAttach = new EventEmitter<void>();

  form   = new FormGroup({});
  model: Record<string, any> = {};
  fields: FormlyFieldConfig[] = [];

  ngOnInit(): void {
    this.fields = [...this.questions]
      .sort((a, b) => a.order - b.order)
      .map(q => this.mapQuestion(q));
  }

  private mapQuestion(q: TemplateQuestion): FormlyFieldConfig {
    const key = q.questionId;
    switch (q.type) {
      case 'TEXT':
        return { key, type: 'textarea',
          props: { required: q.required, rows: 3, placeholder: 'Enter your response…' } };
      case 'RATING':
        return { key, type: 'rating', props: { required: q.required } };
      case 'CHECKBOX':
        return { key, type: 'multicheckbox',
          props: { required: q.required, options: (q.options || []).map(o => ({ label: o, value: o })) } };
      case 'SINGLE_SELECT':
        return { key, type: 'radio',
          props: { required: q.required, options: (q.options || []).map(o => ({ label: o, value: o })) } };
      case 'MULTI_SELECT':
        return { key, type: 'select',
          props: { required: q.required, multiple: true, options: (q.options || []).map(o => ({ label: o, value: o })) } };
      default:
        return { key, type: 'textarea', props: { required: q.required } };
    }
  }

  onSaveAndAttach(): void {
    this.saveAndAttach.emit();
  }
}
