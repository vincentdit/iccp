package tz.go.tirdo.iccp.dir.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * One targeting rule (com.announcement_audience). Rules combine with OR.
 *
 * @param type  ALL, ORG_UNIT, ORG_UNIT_TREE, ROLE, STAFF_CATEGORY, DUTY_STATION or STAFF
 * @param value unit code, role code, category, duty station or staff public id; empty for ALL
 */
public record AudienceRule(
        @NotBlank @Pattern(regexp = "ALL|ORG_UNIT|ORG_UNIT_TREE|ROLE|STAFF_CATEGORY|DUTY_STATION|STAFF") String type,
        String value) {
}
