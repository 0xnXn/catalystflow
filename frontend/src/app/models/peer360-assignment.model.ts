import { Review } from './review.model';

/**
 * Request body for `POST /cycles/{cycleId}/peer360-assignments`.
 * Idempotent on (subjectUserId, authorUserId).
 */
export interface CreatePeer360AssignmentsRequest {
  subjectUserId: string;
  authorUserIds: string[];
  templateId: string;
}

/**
 * Response body for `POST /cycles/{cycleId}/peer360-assignments`.
 * `created` are newly-inserted PEER_360 review rows.
 * `skipped` are pre-existing rows for repeated (subject, author) pairs.
 * `all` is the union — useful for refreshing the UI in one render.
 */
export interface Peer360AssignmentResult {
  created: Review[];
  skipped: Review[];
  all: Review[];
}
