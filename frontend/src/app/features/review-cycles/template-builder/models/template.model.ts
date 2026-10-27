export type QuestionType = 'TEXT' | 'RATING' | 'CHECKBOX' | 'SINGLE_SELECT' | 'MULTI_SELECT';

export type ReviewType =
  | 'SELF'
  | 'PEER_360'
  | 'MANAGER';

export const REVIEW_TYPES: ReviewType[] = [
  'SELF',
  'PEER_360',
  'MANAGER',
];

export const REVIEW_TYPE_LABELS: Record<ReviewType, string> = {
  SELF:     'Self Review',
  PEER_360: 'Peer 360 Review',
  MANAGER:  'Manager Review',
};

export const QUESTION_TYPE_LABELS: Record<QuestionType, string> = {
  TEXT:          'Long Text',
  RATING:        'Star Rating',
  CHECKBOX:      'Checkboxes',
  SINGLE_SELECT: 'Single Choice',
  MULTI_SELECT:  'Multi Select',
};

export interface TemplateQuestion {
  questionId: string;
  text: string;
  type: QuestionType;
  options: string[];
  required: boolean;
  order: number;
  idealResponse?: string;
}

/** Frozen copy stored on a milestone — never updates after attachment. */
export interface TemplateSnapshot {
  templateId?: string;
  templateName?: string;
  reviewType: ReviewType;
  description?: string;
  questions: TemplateQuestion[];
  snapshotCreatedAt: string;
}

export interface ReviewTemplate {
  templateId: string;
  templateName: string;
  reviewType: ReviewType;
  description?: string;
  questions: TemplateQuestion[];
  createdAt: string;
  updatedAt: string;
  lastUsedAt?: string;
  usageCount?: number;
}

export interface CreateReviewTemplateRequest {
  templateName: string;
  reviewType: ReviewType;
  description?: string;
  questions: TemplateQuestion[];
}

export interface ReviewTemplateListResponse {
  templates: ReviewTemplate[];
  count: number;
}

export function needsOptions(type: QuestionType): boolean {
  return ['CHECKBOX', 'SINGLE_SELECT', 'MULTI_SELECT'].includes(type);
}

export function makeQuestion(type: QuestionType, order: number): TemplateQuestion {
  return {
    questionId: crypto.randomUUID(),
    text:       '',
    type,
    options:    needsOptions(type) ? ['Option 1', 'Option 2'] : [],
    required:   true,
    order,
  };
}
