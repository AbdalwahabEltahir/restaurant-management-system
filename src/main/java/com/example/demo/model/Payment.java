package com.example.demo.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** A record of a table's bill being settled, kept for the receipt and for audit history. */
@Document("payments")
public class Payment {

    /** COUNTER: paid to staff in person. STRIPE: paid by the guest through Stripe Checkout. */
    public enum Method {
        COUNTER, STRIPE
    }

    /** PENDING: a Stripe Checkout session was created but not confirmed yet. PAID: money received. */
    public enum Status {
        PENDING, PAID
    }

    @Id
    private String id;

    @Indexed
    private String tableId;

    /** Null until a phone number is linked, same as on the orders it covers. */
    private String customerId;

    private List<String> orderIds;
    private Method method;
    private BigDecimal amount;
    private Status status;

    /** True if the 15% loyalty discount was redeemed for this bill. */
    private boolean discountApplied;

    /** Set once the Stripe Checkout session for this payment is created. STRIPE payments only. */
    private String stripeSessionId;

    /** The gateway's own transaction reference, set once Stripe confirms the payment. */
    private String providerRef;

    private Instant createdAt = Instant.now();

    public Payment() {
    }

    public Payment(String tableId, String customerId, List<String> orderIds,
                   Method method, BigDecimal amount, Status status) {
        this.tableId = tableId;
        this.customerId = customerId;
        this.orderIds = orderIds;
        this.method = method;
        this.amount = amount;
        this.status = status;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTableId() { return tableId; }
    public void setTableId(String tableId) { this.tableId = tableId; }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public List<String> getOrderIds() { return orderIds; }
    public void setOrderIds(List<String> orderIds) { this.orderIds = orderIds; }

    public Method getMethod() { return method; }
    public void setMethod(Method method) { this.method = method; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public boolean isDiscountApplied() { return discountApplied; }
    public void setDiscountApplied(boolean discountApplied) { this.discountApplied = discountApplied; }

    public String getStripeSessionId() { return stripeSessionId; }
    public void setStripeSessionId(String stripeSessionId) { this.stripeSessionId = stripeSessionId; }

    public String getProviderRef() { return providerRef; }
    public void setProviderRef(String providerRef) { this.providerRef = providerRef; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
