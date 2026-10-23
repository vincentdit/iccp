package tz.go.tirdo.iccp.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tz.go.tirdo.iccp.com.AnnouncementService;
import tz.go.tirdo.iccp.com.ComTypes;
import tz.go.tirdo.iccp.dir.HrImportService;
import tz.go.tirdo.iccp.dir.api.AudienceRule;
import tz.go.tirdo.iccp.dir.api.DirectoryApi;
import tz.go.tirdo.iccp.dir.api.StaffRef;
import tz.go.tirdo.iccp.fin.FinTypes;
import tz.go.tirdo.iccp.fin.FinanceBatchService;
import tz.go.tirdo.iccp.fin.FinanceImportService;
import tz.go.tirdo.iccp.fin.FinancialDiaryService;
import tz.go.tirdo.iccp.platform.db.DbSessionContext;
import tz.go.tirdo.iccp.platform.security.IccpAuthentication;
import tz.go.tirdo.iccp.platform.security.IccpPrincipal;
import tz.go.tirdo.iccp.sec.PrincipalService;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Loads fictional demonstration data on an empty database (profile {@code demo}). Everything goes
 * through the real services — HR import, Finance import and maker-checker publication, the
 * announcement workflow — so the demo exercises the same rules as production. All names are
 * invented; email addresses use the reserved {@code .example} domain.
 */
@Component
@ConditionalOnProperty(name = "iccp.demo.enabled", havingValue = "true")
public class DemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final HrImportService hr;
    private final DirectoryApi directory;
    private final PrincipalService principals;
    private final FinanceImportService finImport;
    private final FinanceBatchService finBatches;
    private final FinancialDiaryService diary;
    private final AnnouncementService announcements;

    public DemoSeeder(JdbcClient jdbc, TransactionTemplate tx, HrImportService hr, DirectoryApi directory, PrincipalService principals,
                      FinanceImportService finImport, FinanceBatchService finBatches, FinancialDiaryService diary,
                      AnnouncementService announcements) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.hr = hr;
        this.directory = directory;
        this.principals = principals;
        this.finImport = finImport;
        this.finBatches = finBatches;
        this.diary = diary;
        this.announcements = announcements;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        long staff = DbSessionContext.runAs(DbSessionContext.system("demo"),
                () -> tx.execute(s -> jdbc.sql("SELECT count(*) FROM dir.staff_member").query(Long.class).single()));
        if (staff > 0) {
            log.info("Demo data: directory already populated, nothing to do");
            return;
        }
        log.info("Demo data: loading fictional staff, finance records and notices");
        byte[] hrFile = read("demo/hr/hr-demo-2026-10-01.csv");
        DbSessionContext.runAs(DbSessionContext.system("demo"), () -> hr.importFile("hr-demo-2026-10-01.csv", hrFile));
        DbSessionContext.runAs(DbSessionContext.system("demo"), () -> tx.executeWithoutResult(s -> grantRoles()));
        principals.evict(null);

        byte[] fin1 = read("demo/finance/finance-demo-2026-10-01.csv");
        byte[] fin2 = read("demo/finance/finance-demo-2026-10-09-with-exceptions.csv");
        UUID batch1 = as("TR1041", () -> finImport.importFile("finance-demo-2026-10-01.csv", fin1)).batchPublicId();
        as("TR1040", () -> finBatches.approve(batch1));
        as("TR1041", () -> finImport.importFile("finance-demo-2026-10-09-with-exceptions.csv", fin2));

        // Claim queries: one waiting for Finance, one answered and waiting for the staff member.
        UUID consultancy = claimId("CLM-2026-0533");
        as("TR1007", () -> diary.raise(consultancy, new FinTypes.NewQuery("MISSING_PAYMENT",
                "Only TZS 300,000 of the consultancy allowance has been paid. When will the balance be settled?")));
        UUID extraDuty = claimId("CLM-2026-0502");
        UUID q2 = as("TR1014", () -> diary.raise(extraDuty, new FinTypes.NewQuery("MISSING_PAYMENT",
                "The weekend commissioning allowance approved in August has not been paid.")));
        as("TR1041", () -> diary.post(q2, new FinTypes.MessageInput(
                "The payment is scheduled in the October payment run. You will see it here once Finance publishes the run.")));

        seedAnnouncements();
        principals.evict(null);
        SecurityContextHolder.clearContext();
        log.info("Demo data loaded. Sign in on the dev screen, e.g. as TR1007 (staff), TR1041 (Finance officer) or TR1061 (Communication)");
    }

    private void grantRoles() {
        LocalDate end = LocalDate.now().plusYears(1);
        grant("TR1031", "SYS_ADMIN", end, "TR1030 (DCS)");
        grant("TR1031", "SECURITY_ADMIN", end, "TR1030 (DCS)");
        grant("TR1032", "SYS_ADMIN", end, "TR1031 (ICTT)");
        grant("TR1050", "HR_OFFICER", null, null);
        grant("TR1051", "HR_OFFICER", null, null);
        grant("TR1061", "COMMS_PUBLISHER", null, null);
        grant("TR1060", "COMMS_PUBLISHER", null, null);
        grant("TR1030", "COMMS_APPROVER", end, "TR1001 (DG)");
        grant("TR1001", "COMMS_APPROVER", end, "Board resolution 2026/14");
        grant("TR1001", "EMERGENCY_APPROVER", end, "Board resolution 2026/14");
        grant("TR1001", "EXECUTIVE", null, null);
        grant("TR1041", "FIN_OFFICER", end, "TR1040 (Chief Accountant)");
        grant("TR1042", "FIN_OFFICER", end, "TR1040 (Chief Accountant)");
        grant("TR1040", "FIN_REVIEWER", end, "TR1030 (DCS)");
    }

    private void grant(String employeeNo, String role, LocalDate endsOn, String approvedBy) {
        StaffRef s = directory.findByEmployeeNo(employeeNo).orElseThrow();
        long account = jdbc.sql("SELECT id FROM sec.user_account WHERE staff_id = :s").param("s", s.id()).query(Long.class).optional()
                .orElseGet(() -> jdbc.sql("""
                        INSERT INTO sec.user_account (staff_id, keycloak_subject, account_type, status)
                        VALUES (:s, :sub, 'STAFF', 'ACTIVE') RETURNING id
                        """).param("s", s.id()).param("sub", "pending:" + s.publicId()).query(Long.class).single());
        jdbc.sql("""
                INSERT INTO sec.role_assignment (user_account_id, role_id, starts_on, ends_on, approved_by, approval_ref)
                VALUES (:a, (SELECT id FROM sec.role WHERE code = :r), current_date - 30, :end, :by, :ref)
                """).param("a", account).param("r", role).param("end", endsOn).param("by", approvedBy)
                .param("ref", approvedBy == null ? null : "DEMO-" + role).update();
    }

    private void seedAnnouncements() {
        List<AudienceRule> all = List.of(new AudienceRule("ALL", null));
        OffsetDateTime now = OffsetDateTime.now();
        as("TR1061", () -> {
            UUID ict = announcements.create(new ComTypes.AnnouncementInput("NOTICE", "INTERNAL", true, 2, null, now.plusDays(30),
                    "Password policy update: multi-factor sign-in from 1 November",
                    "<p>From <strong>1 November 2026</strong>, all ICCP accounts with Finance, approval or administration roles must "
                            + "sign in with a second factor. Install an authenticator app and follow the prompts at your next sign-in.</p>"
                            + "<p>The ICTT Department service desk (ext. 1031) can help.</p>",
                    "Sera mpya ya nywila: uthibitishaji wa hatua mbili kuanzia 1 Novemba",
                    "<p>Kuanzia <strong>1 Novemba 2026</strong>, akaunti zote za ICCP zenye majukumu ya Fedha, uidhinishaji au utawala "
                            + "zitaingia kwa uthibitishaji wa hatua mbili. Sakinisha programu ya uthibitishaji na ufuate maelekezo.</p>",
                    all, null));
            announcements.submit(ict);
            UUID event = announcements.create(new ComTypes.AnnouncementInput("EVENT", "INTERNAL", false, 3, null, now.plusDays(21),
                    "Open laboratory day for SMEs - 12 November",
                    "<p>The Chemical and Mechanical laboratories welcome small and medium enterprises on <strong>12 November</strong>, "
                            + "09:00–15:00. Volunteers to guide visitors, please contact the Communication Unit.</p>",
                    "Siku ya maabara wazi kwa wajasiriamali - 12 Novemba",
                    "<p>Maabara za Kemikali na Mitambo zinawakaribisha wajasiriamali wadogo na wa kati tarehe <strong>12 Novemba</strong>, "
                            + "saa 3:00–9:00 mchana.</p>",
                    all, null));
            announcements.submit(event);
            UUID circular = announcements.create(new ComTypes.AnnouncementInput("CIRCULAR", "INTERNAL", true, 1, null, null,
                    "Circular 07/2026: Year-end leave planning",
                    "<p>Heads of units should submit their staff leave plans for December–January by <strong>14 November 2026</strong>, "
                            + "keeping at least one officer on duty in every laboratory.</p>",
                    "Waraka 07/2026: Mpango wa likizo za mwisho wa mwaka",
                    "<p>Wakuu wa vitengo wawasilishe mipango ya likizo ya Desemba–Januari kabla ya <strong>14 Novemba 2026</strong>.</p>",
                    all, null));
            announcements.submit(circular);
            as("TR1030", () -> announcements.decide(circular, "APPROVED", "Approved for circulation."));
            UUID pending = announcements.create(new ComTypes.AnnouncementInput("CIRCULAR", "INTERNAL", false, 2, null, null,
                    "Circular 08/2026: Use of institutional vehicles",
                    "<p>Requests for institutional vehicles must be made through the transport desk at least two working days in advance.</p>",
                    "Waraka 08/2026: Matumizi ya magari ya taasisi",
                    "<p>Maombi ya magari ya taasisi yafanywe kupitia dawati la usafiri angalau siku mbili za kazi kabla.</p>",
                    all, null));
            announcements.submit(pending);
            return null;
        });
    }

    private UUID claimId(String sourceRef) {
        return DbSessionContext.runAs(DbSessionContext.system("demo"),
                () -> tx.execute(s -> jdbc.sql("SELECT public_id FROM fin.claim WHERE source_ref = :r").param("r", sourceRef)
                        .query(UUID.class).single()));
    }

    /** Runs work signed in as a demo staff member, using the same subject the dev sign-in issues. */
    private <T> T as(String employeeNo, Supplier<T> work) {
        var previous = SecurityContextHolder.getContext().getAuthentication();
        StaffRef s = directory.findByEmployeeNo(employeeNo).orElseThrow();
        Jwt jwt = Jwt.withTokenValue("demo").header("alg", "none").subject("dev:" + employeeNo)
                .claim("email", s.email()).claim("employee_no", employeeNo).claim("name", s.displayName())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        principals.evict("dev:" + employeeNo);
        IccpPrincipal p = principals.load(jwt);
        SecurityContextHolder.getContext().setAuthentication(new IccpAuthentication(p, jwt));
        try {
            return work.get();
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }

    private void as(String employeeNo, Runnable work) {
        as(employeeNo, () -> {
            work.run();
            return null;
        });
    }

    private static byte[] read(String path) throws IOException {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        }
    }
}
