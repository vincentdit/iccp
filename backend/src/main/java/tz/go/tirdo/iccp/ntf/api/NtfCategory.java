package tz.go.tirdo.iccp.ntf.api;

import java.util.List;
import java.util.Set;

/** Notification preference categories (FR-NTF-04). Mandatory categories cannot be switched off. */
public final class NtfCategory {

    public static final String COMMUNICATION = "COMMUNICATION";
    public static final String EMERGENCY = "EMERGENCY";
    public static final String FINANCE = "FINANCE";
    public static final String APPROVALS = "APPROVALS";
    public static final String DIRECTORY = "DIRECTORY";
    public static final String SECURITY = "SECURITY";

    public static final List<String> ALL = List.of(COMMUNICATION, EMERGENCY, FINANCE, APPROVALS, DIRECTORY, SECURITY);
    public static final Set<String> MANDATORY = Set.of(EMERGENCY, SECURITY);

    private NtfCategory() {
    }

    public static boolean isMandatory(String category) {
        return MANDATORY.contains(category);
    }
}
