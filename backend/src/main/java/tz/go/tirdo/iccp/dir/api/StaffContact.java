package tz.go.tirdo.iccp.dir.api;

/** Delivery details used by the notification engine. */
public record StaffContact(long staffId, String displayName, String email, String mobilePhone, String language, boolean active) {
}
