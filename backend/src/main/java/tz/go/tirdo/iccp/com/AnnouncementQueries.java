package tz.go.tirdo.iccp.com;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.go.tirdo.iccp.dir.api.AudienceRule;
import tz.go.tirdo.iccp.dir.api.DirectoryApi;
import tz.go.tirdo.iccp.dir.api.UnitRef;
import tz.go.tirdo.iccp.ntf.api.Notifier;
import tz.go.tirdo.iccp.platform.audit.AuditService;
import tz.go.tirdo.iccp.platform.files.FileStorage;
import tz.go.tirdo.iccp.platform.security.CurrentUser;
import tz.go.tirdo.iccp.platform.security.IccpPrincipal;
import tz.go.tirdo.iccp.platform.security.Roles;
import tz.go.tirdo.iccp.platform.web.ApiException;
import tz.go.tirdo.iccp.platform.web.PageResult;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Read side of COM: staff feed, management views and reach reports. */
@Service
public class AnnouncementQueries {

    private final JdbcClient jdbc;
    private final DirectoryApi directory;
    private final FileStorage files;
    private final AuditService audit;
    private final Notifier notifier;

    public AnnouncementQueries(JdbcClient jdbc, DirectoryApi directory, FileStorage files, AuditService audit, Notifier notifier) {
        this.jdbc = jdbc;
        this.directory = directory;
        this.files = files;
        this.audit = audit;
        this.notifier = notifier;
    }

    private static final String TITLE = "CASE WHEN :lang = 'sw' THEN coalesce(v.title_sw, v.title_en) ELSE coalesce(v.title_en, v.title_sw) END";
    private static final String BODY = "CASE WHEN :lang = 'sw' THEN coalesce(v.body_sw, v.body_en) ELSE coalesce(v.body_en, v.body_sw) END";

    // ---------------------------------------------------------------- staff feed (SCR-COM-01/02)

    @Transactional(readOnly = true)
    public PageResult<ComTypes.FeedItem> feed(String filter, String lang, Integer page, Integer size) {
        IccpPrincipal me = CurrentUser.get();
        int p = PageResult.clampPage(page), sz = PageResult.clampSize(size);
        String extra = switch (filter == null ? "all" : filter) {
            case "unread" -> " AND r.first_read_at IS NULL";
            case "ack" -> " AND a.requires_ack AND r.acknowledged_at IS NULL";
            default -> "";
        };
        String from = """
                  FROM com.announcement a
                  JOIN com.announcement_recipient r ON r.announcement_id = a.id AND r.staff_id = :me
                  JOIN com.announcement_version v ON v.announcement_id = a.id AND v.version_no = a.current_version
                 WHERE a.status = 'PUBLISHED' AND a.effective_at <= now() AND (a.expires_at IS NULL OR a.expires_at > now())
                """ + extra;
        long total = jdbc.sql("SELECT count(*)" + from).param("me", me.staffId()).query(Long.class).single();
        List<ComTypes.FeedItem> items = jdbc.sql("SELECT a.public_id, a.category, a.priority, " + TITLE + " AS title, "
                        + "left(btrim(regexp_replace(regexp_replace(regexp_replace(regexp_replace(" + BODY
                        + ", '</?(p|br|li|ul|ol|h3|h4|blockquote)[^>]*>', ' ', 'gi'), '<[^>]+>', '', 'g'), '\\s+', ' ', 'g'), ' ([,.;:])', '\\1', 'g')), 220) AS excerpt, "
                        + "a.published_at, a.effective_at, a.expires_at, a.requires_ack, r.first_read_at AS read_at, r.acknowledged_at, "
                        + "(SELECT count(*) FROM com.announcement_attachment x WHERE x.announcement_id = a.id)::int AS attachment_count, "
                        + "a.current_version" + from
                        + " ORDER BY (a.category = 'EMERGENCY_ALERT' AND r.acknowledged_at IS NULL) DESC, a.priority, a.published_at DESC"
                        + " LIMIT :lim OFFSET :off")
                .param("me", me.staffId()).param("lang", lang).param("lim", sz).param("off", p * sz).query(ComTypes.FeedItem.class).list();
        return new PageResult<>(items, total, p, sz);
    }

    /** Active emergency alerts the person has not acknowledged (pinned banner, FR-COM-09). */
    @Transactional(readOnly = true)
    public List<ComTypes.FeedItem> emergencies(String lang) {
        IccpPrincipal me = CurrentUser.get();
        return jdbc.sql("SELECT a.public_id, a.category, a.priority, " + TITLE + " AS title, '' AS excerpt, a.published_at, a.effective_at, "
                        + "a.expires_at, a.requires_ack, r.first_read_at AS read_at, r.acknowledged_at, 0 AS attachment_count, a.current_version "
                        + """
                          FROM com.announcement a
                          JOIN com.announcement_recipient r ON r.announcement_id = a.id AND r.staff_id = :me
                          JOIN com.announcement_version v ON v.announcement_id = a.id AND v.version_no = a.current_version
                         WHERE a.category = 'EMERGENCY_ALERT' AND a.status = 'PUBLISHED' AND r.acknowledged_at IS NULL
                           AND (a.expires_at IS NULL OR a.expires_at > now())
                         ORDER BY a.published_at DESC
                        """)
                .param("me", me.staffId()).param("lang", lang).query(ComTypes.FeedItem.class).list();
    }

    record DetailRow(long id, UUID publicId, String category, String classification, int priority, String titleEn, String bodyEn,
                     String titleSw, String bodySw, OffsetDateTime publishedAt, OffsetDateTime effectiveAt, OffsetDateTime expiresAt,
                     boolean requiresAck, OffsetDateTime readAt, OffsetDateTime acknowledgedAt, int currentVersion, String changeNote,
                     String authorUnit) {
    }

    /** Opens a notice for a recipient and records the first read (FR-COM-07). */
    @Transactional
    public ComTypes.FeedDetail open(UUID publicId) {
        IccpPrincipal me = CurrentUser.get();
        DetailRow d = jdbc.sql("""
                SELECT a.id, a.public_id, a.category, a.classification, a.priority, v.title_en, v.body_en, v.title_sw, v.body_sw,
                       a.published_at, a.effective_at, a.expires_at, a.requires_ack, r.first_read_at AS read_at, r.acknowledged_at,
                       a.current_version, v.change_note, u.name_en AS author_unit
                  FROM com.announcement a
                  JOIN com.announcement_recipient r ON r.announcement_id = a.id AND r.staff_id = :me
                  JOIN com.announcement_version v ON v.announcement_id = a.id AND v.version_no = a.current_version
                  JOIN dir.staff_member s ON s.id = a.author_staff_id
                  JOIN dir.org_unit u ON u.id = s.org_unit_id
                 WHERE a.public_id = :p AND a.status IN ('PUBLISHED','ARCHIVED')
                """).param("me", me.staffId()).param("p", publicId).query(DetailRow.class).optional().orElseThrow(ApiException::notFound);
        if (d.readAt() == null) {
            jdbc.sql("UPDATE com.announcement_recipient SET first_read_at = now() WHERE announcement_id = :a AND staff_id = :me AND first_read_at IS NULL")
                    .param("a", d.id()).param("me", me.staffId()).update();
        }
        notifier.markReadForSource(me.staffId(), publicId);
        return new ComTypes.FeedDetail(d.publicId(), d.category(), d.classification(), d.priority(), d.titleEn(), d.bodyEn(), d.titleSw(),
                d.bodySw(), d.publishedAt(), d.effectiveAt(), d.expiresAt(), d.requiresAck(), d.readAt() == null ? OffsetDateTime.now() : d.readAt(),
                d.acknowledgedAt(), d.currentVersion(), d.currentVersion() > 1 ? d.changeNote() : null, d.authorUnit(), attachments(d.id()));
    }

    // ---------------------------------------------------------------- management (SCR-COM-03/04)

    @Transactional(readOnly = true)
    public List<ComTypes.ManageItem> manageList(String box) {
        IccpPrincipal me = CurrentUser.get();
        CurrentUser.require(Roles.COMMS_PUBLISHER, Roles.COMMS_APPROVER, Roles.EMERGENCY_APPROVER);
        String where = switch (box == null ? "mine" : box) {
            case "review" -> {
                CurrentUser.require(Roles.COMMS_APPROVER);
                yield " WHERE a.status = 'IN_REVIEW' AND a.author_staff_id <> :me";
            }
            case "all" -> {
                CurrentUser.require(Roles.COMMS_APPROVER);
                yield " WHERE :me = :me";
            }
            default -> " WHERE a.author_staff_id = :me";
        };
        return jdbc.sql("""
                SELECT a.public_id, a.category, a.status, a.priority, v.title_en, v.title_sw,
                       concat_ws(' ', s.first_name, s.last_name) AS author_name, a.effective_at, a.expires_at, a.published_at,
                       coalesce(a.updated_at, a.created_at) AS updated_at, a.requires_ack,
                       (SELECT count(*) FROM com.announcement_recipient r WHERE r.announcement_id = a.id) AS recipients,
                       (SELECT count(*) FROM com.announcement_recipient r WHERE r.announcement_id = a.id AND r.acknowledged_at IS NOT NULL) AS acknowledged
                  FROM com.announcement a
                  JOIN com.announcement_version v ON v.announcement_id = a.id AND v.version_no = a.current_version
                  JOIN dir.staff_member s ON s.id = a.author_staff_id
                """ + where + " ORDER BY coalesce(a.updated_at, a.created_at) DESC LIMIT 200")
                .param("me", me.staffId()).query(ComTypes.ManageItem.class).list();
    }

    record ManageRow(long id, UUID publicId, String category, String classification, String status, int priority, boolean requiresAck,
                     OffsetDateTime effectiveAt, OffsetDateTime expiresAt, OffsetDateTime publishedAt, String withdrawnReason,
                     int currentVersion, String titleEn, String bodyEn, String titleSw, String bodySw, long authorStaffId,
                     UUID authorPublicId, String authorName, String approverName, OffsetDateTime approvedAt) {
    }

    @Transactional(readOnly = true)
    public ComTypes.ManageDetail manageDetail(UUID publicId) {
        IccpPrincipal me = CurrentUser.get();
        ManageRow m = jdbc.sql("""
                SELECT a.id, a.public_id, a.category, a.classification, a.status, a.priority, a.requires_ack, a.effective_at, a.expires_at,
                       a.published_at, a.withdrawn_reason, a.current_version, v.title_en, v.body_en, v.title_sw, v.body_sw,
                       a.author_staff_id, s.public_id AS author_public_id, concat_ws(' ', s.first_name, s.last_name) AS author_name,
                       concat_ws(' ', ap.first_name, ap.last_name) AS approver_name, a.approved_at
                  FROM com.announcement a
                  JOIN com.announcement_version v ON v.announcement_id = a.id AND v.version_no = a.current_version
                  JOIN dir.staff_member s ON s.id = a.author_staff_id
                  LEFT JOIN dir.staff_member ap ON ap.id = a.approver_staff_id
                 WHERE a.public_id = :p
                """).param("p", publicId).query(ManageRow.class).optional().orElseThrow(ApiException::notFound);
        boolean author = m.authorStaffId() == me.staffId();
        boolean approver = me.hasRole(Roles.COMMS_APPROVER);
        if (!author && !approver) {
            throw ApiException.notFound();
        }
        List<AudienceRule> audiences = jdbc.sql("SELECT rule_type AS type, rule_value AS value FROM com.announcement_audience WHERE announcement_id = :a ORDER BY id")
                .param("a", m.id()).query(AudienceRule.class).list();
        List<ComTypes.VersionInfo> versions = jdbc.sql("""
                SELECT version_no, title_en, title_sw, change_note, created_by, created_at FROM com.announcement_version
                 WHERE announcement_id = :a ORDER BY version_no DESC
                """).param("a", m.id()).query(ComTypes.VersionInfo.class).list();
        List<ComTypes.Decision> decisions = jdbc.sql("""
                SELECT st.step_no, concat_ws(' ', s.first_name, s.last_name) AS approver_name, st.decision, st.comment, st.decided_at
                  FROM wf.workflow_instance wi
                  JOIN wf.approval_step st ON st.instance_id = wi.id
                  JOIN dir.staff_member s ON s.id = st.approver_staff_id
                 WHERE wi.resource_type = 'announcement' AND wi.resource_public_id = :p
                 ORDER BY st.decided_at DESC
                """).param("p", publicId).query(ComTypes.Decision.class).list();
        record Counts(long recipients, long read, long acknowledged) { }
        Counts c = jdbc.sql("""
                SELECT count(*) AS recipients, count(first_read_at) AS read, count(acknowledged_at) AS acknowledged
                  FROM com.announcement_recipient WHERE announcement_id = :a
                """).param("a", m.id()).query(Counts.class).single();
        boolean editable = author && Set.of("DRAFT", "RETURNED").contains(m.status());
        boolean decide = approver && !author && "IN_REVIEW".equals(m.status());
        boolean withdraw = approver && Set.of("PUBLISHED", "SCHEDULED", "APPROVED").contains(m.status());
        boolean needsApprover = ComTypes.NEEDS_APPROVAL.contains(m.category()) || ComTypes.EMERGENCY.equals(m.category());
        boolean amend = "PUBLISHED".equals(m.status()) && (needsApprover ? approver || me.hasRole(Roles.EMERGENCY_APPROVER) : author || approver);
        return new ComTypes.ManageDetail(m.publicId(), m.category(), m.classification(), m.status(), m.priority(), m.requiresAck(),
                m.effectiveAt(), m.expiresAt(), m.publishedAt(), m.withdrawnReason(), m.currentVersion(), m.titleEn(), m.bodyEn(),
                m.titleSw(), m.bodySw(), m.authorPublicId(), m.authorName(), m.approverName(), m.approvedAt(), audiences, versions, decisions,
                attachments(m.id()), c.recipients(), c.read(), c.acknowledged(), editable, editable, decide, withdraw, amend);
    }

    // ---------------------------------------------------------------- reach (SCR-COM-05, FR-COM-14)

    @Transactional
    public ComTypes.Reach reach(UUID publicId) {
        IccpPrincipal me = CurrentUser.get();
        record A(long id, String title, boolean requiresAck, long authorStaffId) { }
        A a = jdbc.sql("""
                SELECT a.id, coalesce(v.title_en, v.title_sw) AS title, a.requires_ack, a.author_staff_id
                  FROM com.announcement a JOIN com.announcement_version v ON v.announcement_id = a.id AND v.version_no = a.current_version
                 WHERE a.public_id = :p
                """).param("p", publicId).query(A.class).optional().orElseThrow(ApiException::notFound);
        boolean full = me.hasAnyRole(Roles.COMMS_APPROVER, Roles.EXECUTIVE) || (me.hasRole(Roles.COMMS_PUBLISHER) && a.authorStaffId() == me.staffId());
        Set<Long> scope = new HashSet<>();
        if (!full) {
            if (!me.hasAnyRole(Roles.DIRECTOR, Roles.LINE_MANAGER)) {
                throw ApiException.notFound();
            }
            for (UnitRef u : directory.unitsHeadedBy(me.staffId())) {
                scope.addAll(directory.unitSubtree(u.id()));
            }
            if (scope.isEmpty()) {
                throw ApiException.notFound();
            }
        }
        String unitFilter = full ? "" : " AND s.org_unit_id IN (:units)";
        Map<String, Object> params = full ? Map.of("a", a.id()) : Map.of("a", a.id(), "units", List.copyOf(scope));
        List<ComTypes.UnitReach> byUnit = jdbc.sql("""
                SELECT u.code AS unit_code, u.name_en AS unit_name, count(*) AS targeted, count(r.first_read_at) AS read,
                       count(r.acknowledged_at) AS acknowledged
                  FROM com.announcement_recipient r
                  JOIN dir.staff_member s ON s.id = r.staff_id
                  JOIN dir.org_unit u ON u.id = s.org_unit_id
                 WHERE r.announcement_id = :a""" + unitFilter + " GROUP BY u.code, u.name_en ORDER BY u.name_en")
                .params(params).query(ComTypes.UnitReach.class).list();
        long targeted = byUnit.stream().mapToLong(ComTypes.UnitReach::targeted).sum();
        long read = byUnit.stream().mapToLong(ComTypes.UnitReach::read).sum();
        long ack = byUnit.stream().mapToLong(ComTypes.UnitReach::acknowledged).sum();
        long delivered = jdbc.sql("SELECT count(*) FROM com.announcement_recipient r JOIN dir.staff_member s ON s.id = r.staff_id WHERE r.announcement_id = :a AND r.delivered_at IS NOT NULL" + unitFilter)
                .params(params).query(Long.class).single();
        List<ComTypes.PendingPerson> pending = !a.requiresAck() ? List.of() : jdbc.sql("""
                SELECT s.public_id, concat_ws(' ', nullif(s.title, ''), s.first_name, s.last_name) AS display_name, u.name_en AS unit_name
                  FROM com.announcement_recipient r
                  JOIN dir.staff_member s ON s.id = r.staff_id
                  JOIN dir.org_unit u ON u.id = s.org_unit_id
                 WHERE r.announcement_id = :a AND r.acknowledged_at IS NULL""" + unitFilter + " ORDER BY u.name_en, s.last_name LIMIT 500")
                .params(params).query(ComTypes.PendingPerson.class).list();
        audit.record("COM", "REACH_VIEW", "announcement", publicId, Map.of("scoped", !full));
        return new ComTypes.Reach(publicId, a.title(), a.requiresAck(), targeted, delivered, read, ack, byUnit, pending);
    }

    // ---------------------------------------------------------------- attachments

    public record Download(String fileName, String contentType, long size, InputStream stream) {
    }

    @Transactional(readOnly = true)
    public Download download(long attachmentId) {
        IccpPrincipal me = CurrentUser.get();
        record Att(String objectKey, String fileName, String contentType, long sizeBytes, long authorStaffId, boolean recipient) { }
        Att att = jdbc.sql("""
                SELECT x.object_key, x.file_name, x.content_type, x.size_bytes, a.author_staff_id,
                       EXISTS (SELECT 1 FROM com.announcement_recipient r WHERE r.announcement_id = a.id AND r.staff_id = :me) AS recipient
                  FROM com.announcement_attachment x JOIN com.announcement a ON a.id = x.announcement_id
                 WHERE x.id = :id
                """).param("id", attachmentId).param("me", me.staffId()).query(Att.class).optional().orElseThrow(ApiException::notFound);
        if (!att.recipient() && att.authorStaffId() != me.staffId() && !me.hasRole(Roles.COMMS_APPROVER)) {
            throw ApiException.notFound();
        }
        return new Download(att.fileName(), att.contentType(), att.sizeBytes(), files.open(att.objectKey()));
    }

    private List<ComTypes.Attachment> attachments(long announcementId) {
        return jdbc.sql("SELECT id, file_name, content_type, size_bytes FROM com.announcement_attachment WHERE announcement_id = :a ORDER BY id")
                .param("a", announcementId).query(ComTypes.Attachment.class).list();
    }
}
