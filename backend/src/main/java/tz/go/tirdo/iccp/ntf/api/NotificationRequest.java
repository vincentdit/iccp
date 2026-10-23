package tz.go.tirdo.iccp.ntf.api;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What to tell whom.
 *
 * @param recipients     staff ids
 * @param eventType      template key in ntf.template, e.g. {@code PaymentPublished}
 * @param category       preference category (see {@link NtfCategory})
 * @param params         template values — never amounts or confidential text (NTF-08)
 * @param deepLink       ICCP route the notification opens, e.g. {@code /finance/claims/<id>}
 * @param channels       external channels wanted in addition to in-app
 * @param sourceModule   three-letter module code
 * @param sourcePublicId record the notification is about
 */
public record NotificationRequest(Collection<Long> recipients, String eventType, String category, Map<String, String> params,
                                  String deepLink, Set<Channel> channels, String sourceModule, UUID sourcePublicId) {

    public enum Channel { EMAIL, SMS }

    public static NotificationRequest inApp(Collection<Long> recipients, String eventType, String category, Map<String, String> params,
                                            String deepLink, String sourceModule, UUID sourcePublicId) {
        return new NotificationRequest(recipients, eventType, category, params, deepLink, Set.of(), sourceModule, sourcePublicId);
    }
}
