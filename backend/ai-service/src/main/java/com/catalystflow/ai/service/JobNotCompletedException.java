package com.catalystflow.ai.service;

/**
 * Thrown by {@link AiReviewService#updateManagerGoldenResult} when a manager
 * tries to edit a job result whose row is not COMPLETED (the DynamoDB
 * conditional update on {@code status = COMPLETED} failed).
 *
 * <p>The resource layer maps this to HTTP <b>409 Conflict</b> — distinct from
 * the <b>400</b> a shape-validation failure produces — so a correct client can
 * tell "your edit body is malformed" (400) from "the job isn't ready to be
 * edited yet" (409). Both javadocs on the endpoint promise this 409; before
 * this type existed the conditional failure was re-thrown as a BadRequest and
 * surfaced as a 400, contradicting the contract.
 */
public class JobNotCompletedException extends RuntimeException {
    public JobNotCompletedException(String message) {
        super(message);
    }
}
