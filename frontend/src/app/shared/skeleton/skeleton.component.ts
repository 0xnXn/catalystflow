import { Component, Input } from '@angular/core';

/**
 * Tiny placeholder block with a shimmer animation. Composable into any layout
 * you need to skeleton-load — drop a few of these where the real content will
 * appear, give them sensible widths/heights, and you have a skeleton screen.
 *
 * @example
 *   <app-skeleton width="60%" height="14px"></app-skeleton>          line
 *   <app-skeleton width="40px" height="40px" radius="50%"></app-skeleton>  avatar
 *   <app-skeleton width="100%" height="120px" radius="12px"></app-skeleton> card
 */
@Component({
  selector: 'app-skeleton',
  standalone: true,
  template: `<span class="sk" [style.width]="width" [style.height]="height" [style.borderRadius]="radius"></span>`,
  styles: [`
    .sk {
      display: inline-block;
      vertical-align: middle;
      background: linear-gradient(90deg, #eef0f3 0%, #f7f8fa 50%, #eef0f3 100%);
      background-size: 200% 100%;
      animation: sk-shimmer 1.4s ease-in-out infinite;
    }

    @keyframes sk-shimmer {
      0%   { background-position: 200% 0; }
      100% { background-position: -200% 0; }
    }

    @media (prefers-reduced-motion: reduce) {
      .sk { animation: none; opacity: 0.85; }
    }
  `],
})
export class SkeletonComponent {
  @Input() width: string = '100%';
  @Input() height: string = '14px';
  @Input() radius: string = '6px';
}
