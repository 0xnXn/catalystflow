package com.catalystflow.ai.service;

/**
 * Thrown when an AI-narrative mutation (regenerate or edit) is attempted on a
 * review that is in a terminal state (COMPLETED or PUBLISHED). Once a manager
 * review is submitted/published its AI narrative is frozen — see BUG-001 / OBS-B
 * in bug-investigation-2026-05-31.md. Mapped by the resource layer to HTTP 409
 * Conflict.
 */
public class ReviewLockedException extends RuntimeException {
    public ReviewLockedException(String message) {
        super(message);
    }
}
