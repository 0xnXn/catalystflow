import {
  Component, Output, EventEmitter, OnInit, signal, computed
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  ReviewTemplate, ReviewType,
  REVIEW_TYPES, REVIEW_TYPE_LABELS, QUESTION_TYPE_LABELS
} from '../../models/template.model';
import { TemplateService } from '../../services/template.service';

@Component({
  selector: 'app-saved-templates-grid',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './saved-templates-grid.component.html',
  styleUrl: './saved-templates-grid.component.css'
})
export class SavedTemplatesGridComponent implements OnInit {
  @Output() templateSelected = new EventEmitter<ReviewTemplate>();
  @Output() back = new EventEmitter<void>();

  readonly reviewTypes    = REVIEW_TYPES;
  readonly reviewTypeLabels = REVIEW_TYPE_LABELS;
  readonly questionTypeLabels = QUESTION_TYPE_LABELS;

  templates   = signal<ReviewTemplate[]>([]);
  loading     = signal(true);
  error       = signal('');
  filterType  = signal<ReviewType | 'ALL'>('ALL');
  expandedId  = signal<string | null>(null);

  filtered = computed(() => {
    const f = this.filterType();
    return f === 'ALL'
      ? this.templates()
      : this.templates().filter(t => t.reviewType === f);
  });

  constructor(private svc: TemplateService) {}

  ngOnInit(): void {
    this.svc.getAll().subscribe({
      next:  r => { this.templates.set(r); this.loading.set(false); },
      error: () => { this.error.set('Failed to load templates.'); this.loading.set(false); }
    });
  }

  setFilter(type: ReviewType | 'ALL'): void { this.filterType.set(type); }

  toggleExpand(id: string): void {
    this.expandedId.set(this.expandedId() === id ? null : id);
  }

  select(t: ReviewTemplate): void { this.templateSelected.emit(t); }

  reviewTypeBadgeClass(type: ReviewType): string {
    const map: Record<ReviewType, string> = {
      SELF:     'badge-self',
      PEER_360: 'badge-360',
      MANAGER:  'badge-manager',
    };
    return map[type] ?? '';
  }

  formatDate(iso?: string): string {
    if (!iso) return '—';
    return new Date(iso).toLocaleDateString('en-AU', {
      day: '2-digit', month: 'short', year: 'numeric'
    });
  }
}
