import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule } from '@angular/forms';
import { FieldType, FieldTypeConfig } from '@ngx-formly/core';

@Component({
  selector: 'app-formly-multicheckbox',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  template: `
    <div class="checkbox-group">
      <label class="checkbox-item" *ngFor="let opt of getOptions()">
        <input
          type="checkbox"
          class="checkbox-input"
          [value]="opt.value"
          [checked]="isChecked(opt.value)"
          (change)="toggleOption(opt.value, $event)">
        <span class="checkbox-label">{{ opt.label }}</span>
      </label>
    </div>
  `,
  styles: [`
    .checkbox-group { display: flex; flex-direction: column; gap: 8px; margin: 4px 0; }
    .checkbox-item {
      display: flex; align-items: center; gap: 10px; cursor: pointer;
      padding: 6px 10px; border-radius: 6px; transition: background 0.15s;
    }
    .checkbox-item:hover { background: #EFF5FC; }
    .checkbox-input { width: 16px; height: 16px; accent-color: #4A90E2; cursor: pointer; }
    .checkbox-label { font-size: 13px; color: #2E2E2E; }
  `]
})
export class FormlyMulticheckboxFieldComponent extends FieldType<FieldTypeConfig> {
  getOptions(): any[] {
    if (Array.isArray(this.props.options)) return this.props.options;
    return [];
  }

  isChecked(value: string): boolean {
    const current = this.formControl.value as string[] | null;
    return Array.isArray(current) && current.includes(value);
  }

  toggleOption(value: string, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    const current: string[] = Array.isArray(this.formControl.value) ? [...this.formControl.value] : [];
    if (checked) {
      this.formControl.setValue([...current, value]);
    } else {
      this.formControl.setValue(current.filter(v => v !== value));
    }
  }
}
