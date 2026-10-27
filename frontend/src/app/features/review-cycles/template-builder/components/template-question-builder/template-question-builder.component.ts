import { Component, Input, Output, EventEmitter, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  CdkDropList, CdkDrag, CdkDragDrop, CdkDragHandle, moveItemInArray
} from '@angular/cdk/drag-drop';
import * as XLSX from 'xlsx';
import {
  TemplateQuestion, QuestionType,
  QUESTION_TYPE_LABELS, needsOptions, makeQuestion
} from '../../models/template.model';

@Component({
  selector: 'app-template-question-builder',
  standalone: true,
  imports: [CommonModule, FormsModule, CdkDropList, CdkDrag, CdkDragHandle],
  templateUrl: './template-question-builder.component.html',
  styleUrl: './template-question-builder.component.css'
})
export class TemplateQuestionBuilderComponent implements OnInit {
  @Input() initialQuestions: TemplateQuestion[] = [];
  @Output() questionsChange = new EventEmitter<TemplateQuestion[]>();
  @Output() next = new EventEmitter<TemplateQuestion[]>();

  questions = signal<TemplateQuestion[]>([]);

  readonly questionTypes: QuestionType[] = ['TEXT', 'RATING', 'CHECKBOX', 'SINGLE_SELECT', 'MULTI_SELECT'];
  readonly typeLabels = QUESTION_TYPE_LABELS;
  readonly needsOptions = needsOptions;

  idealOpen: Record<string, boolean> = {};

  ngOnInit(): void {
    this.questions.set(this.initialQuestions.length ? [...this.initialQuestions] : []);
  }

  private emit(): void {
    this.questionsChange.emit([...this.questions()]);
  }

  addQuestion(type: QuestionType): void {
    this.questions.update(qs => {
      const q = makeQuestion(type, qs.length + 1);
      return [...qs, q];
    });
    this.emit();
  }

  removeQuestion(id: string): void {
    this.questions.update(qs =>
      qs.filter(q => q.questionId !== id).map((q, i) => ({ ...q, order: i + 1 }))
    );
    this.emit();
  }

  clearAll(): void {
    if (confirm('Remove all questions? This cannot be undone.')) {
      this.questions.set([]);
      this.emit();
    }
  }

  onQuestionChange(q: TemplateQuestion): void {
    this.questions.update(qs => qs.map(x => x.questionId === q.questionId ? { ...q } : x));
    this.emit();
  }

  onTypeChange(q: TemplateQuestion, newType: QuestionType): void {
    const updated: TemplateQuestion = { ...q, type: newType };
    if (needsOptions(newType) && (!q.options || q.options.length === 0)) {
      updated.options = ['Option 1', 'Option 2'];
    }
    if (!needsOptions(newType)) updated.options = [];
    this.questions.update(qs => qs.map(x => x.questionId === q.questionId ? updated : x));
    this.emit();
  }

  addOption(q: TemplateQuestion): void {
    const opts = q.options || [];
    const updated = { ...q, options: [...opts, `Option ${opts.length + 1}`] };
    this.questions.update(qs => qs.map(x => x.questionId === q.questionId ? updated : x));
    this.emit();
  }

  removeOption(q: TemplateQuestion, index: number): void {
    const opts = [...(q.options || [])];
    opts.splice(index, 1);
    const updated = { ...q, options: opts };
    this.questions.update(qs => qs.map(x => x.questionId === q.questionId ? updated : x));
    this.emit();
  }

  onOptionChange(q: TemplateQuestion, index: number, value: string): void {
    const opts = [...(q.options || [])];
    opts[index] = value;
    const updated = { ...q, options: opts };
    this.questions.update(qs => qs.map(x => x.questionId === q.questionId ? updated : x));
    this.emit();
  }

  onDrop(event: CdkDragDrop<TemplateQuestion[]>): void {
    const list = [...this.questions()];
    moveItemInArray(list, event.previousIndex, event.currentIndex);
    this.questions.set(list.map((q, i) => ({ ...q, order: i + 1 })));
    this.emit();
  }

  toggleIdeal(id: string): void {
    this.idealOpen[id] = !this.idealOpen[id];
  }

  onFileSelected(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = (e) => {
      const wb    = XLSX.read(e.target?.result, { type: 'binary' });
      const sheet = wb.Sheets[wb.SheetNames[0]];
      const rows: any[] = XLSX.utils.sheet_to_json(sheet);
      const currentCount = this.questions().length;
      const imported: TemplateQuestion[] = rows.map((row, i) => ({
        questionId:    crypto.randomUUID(),
        text:          row['Question']     || '',
        type:          (row['Type']        || 'TEXT').toUpperCase() as QuestionType,
        options:       row['Options']
                         ? String(row['Options']).split(',').map((s: string) => s.trim())
                         : [],
        required:      String(row['Required'] || 'yes').toLowerCase() === 'yes',
        order:         currentCount + i + 1,
        idealResponse: row['Ideal Response'] || undefined,
      }));
      this.questions.update(qs => [...qs, ...imported].map((q, i) => ({ ...q, order: i + 1 })));
      this.emit();
    };
    reader.readAsBinaryString(file);
    (event.target as HTMLInputElement).value = '';
  }

  trackById(_: number, q: TemplateQuestion): string { return q.questionId; }
  trackByIdx(i: number): number { return i; }

  onNext(): void {
    if (this.questions().length === 0) return;
    this.next.emit([...this.questions()]);
  }
}
