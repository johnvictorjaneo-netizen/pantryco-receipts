package com.pantryco.receipt;
import jakarta.persistence.*;
@Embeddable
public class ReceiptLine {
    public String sku;
    @Column(name="product_name") public String name;
    public int quantity;
    public double unitPrice;
    public ReceiptLine() {}
    public ReceiptLine(String sku,String name,int quantity,double unitPrice) {
        this.sku=sku;this.name=name;this.quantity=quantity;this.unitPrice=unitPrice;
    }
}
