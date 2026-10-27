import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FieldType, FieldTypeConfig } from '@ngx-formly/core';

@Component({
  selector: 'app-rating-field',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="rating-field">
      <div class="stars-row">
        <div class="stars">
          <button
            *ngFor="let star of stars; let i = index"
            type="button"
            class="star-btn"
            [class.filled]="i < (hovered || formControl.value || 0)"
            (click)="setValue(i + 1)"
            (mouseenter)="hovered = i + 1"
            (mouseleave)="hovered = 0">
            <svg *ngIf="i < (hovered || formControl.value || 0)"
                 viewBox="0 0 24 24" width="30" height="30" fill="#f59e0b">
              <path d="M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"/>
            </svg>
            <svg *ngIf="i >= (hovered || formControl.value || 0)"
                 viewBox="0 0 24 24" width="30" height="30" fill="none"
                 stroke="#DEDEDE" stroke-width="1.5">
              <path d="M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"/>
            </svg>
          </button>
        </div>
        <span class="rating-value" *ngIf="formControl.value">
          {{ formControl.value }} / 5
        </span>
        <button type="button" class="clear-btn" *ngIf="formControl.value" (click)="setValue(0)">
          Clear
        </button>
      </div>
    </div>
  `,
  styles: [`
    .rating-field { margin: 4px 0 8px; }
    .stars-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
    .stars { display: flex; gap: 2px; }
    .star-btn {
      background: none; border: none; padding: 4px; cursor: pointer;
      border-radius: 4px; transition: transform 0.15s;
      display: flex; align-items: center; justify-content: center;
    }
    .star-btn:hover { transform: scale(1.18); }
    .rating-value {
      font-size: 12px; font-weight: 600; color: #b45309;
      background: #fef3c7; padding: 3px 10px; border-radius: 6px;
    }
    .clear-btn {
      border: none; background: none; font-size: 12px; color: #94a3b8;
      cursor: pointer; text-decoration: underline; font-family: inherit;
    }
    .clear-btn:hover { color: #6b7280; }
  `]
})
export class RatingFieldComponent extends FieldType<FieldTypeConfig> {
  stars = [1, 2, 3, 4, 5];
  hovered = 0;

  setValue(val: number): void {
    this.formControl.setValue(val || null);
  }
}
