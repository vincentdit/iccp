package tz.go.tirdo.iccp.platform.security;

/** Role codes from Doc 03 §2.2 (seeded in sec.role by V4). */
public final class Roles {
    public static final String STAFF = "STAFF";
    public static final String LINE_MANAGER = "LINE_MANAGER";
    public static final String DIRECTOR = "DIRECTOR";
    public static final String EXECUTIVE = "EXECUTIVE";
    public static final String COMMS_PUBLISHER = "COMMS_PUBLISHER";
    public static final String COMMS_APPROVER = "COMMS_APPROVER";
    public static final String EMERGENCY_APPROVER = "EMERGENCY_APPROVER";
    public static final String FIN_OFFICER = "FIN_OFFICER";
    public static final String FIN_REVIEWER = "FIN_REVIEWER";
    public static final String HR_OFFICER = "HR_OFFICER";
    public static final String SYS_ADMIN = "SYS_ADMIN";
    public static final String SECURITY_ADMIN = "SECURITY_ADMIN";
    public static final String AUDITOR = "AUDITOR";

    private Roles() {
    }
}
