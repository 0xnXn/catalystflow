import { Component, OnInit, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  ReviewTemplate, ReviewType, REVIEW_TYPE_LABELS,
} from '../review-cycles/template-builder/models/template.model';
import { TemplateService } from '../review-cycles/template-builder/services/template.service';
import {
  TemplateBuilderDrawerComponent, DrawerMode,
} from '../review-cycles/template-builder/components/template-builder-drawer/template-builder-drawer.component';
import { SkeletonComponent } from '../../shared/skeleton/skeleton.component';

@Component({
  selector: 'app-templates',
  standalone: true,
  imports: [CommonModule, FormsModule, TemplateBuilderDrawerComponent, SkeletonComponent],
  templateUrl: './templates.component.html',
  styleUrls: ['./templates.component.css'],
})
export class TemplatesComponent implements OnInit {
  readonly reviewTypeLabels = REVIEW_TYPE_LABELS;

  templates = signal<ReviewTemplate[]>([]);
  loading   = signal(true);
  error     = signal('');
  searchQuery = signal('');

  /** Templates filtered by search query (name, description, or type label). */
  filteredTemplates = computed(() => {
    const q = this.searchQuery().trim().toLowerCase();
    if (!q) return this.templates();
    return this.templates().filter(t => {
      const name  = (t.templateName ?? '').toLowerCase();
      const desc  = (t.description ?? '').toLowerCase();
      const type  = (this.reviewTypeLabels[t.reviewType] ?? '').toLowerCase();
      return name.includes(q) || desc.includes(q) || type.includes(q);
    });
  });

  drawerOpen = signal(false);
  drawerMode = signal<DrawerMode>('build');
  editingTemplate = signal<ReviewTemplate | undefined>(undefined);

  notification: { show: boolean; message: string; type: 'success' | 'error' } = {
    show: false, message: '', type: 'success',
  };

  confirmModal: {
    open: boolean;
    name: string;
    onConfirm: () => void;
  } = { open: false, name: '', onConfirm: () => {} };

  constructor(private svc: TemplateService) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set('');
    this.svc.getAll().subscribe({
      next: r => { this.templates.set(r); this.loading.set(false); },
      error: () => { this.error.set('Failed to load templates.'); this.loading.set(false); },
    });
  }

  openCreate(): void {
    this.editingTemplate.set(undefined);
    this.drawerMode.set('build');
    this.drawerOpen.set(true);
  }

  openEdit(t: ReviewTemplate): void {
    this.editingTemplate.set(t);
    this.drawerMode.set('edit');
    this.drawerOpen.set(true);
  }

  onDrawerClosed(): void {
    this.drawerOpen.set(false);
    this.editingTemplate.set(undefined);
  }

  onTemplateAttached(): void {
    const wasEdit = this.drawerMode() === 'edit';
    this.drawerOpen.set(false);
    this.editingTemplate.set(undefined);
    this.showNotification(
      wasEdit ? 'Template updated successfully.' : 'Template saved successfully.',
      'success',
    );
    this.load();
  }

  askDelete(t: ReviewTemplate): void {
    this.confirmModal = {
      open: true,
      name: t.templateName,
      onConfirm: () => this.doDelete(t),
    };
  }

  closeConfirm(): void {
    this.confirmModal = { ...this.confirmModal, open: false };
  }

  private doDelete(t: ReviewTemplate): void {
    const prev = this.templates();
    this.templates.set(prev.filter(x => x.templateId !== t.templateId));
    this.svc.delete(t.templateId).subscribe({
      next: () => this.showNotification(`"${t.templateName}" has been deleted.`, 'success'),
      error: err => {
        this.templates.set(prev);
        const msg = err?.error?.message ?? 'Failed to delete template. Please try again.';
        this.showNotification(msg, 'error');
      },
    });
  }

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
      day: 'numeric', month: 'numeric', year: 'numeric',
    });
  }

  showNotification(message: string, type: 'success' | 'error' = 'success'): void {
    this.notification = { show: true, message, type };
    setTimeout(() => { this.notification.show = false; }, 4000);
  }
}
