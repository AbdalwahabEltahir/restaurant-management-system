package com.example.demo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.model.Customer;
import com.example.demo.repository.CustomerRepository;

@Service
public class CustomerService {

    private static final String DEFAULT_COUNTRY_CODE = "+974"; // Qatar

    private final CustomerRepository customers;
    private final int pointsPerDiscount;
    private final BigDecimal discountPerBlock;

    public CustomerService(CustomerRepository customers,
            @Value("${app.loyalty.points-per-discount:100}") int pointsPerDiscount,
            @Value("${app.loyalty.discount-per-block:10.00}") BigDecimal discountPerBlock) {
        this.customers = customers;
        this.pointsPerDiscount = pointsPerDiscount;
        this.discountPerBlock = discountPerBlock;
    }

    /** Finds the customer with this phone number, or creates a new profile (FR-4.1). */
    public Customer findOrCreateByPhone(String rawPhone) {
        String phone = normalizePhone(rawPhone);
        return customers.findByPhoneNumber(phone)
                .orElseGet(() -> customers.save(new Customer(phone)));
    }

    public List<Customer> listAll() {
        return customers.findAll();
    }

    /**
     * Adds points for one paid order (FR-4.2): subtotal x tier rate, rounded down.
     * Also counts the order and moves the customer to the right tier (FR-4.3).
     */
    public Customer recordPaidOrder(String customerId, BigDecimal subtotal) {
        Customer c = getById(customerId);
        int earned = subtotal.multiply(c.getTierLevel().getRate())
                .setScale(0, RoundingMode.DOWN)
                .intValue();
        c.setLoyaltyPoints(c.getLoyaltyPoints() + earned);
        c.setTotalOrders(c.getTotalOrders() + 1);
        c.setTierLevel(Customer.Tier.forOrders(c.getTotalOrders()));
        return customers.save(c);
    }

    /**
     * Spends points in blocks (FR-4.4). By default 1 block costs 100 points.
     * Returns the discount those points are worth. The balance can never go below zero.
     */
    public BigDecimal redeemForDiscount(String customerId, int blocks) {
        if (blocks <= 0) {
            throw bad("Choose at least one block of points");
        }
        Customer c = getById(customerId);
        int cost = blocks * pointsPerDiscount;
        if (cost > c.getLoyaltyPoints()) {
            throw bad("Not enough points");
        }
        c.setLoyaltyPoints(c.getLoyaltyPoints() - cost);
        customers.save(c);
        return discountPerBlock.multiply(BigDecimal.valueOf(blocks));
    }

    private Customer getById(String id) {
        return customers.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Customer not found"));
    }

    /** "5555 1234" and "+974 5555-1234" both become "+97455551234". */
    private static String normalizePhone(String raw) {
        if (raw == null) {
            throw bad("Phone number is required");
        }
        String phone = raw.replaceAll("[\\s\\-()]", "");
        if (phone.startsWith("00")) {
            phone = "+" + phone.substring(2);
        }
        if (phone.matches("\\d{8}")) {
            phone = DEFAULT_COUNTRY_CODE + phone;
        }
        if (!phone.matches("\\+\\d{8,15}")) {
            throw bad("Invalid phone number");
        }
        return phone;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}