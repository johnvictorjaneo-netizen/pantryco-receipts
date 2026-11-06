
package com.pantryco.receipt;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

@RestController
public class ReceiptController {

    private final ReceiptRepository repo;

    @Value("${pantry.sync-key}")
    private String syncKey;

    public ReceiptController(ReceiptRepository repo) {
        this.repo = repo;
    }

    // ==========================================
    // DATA TRANSFER OBJECTS
    // ==========================================

    public record LineDto(
            String sku,
            String name,
            int quantity,
            double unitPrice
    ) {}

    public record ReceiptDto(
            String token,
            String receiptNo,
            LocalDateTime soldAt,
            double subtotal,
            double vat,
            double total,
            double cashTendered,
            double changeDue,
            List<LineDto> items
    ) {}

    // ==========================================
    // UPLOAD RECEIPT FROM LOCAL POS
    // ==========================================

    @PostMapping("/internal/receipts")
    public ResponseEntity<Void> upload(
            @RequestHeader(
                    value = "X-Pantry-Sync-Key",
                    required = false
            ) String key,
            @RequestBody ReceiptDto data) {

        if (syncKey == null ||
                syncKey.isBlank() ||
                key == null ||
                !MessageDigest.isEqual(
                        syncKey.getBytes(StandardCharsets.UTF_8),
                        key.getBytes(StandardCharsets.UTF_8))) {

            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Invalid synchronization key");
        }

        if (data == null ||
                data.token() == null ||
                !data.token().matches("[a-fA-F0-9]{64}") ||
                data.receiptNo() == null ||
                data.receiptNo().isBlank() ||
                data.soldAt() == null ||
                data.items() == null ||
                data.items().isEmpty() ||
                data.items().size() > 500 ||
                !valid(data.subtotal()) ||
                !valid(data.vat()) ||
                !valid(data.total()) ||
                !valid(data.cashTendered()) ||
                !valid(data.changeDue())) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid receipt");
        }

        for (LineDto item : data.items()) {

            if (item == null ||
                    item.name() == null ||
                    item.name().length() > 250 ||
                    item.quantity() <= 0 ||
                    !valid(item.unitPrice())) {

                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Invalid receipt item");
            }
        }

        Optional<Receipt> existing =
                repo.findById(data.token());

        if (existing.isPresent()) {

            if (!existing.get().receiptNo
                    .equals(data.receiptNo())) {

                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Receipt token already used");
            }

            return ResponseEntity.ok().build();
        }

        Receipt receipt = new Receipt();

        receipt.token = data.token();
        receipt.receiptNo = data.receiptNo();
        receipt.soldAt = data.soldAt();

        receipt.subtotal = data.subtotal();
        receipt.vat = data.vat();
        receipt.total = data.total();

        receipt.cashTendered = data.cashTendered();
        receipt.changeDue = data.changeDue();

        for (LineDto item : data.items()) {

            receipt.items.add(
                    new ReceiptLine(
                            item.sku(),
                            item.name(),
                            item.quantity(),
                            item.unitPrice()
                    )
            );
        }

        repo.save(receipt);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .build();
    }

    // ==========================================
    // VALIDATION
    // ==========================================

    private boolean valid(double value) {
        return Double.isFinite(value) && value >= 0;
    }

    private Receipt receipt(String token) {

        if (token == null ||
                !token.matches("[a-fA-F0-9]{64}")) {

            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Invalid receipt token");
        }

        return repo.findById(token)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Receipt not found"));
    }

    // ==========================================
    // HTML ESCAPING
    // ==========================================

    private String esc(String text) {

        if (text == null) {
            return "";
        }

        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    // ==========================================
    // MONEY FORMAT
    // ==========================================

    private String money(double amount) {

        return String.format(
                Locale.US,
                "%.2f",
                amount);
    }

    // ==========================================
    // ONLINE DIGITAL RECEIPT
    // ==========================================

    @GetMapping(
            value = "/r/{token}",
            produces = MediaType.TEXT_HTML_VALUE
    )
    public String view(
            @PathVariable String token) {

        Receipt r = receipt(token);

        StringBuilder rows = new StringBuilder();

        for (ReceiptLine item : r.items) {

            rows.append("<tr>")
                    .append("<td>")
                    .append(esc(item.name))
                    .append("</td>")
                    .append("<td>")
                    .append(item.quantity)
                    .append("</td>")
                    .append("<td>PHP ")
                    .append(money(item.unitPrice))
                    .append("</td>")
                    .append("<td>PHP ")
                    .append(money(
                            item.quantity * item.unitPrice))
                    .append("</td>")
                    .append("</tr>");
        }

        String html = """
                <!DOCTYPE html>
                <html lang="en">

                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport"
                          content="width=device-width,
                          initial-scale=1">

                    <meta name="robots"
                          content="noindex,nofollow">

                    <title>Pantry &amp; Co. Receipt</title>

                    <style>

                        * {
                            box-sizing: border-box;
                        }

                        body {
                            margin: 0;
                            padding: 20px;
                            background: #f1f6f3;
                            font-family: Arial, sans-serif;
                            color: #183d2c;
                        }

                        .receipt {
                            max-width: 620px;
                            margin: 25px auto;
                            padding: 28px;
                            background: white;
                            border-radius: 14px;
                            box-shadow: 0 4px 18px
                                rgba(0,0,0,0.08);
                        }

                        h1 {
                            text-align: center;
                            color: #18794e;
                            margin-bottom: 5px;
                        }

                        .subtitle {
                            text-align: center;
                            font-size: 13px;
                            color: #777;
                            letter-spacing: 2px;
                        }

                        .details {
                            margin-top: 25px;
                            line-height: 1.8;
                        }

                        table {
                            width: 100%%;
                            border-collapse: collapse;
                            margin-top: 20px;
                            font-size: 14px;
                        }

                        th, td {
                            padding: 10px 5px;
                            border-bottom: 1px solid #ddd;
                            text-align: left;
                        }

                        th {
                            background: #f4f8f5;
                        }

                        .summary {
                            margin-top: 20px;
                            line-height: 1.8;
                        }

                        .total {
                            font-size: 22px;
                            font-weight: bold;
                            color: #18794e;
                        }

                        .button {
                            display: block;
                            background: #18794e;
                            color: white;
                            text-align: center;
                            text-decoration: none;
                            padding: 16px;
                            border-radius: 8px;
                            font-weight: bold;
                            margin-top: 25px;
                        }

                        .button:hover {
                            background: #105a39;
                        }

                        .footer {
                            text-align: center;
                            margin-top: 25px;
                            font-size: 13px;
                            color: #777;
                        }

                        @media(max-width: 480px) {

                            .receipt {
                                padding: 16px;
                            }

                            table {
                                font-size: 12px;
                            }
                        }

                    </style>
                </head>

                <body>

                    <main class="receipt">

                        <h1>Pantry &amp; Co.</h1>

                        <p class="subtitle">
                            DIGITAL SALES RECEIPT
                        </p>

                        <hr>

                        <div class="details">

                            <p>
                                <b>Receipt No:</b> %s
                            </p>

                            <p>
                                <b>Date:</b> %s
                            </p>

                        </div>

                        <table>

                            <thead>
                                <tr>
                                    <th>Product</th>
                                    <th>Qty</th>
                                    <th>Unit</th>
                                    <th>Amount</th>
                                </tr>
                            </thead>

                            <tbody>
                                %s
                            </tbody>

                        </table>

                        <div class="summary">

                            <p>
                                Subtotal: PHP %s
                            </p>

                            <p>
                                VAT Included (12%%): PHP %s
                            </p>

                            <hr>

                            <p class="total">
                                TOTAL: PHP %s
                            </p>

                            <p>
                                Cash: PHP %s
                            </p>

                            <p>
                                Change: PHP %s
                            </p>

                        </div>

                        <a class="button"
                           href="/r/%s/pdf">

                            Download Receipt (PDF)

                        </a>

                        <p class="footer">
                            Thank you for shopping!
                        </p>

                    </main>

                </body>
                </html>
                """;

        return html.formatted(
                esc(r.receiptNo),
                esc(r.soldAt.toString()),
                rows.toString(),
                money(r.subtotal),
                money(r.vat),
                money(r.total),
                money(r.cashTendered),
                money(r.changeDue),
                token
        );
    }

    // ==========================================
    // DOWNLOAD RECEIPT AS PDF
    // ==========================================

    @GetMapping(
            value = "/r/{token}/pdf",
            produces = MediaType.APPLICATION_PDF_VALUE
    )
    public ResponseEntity<byte[]> pdf(
            @PathVariable String token) throws Exception {

        Receipt r = receipt(token);

        try (
                PDDocument document = new PDDocument();
                ByteArrayOutputStream output =
                        new ByteArrayOutputStream()
        ) {

            List<String> lines = new ArrayList<>();

            lines.add(
                    "PANTRY & CO. - SALES RECEIPT");

            lines.add(
                    "Receipt: " + r.receiptNo);

            lines.add(
                    "Date: " + r.soldAt);

            lines.add(
                    "---------------------------------------------");

            for (ReceiptLine item : r.items) {

                String name =
                        item.name == null
                                ? ""
                                : item.name.replaceAll(
                                        "[^ -~]", "?");

                if (name.length() > 48) {

                    name = name.substring(0, 48);
                }

                lines.add(name);

                lines.add(
                        "  " + item.quantity
                                + " x PHP "
                                + money(item.unitPrice)
                                + " = PHP "
                                + money(
                                        item.quantity
                                                * item.unitPrice));
            }

            lines.add(
                    "---------------------------------------------");

            lines.add(
                    "Subtotal: PHP "
                            + money(r.subtotal));

            lines.add(
                    "VAT included (12%): PHP "
                            + money(r.vat));

            lines.add(
                    "TOTAL: PHP "
                            + money(r.total));

            lines.add(
                    "Cash: PHP "
                            + money(r.cashTendered));

            lines.add(
                    "Change: PHP "
                            + money(r.changeDue));

            lines.add(
                    "Thank you for shopping!");

            PDPageContentStream stream = null;

            float y = 0;

            try {

                for (String line : lines) {

                    if (stream == null || y < 55) {

                        if (stream != null) {
                            stream.close();
                        }

                        PDPage page =
                                new PDPage(PDRectangle.A4);

                        document.addPage(page);

                        stream =
                                new PDPageContentStream(
                                        document,
                                        page);

                        y = PDRectangle.A4.getHeight() - 55;
                    }

                    stream.beginText();

                    stream.setFont(
                            PDType1Font.COURIER,
                            10);

                    stream.newLineAtOffset(45, y);

                    stream.showText(
                            line.replaceAll(
                                    "[^ -~]", "?"));

                    stream.endText();

                    y -= 17;
                }

            } finally {

                if (stream != null) {
                    stream.close();
                }
            }

            document.save(output);

            String filename =
                    "pantry-receipt-"
                            + r.receiptNo.replaceAll(
                                    "[^A-Za-z0-9_-]", "_")
                            + ".pdf";

            return ResponseEntity.ok()
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\""
                                    + filename + "\"")
                    .cacheControl(CacheControl.noStore())
                    .contentType(
                            MediaType.APPLICATION_PDF)
                    .body(output.toByteArray());
        }
    }
}
