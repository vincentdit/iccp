package tz.go.tirdo.iccp.platform.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tz.go.tirdo.iccp.platform.db.DbSessionContext;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Delivers committed outbox events to their handlers. Each event is claimed with
 * {@code FOR UPDATE SKIP LOCKED}, so several application nodes can run the dispatcher safely.
 * A failing event is retried with growing back-off (up to 10 minutes) without blocking the
 * events behind it; failures are logged for the ICTT daily review.
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final Map<String, List<OutboxHandler>> handlers;
    private final Map<Long, Failure> failures = new ConcurrentHashMap<>();

    public OutboxDispatcher(JdbcClient jdbc, TransactionTemplate tx, ObjectMapper mapper, List<OutboxHandler> all) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.mapper = mapper;
        this.handlers = all.stream().collect(Collectors.groupingBy(OutboxHandler::eventType));
    }

    @Scheduled(fixedDelayString = "${iccp.outbox.poll-ms:2000}")
    public void dispatch() {
        DbSessionContext.runAs(DbSessionContext.system("outbox"), () -> {
            for (int i = 0; i < 100; i++) {
                Long[] skip = backingOff();
                Row row;
                try {
                    row = tx.execute(status -> dispatchOne(skip));
                } catch (HandlerFailed e) {
                    Failure f = failures.merge(e.eventId, new Failure(1, OffsetDateTime.now()), (a, b) -> new Failure(a.count + 1, OffsetDateTime.now()));
                    log.error("Outbox event {} ({}) failed {} time(s); will retry after back-off", e.eventId, e.eventType, f.count, e.getCause());
                    continue;
                }
                if (row == null) {
                    break;
                }
                failures.remove(row.id());
            }
        });
    }

    /** Ids still within their back-off window (1, 2, 4 … minutes, max 10). */
    private Long[] backingOff() {
        OffsetDateTime now = OffsetDateTime.now();
        List<Long> ids = new ArrayList<>();
        failures.forEach((id, f) -> {
            long minutes = Math.min(10, 1L << Math.min(f.count - 1, 4));
            if (f.at.plus(Duration.ofMinutes(minutes)).isAfter(now)) {
                ids.add(id);
            }
        });
        ids.add(-1L);
        return ids.toArray(Long[]::new);
    }

    private Row dispatchOne(Long[] skip) {
        List<Row> rows = jdbc.sql("""
                SELECT id, aggregate_type, aggregate_id, event_type, payload::text AS payload, created_at
                  FROM integ.outbox_event
                 WHERE published_at IS NULL AND id NOT IN (:skip)
                 ORDER BY id
                 LIMIT 1
                 FOR UPDATE SKIP LOCKED
                """).param("skip", java.util.Arrays.asList(skip)).query(Row.class).list();
        if (rows.isEmpty()) {
            return null;
        }
        Row r = rows.get(0);
        try {
            OutboxMessage msg = new OutboxMessage(r.id(), r.aggregateType(), r.aggregateId(), r.eventType(), mapper.readTree(r.payload()), r.createdAt());
            for (OutboxHandler h : handlers.getOrDefault(r.eventType(), List.of())) {
                h.handle(msg);
            }
        } catch (Exception e) {
            throw new HandlerFailed(r.id(), r.eventType(), e);
        }
        jdbc.sql("UPDATE integ.outbox_event SET published_at = now() WHERE id = :id").param("id", r.id()).update();
        return r;
    }

    record Row(long id, String aggregateType, UUID aggregateId, String eventType, String payload, OffsetDateTime createdAt) {
    }

    private record Failure(int count, OffsetDateTime at) {
    }

    private static final class HandlerFailed extends RuntimeException {
        final long eventId;
        final String eventType;

        HandlerFailed(long eventId, String eventType, Throwable cause) {
            super(cause);
            this.eventId = eventId;
            this.eventType = eventType;
        }
    }
}
