package tz.go.tirdo.iccp.com;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tz.go.tirdo.iccp.dir.api.AudienceRule;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Request and response shapes for the COM module (SCR-COM-01..05). */
public final class ComTypes {

    private ComTypes() {
    }

    public static final Set<String> NEEDS_APPROVAL = Set.of("CIRCULAR", "DIRECTIVE_NOTICE");
    public static final String EMERGENCY = "EMERGENCY_ALERT";

    public record AnnouncementInput(
            @NotNull @Pattern(regexp = "NOTICE|CIRCULAR|DIRECTIVE_NOTICE|EMERGENCY_ALERT|EVENT") String category,
            @Pattern(regexp = "PUBLIC|INTERNAL|RESTRICTED|CONFIDENTIAL") String classification,
            boolean requiresAck,
            @Min(1) @Max(5) Integer priority,
            OffsetDateTime effectiveAt,
            OffsetDateTime expiresAt,
            @Size(max = 200) String titleEn,
            @Size(max = 20000) String bodyEn,
            @Size(max = 200) String titleSw,
            @Size(max = 20000) String bodySw,
            @NotEmpty @Valid List<AudienceRule> audiences,
            @Size(max = 500) String changeNote) {
    }

    public record DecisionInput(@Size(max = 1000) String comment) {
    }

    public record AudiencePreview(@NotEmpty @Valid List<AudienceRule> audiences) {
    }

    public record FeedItem(UUID publicId, String category, int priority, String title, String excerpt, OffsetDateTime publishedAt,
                           OffsetDateTime effectiveAt, OffsetDateTime expiresAt, boolean requiresAck, OffsetDateTime readAt,
                           OffsetDateTime acknowledgedAt, int attachmentCount, int currentVersion) {
    }

    public record Attachment(long id, String fileName, String contentType, long sizeBytes, String scanStatus) {
    }

    public record VersionInfo(int versionNo, String titleEn, String titleSw, String changeNote, String createdBy, OffsetDateTime createdAt) {
    }

    public record FeedDetail(UUID publicId, String category, String classification, int priority, String titleEn, String bodyEn,
                             String titleSw, String bodySw, OffsetDateTime publishedAt, OffsetDateTime effectiveAt, OffsetDateTime expiresAt,
                             boolean requiresAck, OffsetDateTime readAt, OffsetDateTime acknowledgedAt, int currentVersion,
                             String changeNote, String authorUnit, List<Attachment> attachments) {
    }

    public record ManageItem(UUID publicId, String category, String status, int priority, String titleEn, String titleSw,
                             String authorName, OffsetDateTime effectiveAt, OffsetDateTime expiresAt, OffsetDateTime publishedAt,
                             OffsetDateTime updatedAt, boolean requiresAck, long recipients, long acknowledged) {
    }

    public record Decision(int stepNo, String approverName, String decision, String comment, OffsetDateTime decidedAt) {
    }

    public record ManageDetail(UUID publicId, String category, String classification, String status, int priority, boolean requiresAck,
                               OffsetDateTime effectiveAt, OffsetDateTime expiresAt, OffsetDateTime publishedAt, String withdrawnReason,
                               int currentVersion, String titleEn, String bodyEn, String titleSw, String bodySw, UUID authorPublicId,
                               String authorName, String approverName, OffsetDateTime approvedAt, List<AudienceRule> audiences,
                               List<VersionInfo> versions, List<Decision> decisions, List<Attachment> attachments,
                               long recipients, long read, long acknowledged, boolean canEdit, boolean canSubmit,
                               boolean canDecide, boolean canWithdraw, boolean canAmend) {
    }

    public record UnitReach(String unitCode, String unitName, long targeted, long read, long acknowledged) {
    }

    public record PendingPerson(UUID publicId, String displayName, String unitName) {
    }

    public record Reach(UUID publicId, String title, boolean requiresAck, long targeted, long delivered, long read, long acknowledged,
                        List<UnitReach> byUnit, List<PendingPerson> notAcknowledged) {
    }
}
