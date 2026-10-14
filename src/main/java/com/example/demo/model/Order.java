package com.example.demo.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("orders")
public class Order {

    /** The steps an order goes through, and which step may follow which. */
    public enum Status {
        PENDING, PREPARING, READY, SERVED, PAID, CANCELLED;

        public boolean canMoveTo(Status next) {
            return switch (this) {
                case PENDING -> next == PREPARING || next == CANCELLED;
                case PREPARING -> next == READY;
                case READY -> next == SERVED;
                case SERVED -> next == PAID;
                case PAID, CANCELLED -> false;
            };
        }
    }

    /** One line of the order. Name and price are copied from the menu at order time. */
    public static class Item {
        private String menuItemId;
        private String name;
        private BigDecimal unitPrice;
        private int quantity;
        private String notes;

        public Item() {
        }

        public Item(String menuItemId, String name, BigDecimal unitPrice, int quantity, String notes) {
            this.menuItemId = menuItemId;
            this.name = name;
            this.unitPrice = unitPrice;
            this.quantity = quantity;
            this.notes = notes;
        }

        public BigDecimal lineTotal() {
            return unitPrice.multiply(BigDecimal.valueOf(quantity));
        }

        public String getMenuItemId() { return menuItemId; }
        public void setMenuItemId(String menuItemId) { this.menuItemId = menuItemId; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public BigDecimal getUnitPrice() { return unitPrice; }
        public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

        public int getQuantity() { return quantity; }
        public void setQuantity(int quantity) { this.quantity = quantity; }

        public String getNotes() { return notes; }
        public void setNotes(String notes) { this.notes = notes; }
    }

    @Id
    private String id;

    @Indexed
    private String tableId;

    private String customerId;      // stays null until the guest gives a phone number
    private List<Item> items = new ArrayList<>();
    private Status status = Status.PENDING;
    private Instant createdAt = Instant.now();

    public Order() {
    }

    public Order(String tableId, List<Item> items) {
        this.tableId = tableId;
        this.items = items;
    }

    /** Total of all items, before any loyalty discount. */
    public BigDecimal subtotal() {
        return items.stream()
                .map(Item::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTableId() { return tableId; }
    public void setTableId(String tableId) { this.tableId = tableId; }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public List<Item> getItems() { return items; }
    public void setItems(List<Item> items) { this.items = items; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}