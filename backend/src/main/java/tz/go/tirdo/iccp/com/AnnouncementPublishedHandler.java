package tz.go.tirdo.iccp.com;

import org.springframework.stereotype.Component;
import tz.go.tirdo.iccp.platform.outbox.OutboxHandler;
import tz.go.tirdo.iccp.platform.outbox.OutboxMessage;

import java.util.UUID;

/** Fans out notifications after a notice is published or amended (BP-COM-01 step 5). */
@Component
public class AnnouncementPublishedHandler implements OutboxHandler {

    private final AnnouncementService service;

    public AnnouncementPublishedHandler(AnnouncementService service) {
        this.service = service;
    }

    @Override
    public String eventType() {
        return AnnouncementService.EVENT_PUBLISHED;
    }

    @Override
    public void handle(OutboxMessage m) {
        service.notifyRecipients(UUID.fromString(m.payload().get("publicId").asText()), m.payload().path("amended").asBoolean(false));
    }
}
