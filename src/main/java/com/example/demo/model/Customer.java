package com.example.demo.model;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("customers")
public class Customer {

    /** Loyalty levels and the points rate each one earns. */
    public enum Tier {
        BRONZE("1.0"),
        SILVER("1.25"),
        GOLD("1.5");

        private final BigDecimal rate;

        Tier(String rate) {
            this.rate = new BigDecimal(rate);
        }

        public BigDecimal getRate() {
            return rate;
        }

        /** Silver from 5 orders, Gold from 15 orders. */
        public static Tier forOrders(int orders) {
            if (orders >= 15) return GOLD;
            if (orders >= 5) return SILVER;
            return BRONZE;
        }
    }

    @Id
    private String id;

    @Indexed(unique = true)
    private String phoneNumber;

    private int loyaltyPoints = 0;
    private Tier tierLevel = Tier.BRONZE;
    private int totalOrders = 0;
    private Instant createdAt = Instant.now();

    public Customer() {
    }

    public Customer(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public int getLoyaltyPoints() { return loyaltyPoints; }
    public void setLoyaltyPoints(int loyaltyPoints) { this.loyaltyPoints = loyaltyPoints; }

    public Tier getTierLevel() { return tierLevel; }
    public void setTierLevel(Tier tierLevel) { this.tierLevel = tierLevel; }

    public int getTotalOrders() { return totalOrders; }
    public void setTotalOrders(int totalOrders) { this.totalOrders = totalOrders; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}