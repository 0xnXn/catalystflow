export type NotificationJobStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'SKIPPED';

export type NotificationTaskType =
  | 'AUTO_DEADLINE_REMINDER_DIGEST'
  | 'MANUAL_REMINDER'
  | 'REVIEW_PUBLISHED';

export type ManualReminderTargetType =
  | 'SELF_REVIEW_SUBJECT'
  | 'PEER_REVIEW_EMPLOYEE'
  | 'MANAGER_REVIEW_MANAGER';

export type ManualReminderSkippedReason =
  | 'ALREADY_REMINDED_TODAY'
  | 'NO_REVIEW_FOUND'
  | 'NOT_REMINDABLE'
  | 'FORBIDDEN';

export interface ManualReminderRequest {
  cycleId: string;
  targetType: ManualReminderTargetType;
  subjectUserIds: string[];
  message?: string;
}

export interface NotificationJobSummary {
  jobId?: string;
  taskType?: NotificationTaskType;
  status?: NotificationJobStatus;
  reviewId?: string | null;
  recipientUserId?: string;
  materialized?: boolean;
}

export interface ManualReminderSkippedItem {
  jobId?: string;
  reviewId?: string | null;
  recipientUserId?: string;
  reason?: ManualReminderSkippedReason;
  status?: NotificationJobStatus;
}

export interface ManualReminderResponse {
  created?: NotificationJobSummary[];
  skipped?: ManualReminderSkippedItem[];
}

export interface NotificationJobStatusResponse {
  jobId?: string;
  taskType?: NotificationTaskType;
  status?: NotificationJobStatus;
  errorMessage?: string | null;
  attemptCount?: number;
  result?: Record<string, unknown>;
  materialized?: boolean;
}
