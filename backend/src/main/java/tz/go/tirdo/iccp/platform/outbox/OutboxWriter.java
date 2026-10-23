package tz.go.tirdo.iccp.platform.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Writes a domain event in the same transaction as the business change (transactional outbox,
 * Doc 05 ADR-06). If the change rolls back, so does the event; if it commits, the event will be
 * dispatched even after a restart.
 */
@Component
public class OutboxWriter {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public OutboxWriter(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Event payload is not serialisable", e);
        }
        jdbc.sql("""
                INSERT INTO integ.outbox_event (aggregate_type, aggregate_id, event_type, payload)
                VALUES (:type, :id, :event, CAST(:payload AS jsonb))
                """)
                .param("type", aggregateType)
                .param("id", aggregateId)
                .param("event", eventType)
                .param("payload", json)
                .update();
    }
}
