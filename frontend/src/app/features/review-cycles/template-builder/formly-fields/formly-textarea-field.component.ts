import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule } from '@angular/forms';
import { FieldType, FieldTypeConfig } from '@ngx-formly/core';

@Component({
  selector: 'app-formly-textarea',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  template: `
    <textarea
      class="formly-textarea"
      [formControl]="formControl"
      [rows]="props.rows || 4"
      [placeholder]="props.placeholder || 'Enter your response…'">
    </textarea>
  `,
  styles: [`
    .formly-textarea {
      width: 100%; box-sizing: border-box;
      padding: 10px 12px; border: 1px solid #DEDEDE; border-radius: 8px;
      font-size: 13px; font-family: inherit; color: #2E2E2E;
      resize: vertical; min-height: 80px; outline: none; transition: border-color 0.2s;
      background: #fafafa;
    }
    .formly-textarea:focus { border-color: #4A90E2; background: white; box-shadow: 0 0 0 3px rgba(74, 144, 226,0.1); }
    .formly-textarea.ng-invalid.ng-touched { border-color: #ef4444; }
  `]
})
export class FormlyTextareaFieldComponent extends FieldType<FieldTypeConfig> {}
