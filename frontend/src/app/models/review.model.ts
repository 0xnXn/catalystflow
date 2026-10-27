export type QuestionType =
  | 'text' | 'TEXT'
  | 'rating' | 'RATING'
  | 'yes_no' | 'YES_NO'
  | 'multi_select' | 'MULTI_SELECT' | 'CHECKBOX'
  | 'single_select' | 'SINGLE_SELECT';

export type ReviewStatus = 'NOT_STARTED' | 'DRAFT' | 'IN_PROGRESS' | 'COMPLETED' | 'PUBLISHED' | 'PENDING' | 'LOCKED';

export type ReviewType = 'SELF' | 'PEER_360' | 'MANAGER';

export interface Question {
  questionId: string;
  text: string;
  type: QuestionType;
  category?: string;
  placeholder?: string;
  /** Guidance describing what a strong answer looks like. Not shown to the employee — used later by AI/scoring. */
  expectedResponse?: string;
  required?: boolean;
  scale?: number;
  options?: string[];
  order?: number;
}

export interface ReviewTemplate {
  templateId: string;
  title?: string;
  templateName?: string;
  questions: Question[];
}

export interface Answer {
  questionId: string;
  response: string | number | string[];
}

export interface Review {
  reviewId?: string;
  cycleId: string;
  milestoneId?: string;
  templateId?: string;
  subjectUserId?: string;
  authorUserId?: string;
  userId: string;
  reviewType: ReviewType;
  status: ReviewStatus;
  version?: number;
  overallRating?: string | null;
  overallComment?: string | null;
  submittedAt?: string | null;
  updatedAt?: string | null;
  insightsJobId?: string | null;
  /** Cached ANALYZE job pointer — persisted on the self-review row so refresh
   *  rehydrates the "Get Suggestions" output instead of forcing a re-run. */
  analyzeJobId?: string | null;
  /** Cached PEER_360_DIGEST job pointer — persisted on the MANAGER review row
   *  so the digest tab doesn't re-fire Gemini on every page load (ADR-6). */
  peer360DigestJobId?: string | null;
  completedByAdmin?: string | null;
  /** Cached MANAGER_GOLDEN job pointer — same pattern as peer360DigestJobId (ADR-6). */
  managerGoldenJobId?: string | null;
  // managerGoldenReview is intentionally NOT here — golden review payloads
  // live in AiJobs (taskType=MANAGER_GOLDEN), not on the Review row. Trigger
  // a fresh generation + poll via AiReviewService.startManagerGolden +
  // pollManagerGolden when the manager opens the preview.
  answers: Answer[];
}

export interface ReviewGuidanceResponse {
  missingAreas: string[];
  thinAreas: string[];
  wellCoveredAreas: string[];
  overallGuidance: string;
}

export interface GoldenCitation {
  source: 'self' | 'peer_360';
  reviewId: string;
  questionId?: string;
  /** Verbatim substring from the cited answer; UI uses to locate + highlight. */
  quote?: string;
  /** Manager-only — anonymous to subject. Required for peer_360. */
  authorUserId?: string;
}

export interface ManagerGoldenReview {
  technologyRating: number;
  technologyComments: string;
  consultingRating: number;
  consultingComments: string;
  companyValuesComments: string;
  developmentOpportunities: string;
  otherComments: string;
  overallRating: number;
  /** Optional map of citation key (e.g. "c1") → source pointer. ADR-3. */
  citations?: Record<string, GoldenCitation>;
}
