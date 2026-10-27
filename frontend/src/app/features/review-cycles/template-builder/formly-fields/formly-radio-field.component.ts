import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule } from '@angular/forms';
import { FieldType, FieldTypeConfig } from '@ngx-formly/core';

@Component({
  selector: 'app-formly-radio',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  template: `
    <div class="radio-group">
      <label class="radio-item" *ngFor="let opt of getOptions()">
        <input
          type="radio"
          class="radio-input"
          [name]="getFieldName()"
          [value]="opt.value"
          [formControl]="formControl">
        <span class="radio-label">{{ opt.label }}</span>
      </label>
    </div>
  `,
  styles: [`
    .radio-group { display: flex; flex-direction: column; gap: 8px; margin: 4px 0; }
    .radio-item {
      display: flex; align-items: center; gap: 10px; cursor: pointer;
      padding: 6px 10px; border-radius: 6px; transition: background 0.15s;
    }
    .radio-item:hover { background: #EFF5FC; }
    .radio-input { width: 16px; height: 16px; accent-color: #4A90E2; cursor: pointer; }
    .radio-label { font-size: 13px; color: #2E2E2E; }
  `]
})
export class FormlyRadioFieldComponent extends FieldType<FieldTypeConfig> {
  getOptions(): any[] {
    if (Array.isArray(this.props.options)) return this.props.options;
    return [];
  }

  getFieldName(): string {
    return this.field.key?.toString() ?? 'radio_group';
  }
}
