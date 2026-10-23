package tz.go.tirdo.iccp.ntf.api;

import java.util.UUID;

/**
 * Published NTF interface (M11). Modules describe what happened; NTF decides who is reached on
 * which channel, using bilingual templates, preferences and quiet hours (BP-NTF-01).
 */
public interface Notifier {

    /** Creates in-app notifications now and queues email/SMS deliveries. Call inside the caller's transaction. */
    void notify(NotificationRequest request);

    /** How many notifications of this type about this record a person has already received (for reminders). */
    long countSent(long staffId, String eventType, UUID sourcePublicId);

    /** Marks a person's notifications about a record as read when they open the record itself. */
    void markReadForSource(long staffId, UUID sourcePublicId);
}
