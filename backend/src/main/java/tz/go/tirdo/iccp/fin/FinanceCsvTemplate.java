package tz.go.tirdo.iccp.fin;

/** A labeled, fillable Finance diary CSV matching {@link FinanceImportService}'s record layout. */
public final class FinanceCsvTemplate {
    private FinanceCsvTemplate() {}

    public static String content() {
        return """
                #H,file_id,extracted_at,source_system,period
                H,FIN-EXPORT-2026-10-01,2026-10-01T06:00:00+03:00,FINANCE,2026-Q1
                #C,claim_source_ref,employee_no,claim_type_code,description,approved_amount,currency,approval_date,voucher_ref,status
                C,CLM-2026-0412,TR1007,TRAVEL,Travel allowance - Mwanza field sampling,1200000.00,TZS,2026-07-14,PV-26-1180,APPROVED
                #P,payment_source_ref,employee_no,amount,currency,paid_on,payment_ref,status
                P,PAY-2026-0901,TR1007,1200000.00,TZS,2026-07-28,NMB-77120931,CONFIRMED
                #A,allocation_source_ref,payment_source_ref,claim_source_ref,amount,reverses_source_ref
                A,ALC-2026-1501,PAY-2026-0901,CLM-2026-0412,1200000.00,
                #J,adjustment_source_ref,claim_source_ref,amount,reason (copy this header into a J row only when an adjustment is needed)
                #T,detail_row_count,claims_total,payments_total,allocations_total
                T,3,1200000.00,1200000.00,1200000.00
                """;
    }
}
