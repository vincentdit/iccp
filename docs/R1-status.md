# Release 1 — status and known gaps

Verified in the build environment on 23 October 2026:

* Database: V1–V7 migrate cleanly on PostgreSQL 16; 50 of 50 integrity checks pass; audit chain verifies.
* Backend: compiles on Java 21; 15 unit tests pass (run with a minimal JUnit runner because Maven Central was not
  reachable from the build environment — run `mvn verify` locally to confirm); API smoke test 57 of 57.
* Front end: production build succeeds; an automated browser walkthrough (write circular → approve → acknowledge →
  raise claim query → Finance answers → reviewer publishes import → staff confirms) completes with no script errors;
  screens checked in light, dark, Kiswahili and at phone width.

Known gaps to close before go-live:

1. **Maven build not yet run outside this environment.** Dependencies were compiled against Spring Boot 3.3 jars;
   `pom.xml` targets 3.5.12. Run `mvn verify` and fix any API differences.
2. **Keycloak user attribute `employee_no`.** Keycloak 26 drops unmanaged attributes unless the user profile allows them;
   sign-in falls back to email linking, which works for the demo users. Enable the attribute in the realm's user profile.
3. **SMS gateway** is a logging stub; connect the government SMS gateway (Doc 03 §6.5).
4. **Notification text language** is fixed when the notification is created; switching language later does not
   translate earlier inbox items.
5. **Audit actor names** show the sign-in subject (e.g. `dev:TR1041`); show the person's name in the Finance and audit views.
6. **Doc 06 updates to record:** `ntf.notification.params` (V6) and the COM approval workflow seed (V7).
