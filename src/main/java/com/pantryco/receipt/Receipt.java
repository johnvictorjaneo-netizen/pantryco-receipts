package com.pantryco.receipt;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.*;
@Entity
@Table(name="public_receipts")
public class Receipt {
    @Id @Column(length=64) public String token;
    @Column(nullable=false) public String receiptNo;
    public LocalDateTime soldAt;
    public double subtotal, vat, total, cashTendered, changeDue;
    @ElementCollection(fetch=FetchType.EAGER)
    @CollectionTable(name="public_receipt_items", joinColumns=@JoinColumn(name="receipt_token"))
    @OrderColumn(name="line_order")
    public List<ReceiptLine> items = new ArrayList<>();
}
