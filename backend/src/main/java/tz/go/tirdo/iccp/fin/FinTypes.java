package tz.go.tirdo.iccp.fin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for the FIN module (SCR-FIN-01..07). */
public final class FinTypes {

    private FinTypes() {
    }

    // ------------------------------------------------------------ staff diary

    public record Summary(String currency, BigDecimal totalApproved, BigDecimal totalPaid, BigDecimal outstanding, BigDecimal progressPct,
                          int claimCount, int openQueries, OffsetDateTime asAt) {
    }

    public record ClaimLine(UUID publicId, String sourceRef, String claimTypeCode, String claimTypeEn, String claimTypeSw, String description,
                            String currency, LocalDate approvalDate, BigDecimal approvedAmount, BigDecimal paidAmount,
                            BigDecimal adjustmentAmount, BigDecimal outstandingAmount, String paymentStatus) {
    }

    public record Allocation(long id, LocalDate paidOn, String paymentRef, String paymentSourceRef, BigDecimal amount, boolean reversal,
                             OffsetDateTime publishedAt) {
    }

    public record Adjustment(String sourceRef, BigDecimal amount, String reason, OffsetDateTime publishedAt) {
    }

    public record QuerySummary(UUID publicId, String claimRef, String category, String description, String status, int reopenCount,
                               OffsetDateTime dueAt, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record ClaimDetail(ClaimLine claim, String voucherRef, List<Allocation> allocations, List<Adjustment> adjustments,
                              List<QuerySummary> queries) {
    }

    // ------------------------------------------------------------ queries (BP-FIN-02)

    public record NewQuery(@NotBlank @Pattern(regexp = "AMOUNT|MISSING_PAYMENT|WRONG_CLAIM|OTHER") String category,
                           @NotBlank @Size(max = 2000) String description) {
    }

    public record MessageInput(@NotBlank @Size(max = 2000) String body) {
    }

    public record QueryMessage(String authorName, boolean fromFinance, String body, OffsetDateTime createdAt) {
    }

    public record QueryDetail(UUID publicId, UUID claimPublicId, String claimRef, String claimDescription, UUID staffPublicId,
                              String staffName, String category, String description, String status, int reopenCount, OffsetDateTime dueAt,
                              OffsetDateTime createdAt, String assignedTo, List<QueryMessage> messages, boolean canRespond,
                              boolean canConfirm, boolean canReopen) {
    }

    public record DeskItem(UUID publicId, String claimRef, String staffName, String unitName, String category, String status,
                           int reopenCount, OffsetDateTime dueAt, OffsetDateTime createdAt, boolean overdue) {
    }

    // ------------------------------------------------------------ Finance workspace

    public record Batch(UUID publicId, String feedType, String fileName, String status, int sourceRecordCount, BigDecimal sourceAmountTotal,
                        int loadedCount, int quarantinedCount, long openQuarantine, String importedBy, String reviewedBy,
                        OffsetDateTime reviewedAt, OffsetDateTime publishedAt, OffsetDateTime createdAt, boolean canApprove) {
    }

    public record ImportResult(UUID batchPublicId, boolean duplicate, String status, int records, int loaded, int quarantined,
                               String rejectionReason) {
    }

    public record QuarantineItem(long id, int rowNo, String reasonCode, String payload, String status, String resolutionNote,
                                 String resolvedBy, OffsetDateTime resolvedAt) {
    }

    public record CloseQuarantine(@NotBlank @Pattern(regexp = "RESOLVED|REJECTED") String status, @NotBlank @Size(max = 1000) String note) {
    }

    public record ManualAllocation(@NotBlank String paymentSourceRef, @NotBlank String claimSourceRef, BigDecimal amount,
                                   String reversesSourceRef) {
    }

    public record PendingAllocation(long id, String paymentSourceRef, String claimSourceRef, String staffName, BigDecimal amount,
                                    String allocatedBy, OffsetDateTime createdAt, boolean canApprove) {
    }

    public record StatusCount(String status, long count) {
    }

    public record TypeLiability(String claimTypeCode, String claimTypeEn, String currency, long claims, BigDecimal outstanding) {
    }

    public record AgeBucket(String bucket, long count) {
    }

    public record Dashboard(List<StatusCount> batches, long openQuarantine, long pendingManualAllocations, List<AgeBucket> openQueriesByAge,
                            List<TypeLiability> liabilities, BigDecimal totalOutstandingTzs, long staffWithOutstanding, OffsetDateTime asAt) {
    }
}
