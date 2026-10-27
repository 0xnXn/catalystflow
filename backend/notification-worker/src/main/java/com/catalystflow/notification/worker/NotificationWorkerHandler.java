package com.catalystflow.notification.worker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.catalystflow.notification.service.NotificationPayloads.NotificationIntent;
import com.catalystflow.notification.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

@Named("notificationWorker")
@ApplicationScoped
public class NotificationWorkerHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    private static final Logger LOG = Logger.getLogger(NotificationWorkerHandler.class);

    @Inject
    NotificationService notificationService;

    @Inject
    NotificationDeliveryService deliveryService;

    @Inject
    ObjectMapper mapper;

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();

        for (SQSEvent.SQSMessage msg : event.getRecords()) {
            try {
                String body = msg.getBody();
                if (body != null && body.trim().startsWith("{")) {
                    notificationService.handleIntent(mapper.readValue(body, NotificationIntent.class));
                } else {
                    deliveryService.processDeliveryJob(body);
                }
            } catch (RuntimeException e) {
                // SQS is at-least-once. Only infrastructure/retryable delivery
                // failures bubble out here so SQS redelivers the same message.
                LOG.errorf(e, "Notification worker failed for message %s", msg.getMessageId());
                failures.add(SQSBatchResponse.BatchItemFailure.builder()
                        .withItemIdentifier(msg.getMessageId())
                        .build());
            } catch (Exception e) {
                LOG.errorf(e, "Malformed notification message %s; dropping", msg.getMessageId());
            }
        }

        return SQSBatchResponse.builder().withBatchItemFailures(failures).build();
    }
}
