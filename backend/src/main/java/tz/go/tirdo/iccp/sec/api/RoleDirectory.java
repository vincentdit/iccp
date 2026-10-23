package tz.go.tirdo.iccp.sec.api;

import java.util.Set;

/** Published SEC interface: who holds a role (assigned roles plus roles derived from the directory). */
public interface RoleDirectory {

    /** Active staff holding the role today. */
    Set<Long> staffWithRole(String roleCode);

    Set<String> rolesOf(long staffId);
}
