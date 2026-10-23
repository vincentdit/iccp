package tz.go.tirdo.iccp.platform.outbox;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A domain event read back from integ.outbox_event for dispatch. */
public record OutboxMessage(long id, String aggregateType, UUID aggregateId, String eventType, JsonNode payload, OffsetDateTime createdAt) {
}
