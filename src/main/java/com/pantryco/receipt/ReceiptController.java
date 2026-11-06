package com.pantryco.receipt;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.*;

@RestController
public class ReceiptController {
    private final ReceiptRepository repo;
    @Value("${pantry.sync-key}") private String syncKey;
    public ReceiptController(ReceiptRepository repo) { this.repo=repo; }

    public record LineDto(String sku,String name,int quantity,double unitPrice) {}
    public record ReceiptDto(String token,String receiptNo,LocalDateTime soldAt,
        double subtotal,double vat,double total,double cashTendered,double changeDue,List<LineDto> items) {}

    @PostMapping("/internal/receipts")
    public ResponseEntity<Void> upload(@RequestHeader(value="X-Pantry-Sync-Key",required=false) String key,
                                        @RequestBody ReceiptDto data) {
        if (syncKey == null || syncKey.isBlank() || key == null ||
            !java.security.MessageDigest.isEqual(syncKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                key.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (data.token()==null || !data.token().matches("[a-fA-F0-9]{64}") ||
            data.receiptNo()==null || data.receiptNo().isBlank() || data.soldAt()==null ||
            data.items()==null || data.items().isEmpty() || data.items().size()>500 ||
            !valid(data.subtotal()) || !valid(data.vat()) || !valid(data.total()) ||
            !valid(data.cashTendered()) || !valid(data.changeDue()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid receipt");
        for (LineDto i:data.items()) {
            if (i==null || i.name()==null || i.name().length()>250 || i.quantity()<=0 || !valid(i.unitPrice()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid receipt item");
        }
        Optional<Receipt> existing=repo.findById(data.token());
        if (existing.isPresent()) {
            if (!existing.get().receiptNo.equals(data.receiptNo()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Token already used");
            return ResponseEntity.ok().build();
        }
        Receipt r=new Receipt();
        r.token=data.token(); r.receiptNo=data.receiptNo();r.soldAt=data.soldAt();
        r.subtotal=data.subtotal();r.vat=data.vat();r.total=data.total();
        r.cashTendered=data.cashTendered();r.changeDue=data.changeDue();
        for(LineDto i:data.items())r.items.add(new ReceiptLine(i.sku(),i.name(),i.quantity(),i.unitPrice()));
        repo.save(r);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
    private boolean valid(double v) {return Double.isFinite(v) && v>=0;}
    private Receipt receipt(String token) {
        if(token==null || !token.matches("[a-fA-F0-9]{64}"))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return repo.findById(token).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    private String esc(String s) {
        if(s==null)return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
                .replace(""","&quot;").replace("'","&#39;");
    }
    private String money(double n) {return String.format(Locale.US,"%.2f",n);}

    @GetMapping(value="/r/{token}",produces=MediaType.TEXT_HTML_VALUE)
    public String view(@PathVariable String token) {
        Receipt r=receipt(token);
        StringBuilder rows=new StringBuilder();
        for(ReceiptLine i:r.items) rows.append("<tr><td>").append(esc(i.name))
            .append("</td><td>").append(i.quantity).append("</td><td>PHP ")
            .append(money(i.unitPrice)).append("</td><td>PHP ")
            .append(money(i.quantity*i.unitPrice)).append("</td></tr>");
        return "<!doctype html><html lang='en'><head><meta charset='utf-8'>"+
            "<meta name='viewport' content='width=device-width,initial-scale=1'>"+
            "<meta name='robots' content='noindex,nofollow'>"+
            "<title>Pantry &amp; Co. Receipt</title>"+
            "<style>body{font:16px Arial;background:#f1f6f3;color:#183d2c;padding:20px}"+
            ".card{background:white;max-width:620px;margin:20px auto;padding:26px;border-radius:14px}"+
            "h1{text-align:center}table{width:100%;border-collapse:collapse;font-size:14px}"+
            "th,td{padding:9px 4px;border-bottom:1px solid #ddd;text-align:left}"+
            ".button{display:block;text-align:center;background:#18794e;color:white;padding:14px;"+
            "border-radius:8px;text-decoration:none;margin-top:22px} .sum{font-weight:bold;font-size:20px}"+
            "</style></head><body><main class='card'><h1>Pantry &amp; Co.</h1>"+
            "<p style='text-align:center'>DIGITAL SALES RECEIPT</p>"+
            "<p><b>Receipt:</b> "+esc(r.receiptNo)+"</p><p><b>Date:</b> "+esc(r.soldAt.toString())+"</p>"+
            "<table><thead><tr><th>Product</th><th>Qty</th><th>Unit</th><th>Amount</th></tr></thead><tbody>"+
            rows+"</tbody></table>"+
            "<p>Subtotal: PHP "+money(r.subtotal)+"</p><p>VAT included (12%): PHP "+money(r.vat)+"</p>"+
            "<p class='sum'>TOTAL: PHP "+money(r.total)+"</p>"+
            "<p>Cash: PHP "+money(r.cashTendered)+"</p>"+
            "<p>Change: PHP "+money(r.changeDue)+"</p>"+
            "<a class='button' href='/r/"+token+"/pdf'>Download Receipt (PDF)</a>"+
            "<p style='text-align:center'>Thank you for shopping!</p></main></body></html>";
    }

    @GetMapping(value="/r/{token}/pdf",produces=MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable String token) throws Exception {
        Receipt r=receipt(token);
        try(PDDocument doc=new PDDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            List<String> lines=new ArrayList<>();
            lines.add("PANTRY & CO. - SALES RECEIPT");
            lines.add("Receipt: "+r.receiptNo);
            lines.add("Date: "+r.soldAt);
            lines.add("---------------------------------------------");
            for(ReceiptLine i:r.items) {
                String name=i.name==null?"":i.name.replaceAll("[^ -~]","?");
                if(name.length()>48)name=name.substring(0,48);
                lines.add(name);
                lines.add("  "+i.quantity+" x PHP "+money(i.unitPrice)+" = PHP "+money(i.quantity*i.unitPrice));
            }
            lines.add("---------------------------------------------");
            lines.add("Subtotal: PHP "+money(r.subtotal));
            lines.add("VAT included (12%): PHP "+money(r.vat));
            lines.add("TOTAL: PHP "+money(r.total));
            lines.add("Cash: PHP "+money(r.cashTendered));
            lines.add("Change: PHP "+money(r.changeDue));
            lines.add("Thank you for shopping!");
            PDPage page=null;PDPageContentStream stream=null;float y=0;
            try {
                for(String line:lines) {
                    if(stream==null || y<55) {
                        if(stream!=null)stream.close();
                        page=new PDPage(PDRectangle.A4);doc.addPage(page);
                        stream=new PDPageContentStream(doc,page);y=PDRectangle.A4.getHeight()-55;
                    }
                    stream.beginText();stream.setFont(PDType1Font.COURIER,10);
                    stream.newLineAtOffset(45,y);
                    stream.showText(line.replaceAll("[^ -~]","?"));
                    stream.endText();y-=17;
                }
            } finally {if(stream!=null)stream.close();}
            doc.save(out);
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=pantry-receipt-"+r.receiptNo+".pdf")
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PDF).body(out.toByteArray());
        }
    }
}
