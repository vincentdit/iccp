package tz.go.tirdo.iccp.com;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.go.tirdo.iccp.dir.api.AudienceRule;
import tz.go.tirdo.iccp.dir.api.DirectoryApi;
import tz.go.tirdo.iccp.ntf.api.NotificationRequest;
import tz.go.tirdo.iccp.ntf.api.Notifier;
import tz.go.tirdo.iccp.ntf.api.NtfCategory;
import tz.go.tirdo.iccp.platform.audit.AuditService;
import tz.go.tirdo.iccp.platform.config.IccpProperties;
import tz.go.tirdo.iccp.platform.files.FileScanService;
import tz.go.tirdo.iccp.platform.files.FileStorage;
import tz.go.tirdo.iccp.platform.files.FileStorageConfig;
import tz.go.tirdo.iccp.platform.outbox.OutboxWriter;
import tz.go.tirdo.iccp.platform.security.CurrentUser;
import tz.go.tirdo.iccp.platform.security.IccpPrincipal;
import tz.go.tirdo.iccp.platform.security.Roles;
import tz.go.tirdo.iccp.platform.text.HtmlSanitizer;
import tz.go.tirdo.iccp.platform.web.ApiException;
import tz.go.tirdo.iccp.sec.api.RoleDirectory;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Announcement lifecycle (BP-COM-01): draft → (review) → approved/scheduled → published →
 * amended/withdrawn → archived. Approval decisions are recorded in the shared workflow tables, whose
 * trigger rejects self-approval (BRL-07, FR-COM-04).
 */
@Service
public class AnnouncementService {

    static final String EVENT_PUBLISHED = "AnnouncementPublished";

    private final JdbcClient jdbc;
    private final DirectoryApi directory;
    private final RoleDirectory roles;
    private final Notifier notifier;
    private final OutboxWriter outbox;
    private final AuditService audit;
    private final FileStorage files;
    private final FileScanService scans;
    private final long maxUploadBytes;

    public AnnouncementService(JdbcClient jdbc, DirectoryApi directory, RoleDirectory roles, Notifier notifier, OutboxWriter outbox,
                               AuditService audit, FileStorage files, FileScanService scans, IccpProperties props) {
        this.jdbc = jdbc;
        this.directory = directory;
        this.roles = roles;
        this.notifier = notifier;
        this.outbox = outbox;
        this.audit = audit;
        this.files = files;
        this.scans = scans;
        this.maxUploadBytes = props.files().maxUploadBytes();
    }

    record Ann(long id, UUID publicId, String category, String status, long authorStaffId, Long approverStaffId, boolean requiresAck,
               OffsetDateTime effectiveAt, OffsetDateTime expiresAt, int currentVersion, String titleEn, String titleSw) {
    }

    static final String ANN_SELECT = """
            SELECT a.id, a.public_id, a.category, a.status, a.author_staff_id, a.approver_staff_id, a.requires_ack, a.effective_at,
                   a.expires_at, a.current_version, v.title_en, v.title_sw
              FROM com.announcement a
              JOIN com.announcement_version v ON v.announcement_id = a.id AND v.version_no = a.current_version
            """;

    Ann load(UUID publicId) {
        return jdbc.sql(ANN_SELECT + " WHERE a.public_id = :p FOR UPDATE OF a").param("p", publicId).query(Ann.class).optional()
                .orElseThrow(ApiException::notFound);
    }

    // ---------------------------------------------------------------- create / edit

    @Transactional
    public UUID create(ComTypes.AnnouncementInput in) {
        IccpPrincipal me = CurrentUser.get();
        requireAuthorRole(me, in.category());
        validate(in);
        boolean emergency = ComTypes.EMERGENCY.equals(in.category());
        OffsetDateTime effective = in.effectiveAt() == null || emergency ? OffsetDateTime.now() : in.effectiveAt();
        record Created(long id, UUID publicId) { }
        Created a = jdbc.sql("""
                INSERT INTO com.announcement (category, classification, author_staff_id, requires_ack, priority, effective_at, expires_at)
                VALUES (:cat, :cls, :author, :ack, :prio, :eff, :exp) RETURNING id, public_id
                """).param("cat", in.category()).param("cls", in.classification() == null ? "INTERNAL" : in.classification())
                .param("author", me.staffId()).param("ack", emergency || in.requiresAck())
                .param("prio", emergency ? 1 : in.priority() == null ? 3 : in.priority())
                .param("eff", effective).param("exp", in.expiresAt()).query(Created.class).single();
        insertVersion(a.id(), 1, in, null);
        replaceAudiences(a.id(), in.audiences());
        audit.record("COM", "ANNOUNCEMENT_CREATE", "announcement", a.publicId(), Map.of("category", in.category()));
        return a.publicId();
    }

    @Transactional
    public void update(UUID publicId, ComTypes.AnnouncementInput in) {
        IccpPrincipal me = CurrentUser.get();
        Ann a = load(publicId);
        if (a.authorStaffId() != me.staffId() || !Set.of("DRAFT", "RETURNED").contains(a.status())) {
            throw ApiException.conflict("ICCP-COM-010", "com.notEditable");
        }
        if (!a.category().equals(in.category())) {
            requireAuthorRole(me, in.category());
        }
        validate(in);
        boolean emergency = ComTypes.EMERGENCY.equals(in.category());
        int version = a.currentVersion();
        if (contentChanged(a.id(), version, in)) {
            version++;
            insertVersion(a.id(), version, in, in.changeNote());
        }
        jdbc.sql("""
                UPDATE com.announcement SET category = :cat, classification = :cls, requires_ack = :ack, priority = :prio,
                       effective_at = :eff, expires_at = :exp, current_version = :v, status = 'DRAFT' WHERE id = :id
                """).param("cat", in.category()).param("cls", in.classification() == null ? "INTERNAL" : in.classification())
                .param("ack", emergency || in.requiresAck()).param("prio", emergency ? 1 : in.priority() == null ? 3 : in.priority())
                .param("eff", in.effectiveAt() == null ? OffsetDateTime.now() : in.effectiveAt()).param("exp", in.expiresAt())
                .param("v", version).param("id", a.id()).update();
        replaceAudiences(a.id(), in.audiences());
        audit.record("COM", "ANNOUNCEMENT_UPDATE", "announcement", publicId, Map.of("version", version));
    }

    // ---------------------------------------------------------------- workflow

    @Transactional
    public String submit(UUID publicId) {
        IccpPrincipal me = CurrentUser.get();
        Ann a = load(publicId);
        if (a.authorStaffId() != me.staffId() || !Set.of("DRAFT", "RETURNED").contains(a.status())) {
            throw ApiException.conflict("ICCP-COM-011", "com.notSubmittable");
        }
        ensureAttachmentsClean(a.id());
        if (ComTypes.EMERGENCY.equals(a.category())) {
            CurrentUser.require(Roles.EMERGENCY_APPROVER);
            publish(a);
            audit.record("COM", "EMERGENCY_PUBLISH", "announcement", publicId, Map.of());
            return "PUBLISHED";
        }
        if (ComTypes.NEEDS_APPROVAL.contains(a.category())) {
            long def = jdbc.sql("SELECT id FROM wf.workflow_definition WHERE code = 'COM_APPROVAL' AND active ORDER BY def_version DESC LIMIT 1")
                    .query(Long.class).single();
            jdbc.sql("""
                    INSERT INTO wf.workflow_instance (definition_id, module, resource_type, resource_public_id, status, started_by_staff_id)
                    VALUES (:d, 'COM', 'announcement', :p, 'RUNNING', :me)
                    """).param("d", def).param("p", publicId).param("me", me.staffId()).update();
            jdbc.sql("UPDATE com.announcement SET status = 'IN_REVIEW' WHERE id = :id").param("id", a.id()).update();
            Set<Long> approvers = new LinkedHashSet<>(roles.staffWithRole(Roles.COMMS_APPROVER));
            approvers.remove(me.staffId());
            notifier.notify(new NotificationRequest(approvers, "AnnouncementSubmitted", NtfCategory.APPROVALS,
                    Map.of("title", title(a)), "/announcements/manage/" + publicId, Set.of(NotificationRequest.Channel.EMAIL), "COM", publicId));
            audit.record("COM", "ANNOUNCEMENT_SUBMIT", "announcement", publicId, Map.of());
            return "IN_REVIEW";
        }
        String status = goLive(a);
        audit.record("COM", "ANNOUNCEMENT_SUBMIT", "announcement", publicId, Map.of("status", status));
        return status;
    }

    @Transactional
    public String decide(UUID publicId, String decision, String comment) {
        IccpPrincipal me = CurrentUser.get();
        CurrentUser.require(Roles.COMMS_APPROVER);
        Ann a = load(publicId);
        if (!"IN_REVIEW".equals(a.status())) {
            throw ApiException.conflict("ICCP-COM-012", "com.notInReview");
        }
        if (a.authorStaffId() == me.staffId()) {
            audit.recordOutcome("COM", "ANNOUNCEMENT_" + decision, "announcement", publicId, AuditService.Outcome.DENIED, Map.of("reason", "self-approval"));
            throw ApiException.forbidden();
        }
        if (("REJECTED".equals(decision) || "RETURNED".equals(decision)) && (comment == null || comment.isBlank())) {
            throw ApiException.badRequest("ICCP-COM-013", "com.reasonRequired");
        }
        long instance = jdbc.sql("""
                SELECT id FROM wf.workflow_instance WHERE resource_type = 'announcement' AND resource_public_id = :p AND status = 'RUNNING'
                 ORDER BY id DESC LIMIT 1
                """).param("p", publicId).query(Long.class).optional().orElseThrow(() -> ApiException.conflict("ICCP-COM-012", "com.notInReview"));
        int step = jdbc.sql("SELECT coalesce(max(step_no), 0) + 1 FROM wf.approval_step WHERE instance_id = :i").param("i", instance).query(Integer.class).single();
        jdbc.sql("""
                INSERT INTO wf.approval_step (instance_id, step_no, approver_staff_id, decision, comment)
                VALUES (:i, :n, :me, :d, :c)
                """).param("i", instance).param("n", step).param("me", me.staffId()).param("d", decision)
                .param("c", comment == null || comment.isBlank() ? null : comment.strip()).update();
        jdbc.sql("UPDATE wf.workflow_instance SET status = :s, completed_at = now() WHERE id = :i").param("s", decision).param("i", instance).update();
        String status;
        if ("APPROVED".equals(decision)) {
            jdbc.sql("UPDATE com.announcement SET approver_staff_id = :me, approved_at = now(), status = 'APPROVED' WHERE id = :id")
                    .param("me", me.staffId()).param("id", a.id()).update();
            status = goLive(load(publicId));
        } else {
            jdbc.sql("UPDATE com.announcement SET status = :s WHERE id = :id").param("s", decision).param("id", a.id()).update();
            status = decision;
        }
        notifier.notify(NotificationRequest.inApp(List.of(a.authorStaffId()), "AnnouncementDecided", NtfCategory.APPROVALS,
                Map.of("title", title(a), "decision", decision.toLowerCase()), "/announcements/manage/" + publicId, "COM", publicId));
        audit.record("COM", "ANNOUNCEMENT_" + decision, "announcement", publicId, Map.of());
        return status;
    }

    @Transactional
    public void withdraw(UUID publicId, String reason) {
        CurrentUser.require(Roles.COMMS_APPROVER);
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("ICCP-COM-013", "com.reasonRequired");
        }
        Ann a = load(publicId);
        if (!Set.of("PUBLISHED", "SCHEDULED", "APPROVED").contains(a.status())) {
            throw ApiException.conflict("ICCP-COM-014", "com.notWithdrawable");
        }
        jdbc.sql("""
                UPDATE com.announcement SET status = 'WITHDRAWN', withdrawn_reason = :r, published_at = coalesce(published_at, now()) WHERE id = :id
                """).param("r", reason.strip()).param("id", a.id()).update();
        audit.record("COM", "ANNOUNCEMENT_WITHDRAW", "announcement", publicId, Map.of());
    }

    /** FR-COM-10: a published notice changes only by a new version; recipients are told. */
    @Transactional
    public void amend(UUID publicId, ComTypes.AnnouncementInput in) {
        IccpPrincipal me = CurrentUser.get();
        Ann a = load(publicId);
        if (!"PUBLISHED".equals(a.status())) {
            throw ApiException.conflict("ICCP-COM-015", "com.notAmendable");
        }
        boolean approverNeeded = ComTypes.NEEDS_APPROVAL.contains(a.category()) || ComTypes.EMERGENCY.equals(a.category());
        boolean allowed = approverNeeded ? me.hasAnyRole(Roles.COMMS_APPROVER, Roles.EMERGENCY_APPROVER) : a.authorStaffId() == me.staffId() || me.hasRole(Roles.COMMS_APPROVER);
        if (!allowed) {
            throw ApiException.forbidden();
        }
        if (in.changeNote() == null || in.changeNote().isBlank()) {
            throw ApiException.badRequest("ICCP-COM-016", "com.changeNoteRequired");
        }
        validateContent(in);
        int version = a.currentVersion() + 1;
        insertVersion(a.id(), version, in, in.changeNote());
        jdbc.sql("UPDATE com.announcement SET current_version = :v, expires_at = coalesce(:exp, expires_at) WHERE id = :id")
                .param("v", version).param("exp", in.expiresAt()).param("id", a.id()).update();
        outbox.write("announcement", publicId, EVENT_PUBLISHED, Map.of("publicId", publicId.toString(), "amended", true));
        audit.record("COM", "ANNOUNCEMENT_AMEND", "announcement", publicId, Map.of("version", version));
    }

    /** Approved or self-publishable: publish now, or schedule for the effective time (FR-COM-05). */
    String goLive(Ann a) {
        if (a.effectiveAt() != null && a.effectiveAt().isAfter(OffsetDateTime.now().plusSeconds(30))) {
            jdbc.sql("UPDATE com.announcement SET status = 'SCHEDULED' WHERE id = :id").param("id", a.id()).update();
            return "SCHEDULED";
        }
        publish(a);
        return "PUBLISHED";
    }

    /** Resolves recipients, records them and emits the event that fans out notifications. */
    void publish(Ann a) {
        Set<Long> recipients = resolveRecipients(a.id());
        jdbc.sql("UPDATE com.announcement SET status = 'PUBLISHED', published_at = now(), effective_at = least(effective_at, now()) WHERE id = :id")
                .param("id", a.id()).update();
        if (!recipients.isEmpty()) {
            jdbc.sql("""
                    INSERT INTO com.announcement_recipient (announcement_id, staff_id, delivered_at)
                    SELECT :a, x, now() FROM unnest(CAST(:ids AS bigint[])) AS x
                    ON CONFLICT (announcement_id, staff_id) DO NOTHING
                    """).param("a", a.id()).param("ids", pgArray(recipients)).update();
        }
        outbox.write("announcement", a.publicId(), EVENT_PUBLISHED, Map.of("publicId", a.publicId().toString(), "amended", false));
    }

    Set<Long> resolveRecipients(long announcementId) {
        List<AudienceRule> rules = jdbc.sql("SELECT rule_type AS type, rule_value AS value FROM com.announcement_audience WHERE announcement_id = :a")
                .param("a", announcementId).query(AudienceRule.class).list();
        Set<Long> ids = new LinkedHashSet<>(directory.resolveAudience(rules));
        rules.stream().filter(r -> "ROLE".equals(r.type())).forEach(r -> ids.addAll(roles.staffWithRole(r.value())));
        return ids;
    }

    @Transactional(readOnly = true)
    public long previewAudience(List<AudienceRule> rules) {
        CurrentUser.require(Roles.COMMS_PUBLISHER, Roles.COMMS_APPROVER, Roles.EMERGENCY_APPROVER);
        Set<Long> ids = new LinkedHashSet<>(directory.resolveAudience(rules));
        rules.stream().filter(r -> "ROLE".equals(r.type())).forEach(r -> ids.addAll(roles.staffWithRole(r.value())));
        return ids.size();
    }

    // ---------------------------------------------------------------- recipient actions

    @Transactional
    public void acknowledge(UUID publicId, String channel) {
        IccpPrincipal me = CurrentUser.get();
        int n = jdbc.sql("""
                UPDATE com.announcement_recipient r
                   SET acknowledged_at = coalesce(r.acknowledged_at, now()), first_read_at = coalesce(r.first_read_at, now()),
                       ack_channel = coalesce(r.ack_channel, :ch)
                  FROM com.announcement a
                 WHERE a.id = r.announcement_id AND a.public_id = :p AND r.staff_id = :me AND a.status = 'PUBLISHED'
                """).param("ch", "MOBILE_WEB".equals(channel) ? "MOBILE_WEB" : "WEB").param("p", publicId).param("me", me.staffId()).update();
        if (n == 0) {
            throw ApiException.notFound();
        }
        audit.record("COM", "ANNOUNCEMENT_ACK", "announcement", publicId, Map.of());
    }

    @Transactional
    public FileStorage.StoredFile addAttachment(UUID publicId, String fileName, String contentType, byte[] content) {
        IccpPrincipal me = CurrentUser.get();
        Ann a = load(publicId);
        if (a.authorStaffId() != me.staffId() || !Set.of("DRAFT", "RETURNED").contains(a.status())) {
            throw ApiException.conflict("ICCP-COM-010", "com.notEditable");
        }
        if (contentType == null || !FileStorageConfig.ALLOWED_TYPES.contains(contentType)) {
            throw ApiException.badRequest("ICCP-FILE-002", "error.fileType");
        }
        if (content.length == 0 || content.length > maxUploadBytes) {
            throw ApiException.badRequest("ICCP-FILE-001", "error.fileTooLarge");
        }
        String safeName = fileName == null ? "attachment" : fileName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        FileStorage.StoredFile f = files.put("quarantine/com/" + publicId, safeName, contentType, content);
        jdbc.sql("""
                INSERT INTO com.announcement_attachment (announcement_id, object_key, file_name, content_type, size_bytes, sha256)
                VALUES (:a, :k, :n, :t, :s, :h)
                """).param("a", a.id()).param("k", f.objectKey()).param("n", f.fileName()).param("t", f.contentType())
                .param("s", f.sizeBytes()).param("h", f.sha256()).update();
        scans.enqueue(f.objectKey());
        audit.record("COM", "ATTACHMENT_QUARANTINED", "announcement", publicId, Map.of("file", safeName));
        return f;
    }

    // ---------------------------------------------------------------- notifications (called from the outbox handler)

    private void ensureAttachmentsClean(long announcementId) {
        String status = jdbc.sql("""
                SELECT fs.status FROM com.announcement_attachment a
                  LEFT JOIN integ.file_scan fs ON fs.object_key = a.object_key
                 WHERE a.announcement_id = :a AND coalesce(fs.status, 'CLEAN') <> 'CLEAN'
                 ORDER BY a.id LIMIT 1
                """).param("a", announcementId).query(String.class).optional().orElse(null);
        if (status != null) {
            throw ApiException.conflict("ICCP-FILE-003",
                    "INFECTED".equals(status) ? "error.fileInfected" : "error.fileScanning");
        }
    }

    /** Fans out notifications for a newly published or amended notice, in each recipient's language. */
    @Transactional
    public void notifyRecipients(UUID publicId, boolean amended) {
        Ann a = jdbc.sql(ANN_SELECT + " WHERE a.public_id = :p").param("p", publicId).query(Ann.class).optional().orElse(null);
        if (a == null || !"PUBLISHED".equals(a.status())) {
            return;
        }
        List<Long> ids = jdbc.sql("SELECT staff_id FROM com.announcement_recipient WHERE announcement_id = :a").param("a", a.id()).query(Long.class).list();
        boolean emergency = ComTypes.EMERGENCY.equals(a.category());
        String event = emergency ? "EmergencyAlert" : a.requiresAck() && !amended ? "AnnouncementAckRequired" : EVENT_PUBLISHED;
        String category = emergency ? NtfCategory.EMERGENCY : NtfCategory.COMMUNICATION;
        Set<NotificationRequest.Channel> channels = emergency
                ? Set.of(NotificationRequest.Channel.EMAIL, NotificationRequest.Channel.SMS)
                : a.requiresAck() || ComTypes.NEEDS_APPROVAL.contains(a.category()) ? Set.of(NotificationRequest.Channel.EMAIL) : Set.of();
        Map<String, List<Long>> byLanguage = directory.contacts(ids).stream()
                .collect(Collectors.groupingBy(c -> "sw".equals(c.language()) ? "sw" : "en", Collectors.mapping(c -> c.staffId(), Collectors.toList())));
        byLanguage.forEach((lang, staff) -> {
            String t = "sw".equals(lang) ? Objects.requireNonNullElse(a.titleSw(), a.titleEn()) : Objects.requireNonNullElse(a.titleEn(), a.titleSw());
            if (amended) {
                t = ("sw".equals(lang) ? "Limerekebishwa: " : "Amended: ") + t;
            }
            notifier.notify(new NotificationRequest(staff, event, category, Map.of("title", t), "/announcements/" + publicId, channels, "COM", publicId));
        });
    }

    // ---------------------------------------------------------------- helpers

    private void requireAuthorRole(IccpPrincipal me, String category) {
        boolean ok = ComTypes.EMERGENCY.equals(category) ? me.hasRole(Roles.EMERGENCY_APPROVER) : me.hasRole(Roles.COMMS_PUBLISHER);
        if (!ok) {
            throw ApiException.forbidden();
        }
    }

    private void validate(ComTypes.AnnouncementInput in) {
        validateContent(in);
        if (in.expiresAt() != null) {
            OffsetDateTime eff = in.effectiveAt() == null ? OffsetDateTime.now() : in.effectiveAt();
            if (!in.expiresAt().isAfter(eff)) {
                throw ApiException.badRequest("ICCP-COM-002", "com.expiryBeforeEffective");
            }
        }
        for (AudienceRule r : in.audiences()) {
            if (!"ALL".equals(r.type()) && (r.value() == null || r.value().isBlank())) {
                throw ApiException.badRequest("ICCP-COM-003", "com.audienceValue");
            }
        }
    }

    private static void validateContent(ComTypes.AnnouncementInput in) {
        boolean en = notBlank(in.titleEn()) && notBlank(HtmlSanitizer.toPlainText(HtmlSanitizer.sanitize(in.bodyEn())));
        boolean sw = notBlank(in.titleSw()) && notBlank(HtmlSanitizer.toPlainText(HtmlSanitizer.sanitize(in.bodySw())));
        if (!en && !sw) {
            throw ApiException.badRequest("ICCP-COM-001", "com.contentRequired");
        }
    }

    private void insertVersion(long id, int versionNo, ComTypes.AnnouncementInput in, String note) {
        jdbc.sql("""
                INSERT INTO com.announcement_version (announcement_id, version_no, title_en, body_en, title_sw, body_sw, change_note)
                VALUES (:a, :v, :te, :be, :ts, :bs, :n)
                """).param("a", id).param("v", versionNo)
                .param("te", blank(in.titleEn())).param("be", blank(HtmlSanitizer.sanitize(in.bodyEn())))
                .param("ts", blank(in.titleSw())).param("bs", blank(HtmlSanitizer.sanitize(in.bodySw())))
                .param("n", blank(note)).update();
    }

    record Content(String titleEn, String bodyEn, String titleSw, String bodySw) {
    }

    private boolean contentChanged(long id, int version, ComTypes.AnnouncementInput in) {
        Content c = jdbc.sql("SELECT title_en, body_en, title_sw, body_sw FROM com.announcement_version WHERE announcement_id = :a AND version_no = :v")
                .param("a", id).param("v", version).query(Content.class).single();
        return !Objects.equals(c.titleEn(), blank(in.titleEn())) || !Objects.equals(c.bodyEn(), blank(HtmlSanitizer.sanitize(in.bodyEn())))
                || !Objects.equals(c.titleSw(), blank(in.titleSw())) || !Objects.equals(c.bodySw(), blank(HtmlSanitizer.sanitize(in.bodySw())));
    }

    private void replaceAudiences(long id, List<AudienceRule> rules) {
        jdbc.sql("DELETE FROM com.announcement_audience WHERE announcement_id = :a").param("a", id).update();
        for (AudienceRule r : rules) {
            jdbc.sql("INSERT INTO com.announcement_audience (announcement_id, rule_type, rule_value) VALUES (:a, :t, :v)")
                    .param("a", id).param("t", r.type()).param("v", "ALL".equals(r.type()) ? null : r.value().trim()).update();
        }
    }

    static String title(Ann a) {
        return a.titleEn() != null ? a.titleEn() : a.titleSw();
    }

    static String pgArray(Set<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
