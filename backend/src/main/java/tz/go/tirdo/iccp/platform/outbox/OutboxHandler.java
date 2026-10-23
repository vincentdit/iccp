package tz.go.tirdo.iccp.platform.outbox;

/**
 * Reacts to a domain event after it has been committed. Handlers run in their own transaction
 * under a system database context; they must be idempotent because an event can be delivered
 * again if the process stops between handling and marking it dispatched.
 */
public interface OutboxHandler {

    /** Event type this handler accepts, e.g. {@code AnnouncementPublished}. */
    String eventType();

    void handle(OutboxMessage message);
}
