import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule } from '@angular/forms';
import { FieldType, FieldTypeConfig } from '@ngx-formly/core';

@Component({
  selector: 'app-formly-select',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  template: `
    <select
      class="formly-select"
      [formControl]="formControl"
      [multiple]="isMultiple()">
      <option value="" disabled>Choose an option…</option>
      <option *ngFor="let opt of getOptions()" [value]="opt.value">
        {{ opt.label }}
      </option>
    </select>
  `,
  styles: [`
    .formly-select {
      width: 100%; box-sizing: border-box;
      height: 38px; padding: 0 10px;
      border: 1px solid #DEDEDE; border-radius: 8px;
      font-size: 13px; font-family: inherit; color: #2E2E2E;
      background: #fafafa; outline: none; cursor: pointer;
      transition: border-color 0.2s;
    }
    .formly-select[multiple] { height: auto; min-height: 80px; padding: 6px; }
    .formly-select:focus { border-color: #4A90E2; background: white; box-shadow: 0 0 0 3px rgba(74, 144, 226,0.1); }
    .formly-select.ng-invalid.ng-touched { border-color: #ef4444; }
  `]
})
export class FormlySelectFieldComponent extends FieldType<FieldTypeConfig> {
  isMultiple(): boolean {
    return this.props['multiple'] || false;
  }

  getOptions(): any[] {
    if (Array.isArray(this.props.options)) return this.props.options;
    return [];
  }
}
