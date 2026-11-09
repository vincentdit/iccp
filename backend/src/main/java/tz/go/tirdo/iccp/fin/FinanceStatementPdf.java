package tz.go.tirdo.iccp.fin;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.format.DateTimeFormatter;

/** Produces the staff-owned printable statement required by FR-FIN-13. */
@Component
public final class FinanceStatementPdf {
    private static final PDType1Font REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm xxx");
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");

    public byte[] generate(FinTypes.Statement statement) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PageWriter page = new PageWriter(document, statement);
            page.heading();
            for (FinTypes.Summary total : statement.summaries()) page.total(total);
            page.claimHeader();
            for (FinTypes.ClaimLine claim : statement.claims()) page.claim(claim);
            if (statement.claims().isEmpty()) page.line("No claims were recorded in this period.", 10, REGULAR);
            page.finish();
            document.save(output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not generate the Finance statement PDF.", e);
        }
    }

    private static final class PageWriter {
        private final PDDocument document;
        private final FinTypes.Statement statement;
        private PDPageContentStream out;
        private float y;
        private int pageNo;

        PageWriter(PDDocument document, FinTypes.Statement statement) throws IOException {
            this.document = document;
            this.statement = statement;
            nextPage();
        }

        void nextPage() throws IOException {
            if (out != null) footer();
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            out = new PDPageContentStream(document, page);
            y = 790;
            pageNo++;
        }

        void heading() throws IOException {
            try (InputStream logo = FinanceStatementPdf.class.getResourceAsStream("/assets/tirdo-logo.jpg")) {
                if (logo == null) throw new IllegalStateException("TIRDO logo resource is missing.");
                PDImageXObject image = PDImageXObject.createFromByteArray(document, logo.readAllBytes(), "tirdo-logo");
                float logoWidth = 50;
                float logoHeight = 66;
                float logoX = (PDRectangle.A4.getWidth() - logoWidth) / 2;
                float logoY = y - logoHeight;
                out.drawImage(image, logoX, logoY, logoWidth, logoHeight);
                y = logoY - 18;
            }
            centeredLine("TANZANIA INDUSTRIAL RESEARCH AND DEVELOPMENT ORGANIZATION", 11, BOLD);
            centeredLine("ICCP PERSONAL FINANCIAL STATEMENT", 18, BOLD);
            gap(8);
            line("Staff: " + statement.staffName() + " (" + statement.employeeNo() + ")", 10, REGULAR);
            line("Period: " + DATE.format(statement.from()) + " to " + DATE.format(statement.to()), 10, REGULAR);
            line("Generated: " + DATE_TIME.format(statement.generatedAt()), 10, REGULAR);
            line("Finance data as at: " + (statement.asAt() == null ? "No published data" : DATE_TIME.format(statement.asAt())), 10, REGULAR);
            gap(8);
            line("ICCP statement - not a payment voucher", 11, BOLD);
            gap(12);
        }

        void total(FinTypes.Summary s) throws IOException {
            line(s.currency() + "  Approved " + money(s.totalApproved()) + "   Paid " + money(s.totalPaid())
                    + "   Outstanding " + money(s.outstanding()), 10, BOLD);
        }

        void claimHeader() throws IOException {
            gap(12);
            line("CLAIM LEDGER", 12, BOLD);
            line(String.format("%-12s %-15s %-12s %14s %14s %14s", "Date", "Reference", "Status", "Approved", "Paid", "Outstanding"), 8, BOLD);
            rule();
        }

        void claim(FinTypes.ClaimLine c) throws IOException {
            ensure(42);
            line(String.format("%-12s %-15s %-12s %14s %14s %14s", DATE.format(c.approvalDate()), trim(c.sourceRef(), 15),
                    trim(c.paymentStatus(), 12), money(c.approvedAmount().add(c.adjustmentAmount())), money(c.paidAmount()), money(c.outstandingAmount())), 8, REGULAR);
            line(trim(c.description(), 95), 8, REGULAR);
            gap(4);
        }

        void ensure(float height) throws IOException { if (y - height < 65) { nextPage(); claimHeader(); } }
        void gap(float amount) { y -= amount; }
        void rule() throws IOException { out.moveTo(40, y); out.lineTo(555, y); out.stroke(); y -= 8; }
        void line(String text, float size, PDType1Font font) throws IOException {
            lineAt(text, 40, size, font);
        }
        void lineAt(String text, float x, float size, PDType1Font font) throws IOException {
            out.beginText(); out.setFont(font, size); out.newLineAtOffset(x, y); out.showText(ascii(text)); out.endText(); y -= size + 5;
        }
        void centeredLine(String text, float size, PDType1Font font) throws IOException {
            String printable = ascii(text);
            float width = font.getStringWidth(printable) / 1000 * size;
            lineAt(printable, (PDRectangle.A4.getWidth() - width) / 2, size, font);
        }
        void footer() throws IOException {
            out.beginText(); out.setFont(REGULAR, 8); out.newLineAtOffset(40, 30);
            out.showText("Confidential - for the named staff member and authorised Finance officers only   Page " + pageNo);
            out.endText(); out.close(); out = null;
        }
        void finish() throws IOException { footer(); }
        static String money(BigDecimal value) { return MONEY.format(value == null ? BigDecimal.ZERO : value); }
        static String trim(String value, int max) { return value == null ? "" : value.length() <= max ? value : value.substring(0, max - 1) + "."; }
        static String ascii(String value) { return value.replace('\u2013', '-').replace('\u2014', '-').replace('\u2019', '\''); }
    }
}
