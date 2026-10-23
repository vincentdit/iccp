package tz.go.tirdo.iccp.fin;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import tz.go.tirdo.iccp.ntf.api.NotificationRequest;
import tz.go.tirdo.iccp.ntf.api.Notifier;
import tz.go.tirdo.iccp.ntf.api.NtfCategory;
import tz.go.tirdo.iccp.platform.outbox.OutboxHandler;
import tz.go.tirdo.iccp.platform.outbox.OutboxMessage;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Tells staff that their Financial Diary has changed (FR-FIN-09). The message never contains amounts
 * or claim details (NTF-08): staff sign in to see them.
 */
@Component
public class FinanceBatchPublishedHandler implements OutboxHandler {

    private final Notifier notifier;

    public FinanceBatchPublishedHandler(Notifier notifier) {
        this.notifier = notifier;
    }

    @Override
    public String eventType() {
        return FinanceBatchService.BATCH_PUBLISHED;
    }

    @Override
    public void handle(OutboxMessage m) {
        List<Long> staff = new ArrayList<>();
        for (JsonNode n : m.payload().path("staffIds")) {
            staff.add(n.asLong());
        }
        notifier.notify(new NotificationRequest(staff, "PaymentPublished", NtfCategory.FINANCE, Map.of(), "/finance",
                EnumSet.of(NotificationRequest.Channel.EMAIL, NotificationRequest.Channel.SMS), "FIN", m.aggregateId()));
    }
}
