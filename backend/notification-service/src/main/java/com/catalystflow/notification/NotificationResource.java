package com.catalystflow.notification;

import com.catalystflow.notification.api.NotificationsApi;
import com.catalystflow.notification.model.ManualReminderRequest;
import com.catalystflow.notification.model.MessageResponse;
import com.catalystflow.notification.model.NotificationJobStatusResponse;
import com.catalystflow.notification.service.NotificationService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Path("/notifications")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class NotificationResource extends BaseResource implements NotificationsApi {

    private static final Logger LOG = Logger.getLogger(NotificationResource.class);
    private static final ZoneId AU_ZONE = ZoneId.of("Australia/Sydney");

    @Inject
    NotificationService notificationService;

    @POST
    @Path("/reminders/manual")
    @Override
    public Response sendManualReminder(ManualReminderRequest request) {
        try {
            return Response.accepted(notificationService.sendManualReminder(
                    request, getUserId(), isAdmin(), isManager())).build();
        } catch (BadRequestException e) {
            return badRequest(e.getMessage());
        } catch (ForbiddenException e) {
            return forbidden(e.getMessage());
        } catch (Exception e) {
            LOG.error("Failed to create manual reminder notification", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new MessageResponse().message("Failed to create manual reminder"))
                    .build();
        }
    }

    @POST
    @Path("/maintenance/daily")
    @Override
    public Response runNotificationDailyMaintenance() {
        try {
            LocalDate todayAu = ZonedDateTime.now(AU_ZONE).toLocalDate();
            notificationService.runDailyMaintenance(todayAu);
            return Response.ok(new MessageResponse().message("Notification maintenance complete for date: " + todayAu)).build();
        } catch (Exception e) {
            LOG.error("Failed to run notification daily maintenance", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new MessageResponse().message("Notification maintenance failed: " + e.getMessage()))
                    .build();
        }
    }

    @GET
    @Path("/jobs/{jobId}/status")
    @Override
    public Response getNotificationJobStatus(@PathParam("jobId") String jobId) {
        NotificationJobStatusResponse status = notificationService.getStatus(jobId);
        return status == null ? notFound("Notification job not found") : Response.ok(status).build();
    }
}
