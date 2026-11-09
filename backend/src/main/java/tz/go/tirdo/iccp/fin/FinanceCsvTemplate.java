package tz.go.tirdo.iccp.fin;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** A labeled, fillable Finance diary CSV matching {@link FinanceImportService}'s record layout. */
public final class FinanceCsvTemplate {
    private FinanceCsvTemplate() {}

    public record Generated(String signature, String content) {}

    public static Generated generate() {
        return generate(UUID.randomUUID(), OffsetDateTime.now());
    }

    static Generated generate(UUID id, OffsetDateTime generatedAt) {
        String signature = id.toString().toUpperCase();
        String fileId = "ICCP-DIARY-" + signature;
        String content = """
                #TEMPLATE_SIGNATURE,%s
                #H,file_id,extracted_at,source_system,period
                H,%s,%s,FINANCE,%s
                #C,claim_source_ref,employee_no,claim_type_code,description,approved_amount,currency,approval_date,voucher_ref,status
                C,CLM-2026-0412,TR1007,TRAVEL,Travel allowance - Mwanza field sampling,1200000.00,TZS,2026-07-14,PV-26-1180,APPROVED
                #P,payment_source_ref,employee_no,amount,currency,paid_on,payment_ref,status
                P,PAY-2026-0901,TR1007,1200000.00,TZS,2026-07-28,NMB-77120931,CONFIRMED
                #A,allocation_source_ref,payment_source_ref,claim_source_ref,amount,reverses_source_ref
                A,ALC-2026-1501,PAY-2026-0901,CLM-2026-0412,1200000.00,
                #J,adjustment_source_ref,claim_source_ref,amount,reason (copy this header into a J row only when an adjustment is needed)
                #T,detail_row_count,claims_total,payments_total,allocations_total
                T,3,1200000.00,1200000.00,1200000.00
                """.formatted(signature, fileId, generatedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), YearMonth.from(generatedAt));
        return new Generated(signature, content);
    }
}
