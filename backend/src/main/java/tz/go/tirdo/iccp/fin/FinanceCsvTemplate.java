package tz.go.tirdo.iccp.fin;

/** A fillable Finance diary CSV skeleton matching {@link FinanceImportService}'s record layout. */
public final class FinanceCsvTemplate {
    private FinanceCsvTemplate() {}

    public static String content() {
        return """
                H,REPLACE-FILE-ID,2026-01-31T17:00:00+03:00,FINANCE,2026-01
                C,REPLACE-CLAIM-REF,REPLACE-EMPLOYEE-NO,TRAVEL,REPLACE-DESCRIPTION,0.00,TZS,2026-01-31,REPLACE-VOUCHER-REF,APPROVED
                P,REPLACE-PAYMENT-REF,REPLACE-EMPLOYEE-NO,0.00,TZS,2026-01-31,REPLACE-BANK-REF,CONFIRMED
                A,REPLACE-ALLOCATION-REF,REPLACE-PAYMENT-REF,REPLACE-CLAIM-REF,0.00,
                J,REPLACE-ADJUSTMENT-REF,REPLACE-CLAIM-REF,0.00,REPLACE-REASON
                T,4,0.00,0.00,0.00
                """;
    }
}
