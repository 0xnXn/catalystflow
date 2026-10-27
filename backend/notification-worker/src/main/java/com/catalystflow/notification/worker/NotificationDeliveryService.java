package com.catalystflow.notification.worker;

import com.catalystflow.notification.repository.NotificationJobRepository;
import com.catalystflow.notification.service.NotificationDomainLookup;
import com.catalystflow.notification.service.NotificationPayloads.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class NotificationDeliveryService {

    private static final Logger LOG = Logger.getLogger(NotificationDeliveryService.class);
    private static final Set<String> INCOMPLETE = Set.of("NOT_STARTED", "DRAFT", "IN_PROGRESS");
    private static final String LOGO_RESOURCE = "email/catalystflow-solutions-logo.jpg";
    private static final String LOGO_CONTENT_ID = "catalystflow-solutions-logo";

    @Inject
    NotificationJobRepository jobs;

    @Inject
    NotificationDomainLookup domain;

    @Inject
    ObjectMapper mapper;

    SesV2Client sesClient;

    @ConfigProperty(name = "notification.from-email")
    String fromEmail;

    @ConfigProperty(name = "notification.app-base-url")
    String appBaseUrl;

    @ConfigProperty(name = "notification.max-attempts")
    int maxAttempts;

    public void processDeliveryJob(String jobId) {
        NotificationJobRepository.JobData job = jobs.getJobData(jobId);
        if (job == null) {
            LOG.warnf("Notification job %s missing; dropping delivery message", jobId);
            return;
        }
        if (isTerminal(job.status())) {
            LOG.infof("Notification job %s already %s; skipping duplicate delivery", jobId, job.status());
            return;
        }
        if (!jobs.tryClaim(jobId, Instant.now())) return;

        try {
            DeliveryMessage message = buildMessage(job);
            if (message.skipReason() != null) {
                jobs.markSkipped(jobId, message.skipReason(), Instant.now());
                return;
            }

            SendEmailResponse response = ses().sendEmail(SendEmailRequest.builder()
                    .fromEmailAddress(fromEmail)
                    .destination(Destination.builder().toAddresses(message.to()).build())
                    .content(EmailContent.builder()
                            .raw(RawMessage.builder()
                                    .data(SdkBytes.fromByteArray(rawMessage(message)))
                                    .build())
                            .build())
                    .build());

            jobs.markCompleted(jobId, mapper.writeValueAsString(Map.of(
                    "sesMessageId", response.messageId(),
                    "sentAt", Instant.now().toString()
            )), Instant.now());
        } catch (SesV2Exception e) {
            handleFailure(job, e, isRetryable(e), e.awsErrorDetails().errorMessage());
        } catch (IllegalArgumentException e) {
            handleFailure(job, e, false, e.getMessage());
        } catch (Exception e) {
            handleFailure(job, e, true, e.getMessage());
        }
    }

    private DeliveryMessage buildMessage(NotificationJobRepository.JobData job) throws Exception {
        return switch (job.taskType()) {
            case "AUTO_DEADLINE_REMINDER_DIGEST" -> autoDigestMessage(mapper.readValue(
                    job.requestJson(), AutoDeadlineReminderDigestRequest.class));
            case "MANUAL_REMINDER" -> manualMessage(mapper.readValue(
                    job.requestJson(), ManualReminderJobRequest.class));
            case "REVIEW_PUBLISHED" -> publishedMessage(mapper.readValue(
                    job.requestJson(), ReviewPublishedRequest.class));
            default -> throw new IllegalArgumentException("Unknown taskType: " + job.taskType());
        };
    }

    private DeliveryMessage autoDigestMessage(AutoDeadlineReminderDigestRequest request) {
        String cycleTitle = hasText(request.cycleTitle()) ? request.cycleTitle() : "Review cycle";
        List<AutoDeadlineReminderDigestItem> reminders = request.reminders() == null
                ? List.of()
                : request.reminders();
        String textLines = reminders.stream()
                .map(item -> "- " + digestItemLine(item, request.sendDate()))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("- Review cycle has an upcoming reminder.");
        String htmlLines = reminders.stream()
                .map(item -> "<li>" + escapeHtml(digestItemLine(item, request.sendDate())) + "</li>")
                .reduce((left, right) -> left + "\n" + right)
                .orElse("<li>Review cycle has an upcoming reminder.</li>");
        String textBody = "Here are your upcoming CatalystFlow dates for " + cycleTitle + ":\n\n"
                + textLines
                + "\n\nOpen CatalystFlow: " + appBaseUrl;
        String htmlBody = """
                <p>Here are your upcoming CatalystFlow dates for %s.</p>
                <ul>%s</ul>
                <p><a href="%s">Open CatalystFlow</a></p>
                """.formatted(escapeHtml(cycleTitle), htmlLines, escapeHtml(appBaseUrl));
        return messageForUser(request.recipientUserId(),
                "CatalystFlow reminders for " + cycleTitle,
                textBody,
                htmlBody);
    }

    private DeliveryMessage manualMessage(ManualReminderJobRequest request) {
        if (request.reviewId() != null && !request.reviewId().isBlank()) {
            Optional<ReviewRecord> review = domain.review(request.reviewId());
            if (review.isEmpty() || !INCOMPLETE.contains(review.get().status())) {
                return DeliveryMessage.skip("Review is no longer incomplete");
            }
        } else if ("SELF_REVIEW_SUBJECT".equals(request.targetType())) {
            boolean selfReviewFinished = domain.reviewsForCycle(request.cycleId()).stream()
                    .filter(r -> request.subjectUserId().equals(r.subjectUserId()))
                    .filter(r -> "SELF".equals(r.reviewType()))
                    .anyMatch(r -> !INCOMPLETE.contains(r.status()));
            if (selfReviewFinished) {
                return DeliveryMessage.skip("Self review is no longer incomplete");
            }
        } else if ("MANAGER_REVIEW_MANAGER".equals(request.targetType())) {
            boolean managerReviewFinished = domain.reviewsForCycle(request.cycleId()).stream()
                    .filter(r -> request.subjectUserId().equals(r.subjectUserId()))
                    .filter(r -> request.recipientUserId().equals(r.authorUserId()))
                    .filter(r -> "MANAGER".equals(r.reviewType()))
                    .anyMatch(r -> !INCOMPLETE.contains(r.status()));
            if (managerReviewFinished) {
                return DeliveryMessage.skip("Manager review is no longer incomplete");
            }
        } else {
            return DeliveryMessage.skip("Review is no longer incomplete");
        }
        String note = request.message() == null || request.message().isBlank()
                ? ""
                : "\n\nMessage: " + request.message();
        String htmlNote = request.message() == null || request.message().isBlank()
                ? ""
                : "<p><strong>Message:</strong> " + escapeHtml(request.message()) + "</p>";
        return reminderMessage(request.recipientUserId(),
                "Review reminder",
                "You have a review task waiting in CatalystFlow. Please open CatalystFlow and complete it when you can."
                        + note + "\n\nOpen CatalystFlow: " + appBaseUrl,
                """
                <p>You have a review task waiting in CatalystFlow. Please open CatalystFlow and complete it when you can.</p>
                %s
                <p><a href="%s">Open CatalystFlow</a></p>
                """.formatted(htmlNote, escapeHtml(appBaseUrl)));
    }

    private DeliveryMessage publishedMessage(ReviewPublishedRequest request) {
        boolean bundlePublished = domain.reviewsForCycle(request.cycleId()).stream()
                .filter(r -> request.subjectUserId().equals(r.subjectUserId()))
                .anyMatch(r -> "PUBLISHED".equals(r.status()));
        if (!bundlePublished) return DeliveryMessage.skip("Review bundle is not published");
        return messageForUser(request.recipientUserId(),
                "Your review has been published",
                "Your review is now available in CatalystFlow.\n\nOpen CatalystFlow: " + appBaseUrl,
                """
                <p>Your review is now available in CatalystFlow.</p>
                <p><a href="%s">Open CatalystFlow</a></p>
                """.formatted(escapeHtml(appBaseUrl)));
    }

    private DeliveryMessage reminderMessage(String recipientUserId, String subject,
                                            String textBody, String htmlBody) {
        return messageForUser(recipientUserId, subject, textBody, htmlBody);
    }

    private String digestItemLine(AutoDeadlineReminderDigestItem item, String sendDateValue) {
        String name = hasText(item.milestoneName()) ? item.milestoneName() : "Review cycle";
        String timing = timingPhrase(sendDateValue, item.targetDate());
        return switch (item.deadlineEvent()) {
            case "CYCLE_START" -> "Review cycle starts " + timing;
            case "CYCLE_END" -> "Review cycle is due " + timing;
            case "MILESTONE_START" -> name + " starts " + timing;
            case "MILESTONE_END" -> name + " is due " + timing;
            default -> name + " has an upcoming review event " + timing;
        };
    }

    private String timingPhrase(String sendDateValue, String targetDateValue) {
        if (!hasText(targetDateValue)) return "soon";
        try {
            LocalDate sendDate = hasText(sendDateValue) ? LocalDate.parse(sendDateValue) : LocalDate.now();
            LocalDate targetDate = LocalDate.parse(targetDateValue);
            long days = ChronoUnit.DAYS.between(sendDate, targetDate);
            if (days == 0) return "today";
            if (days == 1) return "tomorrow, on " + targetDateValue;
            if (days > 1) return "in " + days + " days, on " + targetDateValue;
            if (days == -1) return "yesterday, on " + targetDateValue;
            return Math.abs(days) + " days ago, on " + targetDateValue;
        } catch (Exception e) {
            return "on " + targetDateValue;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private SesV2Client ses() {
        if (sesClient == null) sesClient = SesV2Client.create();
        return sesClient;
    }

    private DeliveryMessage messageForUser(String recipientUserId, String subject, String textBody, String htmlBody) {
        Optional<UserRecord> user = domain.user(recipientUserId);
        if (user.isEmpty()) return DeliveryMessage.skip("Recipient user not found");
        if (!"Active".equalsIgnoreCase(user.get().status())) return DeliveryMessage.skip("Recipient user is inactive");
        if (user.get().email() == null || user.get().email().isBlank()) {
            throw new IllegalArgumentException("Recipient email is missing");
        }
        return new DeliveryMessage(user.get().email(), subject, textBody, htmlBody, null);
    }

    private byte[] rawMessage(DeliveryMessage message) throws IOException {
        String relatedBoundary = "catalystflow-related-" + System.nanoTime();
        String alternativeBoundary = "catalystflow-alternative-" + System.nanoTime();
        byte[] logo = logoBytes();
        String htmlBody = brandedHtml(message.htmlBody(), logo.length > 0);
        StringBuilder raw = new StringBuilder();
        raw.append("From: ").append(headerValue(fromEmail)).append("\r\n");
        raw.append("To: ").append(headerValue(message.to())).append("\r\n");
        raw.append("Subject: ").append(encodedHeader(message.subject())).append("\r\n");
        raw.append("MIME-Version: 1.0\r\n");
        raw.append("Content-Type: multipart/related; boundary=\"").append(relatedBoundary).append("\"\r\n\r\n");
        raw.append("--").append(relatedBoundary).append("\r\n");
        raw.append("Content-Type: multipart/alternative; boundary=\"").append(alternativeBoundary).append("\"\r\n\r\n");
        raw.append("--").append(alternativeBoundary).append("\r\n");
        appendBase64Part(raw, "text/plain; charset=UTF-8", message.textBody());
        raw.append("--").append(alternativeBoundary).append("\r\n");
        appendBase64Part(raw, "text/html; charset=UTF-8", htmlBody);
        raw.append("--").append(alternativeBoundary).append("--\r\n");
        if (logo.length > 0) {
            raw.append("--").append(relatedBoundary).append("\r\n");
            raw.append("Content-Type: image/jpeg; name=\"catalystflow-solutions-logo.jpg\"\r\n");
            raw.append("Content-Transfer-Encoding: base64\r\n");
            raw.append("Content-ID: <").append(LOGO_CONTENT_ID).append(">\r\n");
            raw.append("Content-Disposition: inline; filename=\"catalystflow-solutions-logo.jpg\"\r\n\r\n");
            raw.append(base64(logo)).append("\r\n");
        }
        raw.append("--").append(relatedBoundary).append("--\r\n");
        return raw.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void appendBase64Part(StringBuilder raw, String contentType, String content) {
        raw.append("Content-Type: ").append(contentType).append("\r\n");
        raw.append("Content-Transfer-Encoding: base64\r\n\r\n");
        raw.append(base64(content.getBytes(StandardCharsets.UTF_8))).append("\r\n");
    }

    private String brandedHtml(String innerHtml, boolean includeLogo) {
        String logo = includeLogo
                ? "<div style=\"padding-top:24px;text-align:left;\"><img src=\"cid:" + LOGO_CONTENT_ID + "\" alt=\"CatalystFlow Solutions\" style=\"display:block;width:180px;max-width:100%;height:auto;\"></div>"
                : "";
        return """
                <!doctype html>
                <html>
                <body style="margin:0;padding:0;background:#f6f7f9;font-family:Arial,Helvetica,sans-serif;color:#1f2937;">
                  <div style="max-width:640px;margin:0 auto;padding:28px 20px;">
                    <div style="background:#ffffff;border:1px solid #e5e7eb;border-radius:8px;padding:24px;line-height:1.5;font-size:15px;">
                      %s
                      %s
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(innerHtml, logo);
    }

    private byte[] logoBytes() throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(LOGO_RESOURCE)) {
            if (in == null) return new byte[0];
            return in.readAllBytes();
        }
    }

    private String encodedHeader(String value) {
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private String headerValue(String value) {
        return value == null ? "" : value.replace("\r", "").replace("\n", "");
    }

    private String base64(byte[] bytes) {
        return Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.UTF_8)).encodeToString(bytes);
    }

    private String escapeHtml(String value) {
        if (value == null) return "";
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private void handleFailure(NotificationJobRepository.JobData job, Exception error,
                               boolean retryable, String message) {
        int nextAttempt = job.attemptCount() + 1;
        if (retryable && nextAttempt < maxAttempts) {
            jobs.markRetryableFailure(job.jobId(), nextAttempt, message, Instant.now());
            throw new RuntimeException(error);
        }
        jobs.markFailed(job.jobId(), message == null ? error.getClass().getSimpleName() : message, Instant.now());
    }

    private boolean isRetryable(SesV2Exception e) {
        int status = e.statusCode();
        return status == 429 || status >= 500;
    }

    private boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "SKIPPED".equals(status);
    }

    private record DeliveryMessage(String to, String subject, String textBody, String htmlBody, String skipReason) {
        static DeliveryMessage skip(String reason) {
            return new DeliveryMessage(null, null, null, null, reason);
        }
    }
}
