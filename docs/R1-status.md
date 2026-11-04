# Release 1 — status and known gaps

Verified in the build environment on 4 November 2026:

* Database: V1–V7 migrate cleanly on PostgreSQL 16; 50 of 50 integrity checks pass; audit chain verifies.
* Backend: Spring Boot 3.5.16 compiles on Java 21; `mvn verify` passes all 15 unit tests; API smoke test 57 of 57.
* Front end: production build succeeds; an automated browser walkthrough (write circular → approve → acknowledge →
  raise claim query → Finance answers → reviewer publishes import → staff confirms) completes with no script errors;
  screens checked in light, dark, Kiswahili and at phone width.

Known gaps to close before go-live:

1. **SMS gateway** is a logging stub; connect the government SMS gateway (Doc 03 §6.5).
2. **Notification text language** is fixed when the notification is created; switching language later does not
   translate earlier inbox items.
3. **Doc 06 updates to record:** `ntf.notification.params` (V6) and the COM approval workflow seed (V7).
4. **External acceptance:** security review, UAT sign-off, production SMS credentials and disaster-recovery evidence remain institutional dependencies.

Closed in the 4 November closure pass:

* Upgraded and verified Spring Boot 3.5.16 on Java 21.
* Upgraded the SSO profile to Keycloak 26.5.7 and declared `employee_no` as a managed, administrator-edited user-profile attribute.
* First sign-in now prefers the authoritative employee-number claim, with official email as fallback.
* Audit search and display resolve the staff name while retaining the immutable identity subject for traceability.
* Added GitHub Actions gates for backend verification, frontend production build and Docker Compose validation.
