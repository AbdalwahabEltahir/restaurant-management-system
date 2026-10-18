package com.example.demo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
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

    /** One rung of the loyalty discount ladder: spend this many points for this much off. */
    private record DiscountTier(int points, BigDecimal rate) {
    }

    private final CustomerRepository customers;
    private final List<DiscountTier> discountTiers; // sorted highest points first

    public CustomerService(CustomerRepository customers,
            @Value("${app.loyalty.discount-tiers-points:500,1000,2000}") int[] discountTiersPoints,
            @Value("${app.loyalty.discount-tiers-rates:0.15,0.25,0.40}") BigDecimal[] discountTiersRates) {
        this.customers = customers;
        if (discountTiersPoints.length != discountTiersRates.length) {
            throw new IllegalStateException(
                    "app.loyalty.discount-tiers-points and app.loyalty.discount-tiers-rates must list the same number of values");
        }
        List<DiscountTier> tiers = new ArrayList<>();
        for (int i = 0; i < discountTiersPoints.length; i++) {
            tiers.add(new DiscountTier(discountTiersPoints[i], discountTiersRates[i]));
        }
        tiers.sort(Comparator.comparingInt(DiscountTier::points).reversed());
        this.discountTiers = List.copyOf(tiers);
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
     * Redeems the loyalty discount (FR-4.4): a customer gets the best percentage off their
     * bill that their points reach on the ladder (500 pts → 15%, 1000 → 25%, 2000 → 40% by
     * default), and that tier's points are spent — not stacked with other tiers. Returns the
     * discount rate (e.g. 0.25 for 25%) to apply to the bill.
     */
    public BigDecimal redeemLoyaltyDiscount(String customerId) {
        Customer c = getById(customerId);
        DiscountTier tier = discountTiers.stream()
                .filter(t -> c.getLoyaltyPoints() >= t.points())
                .findFirst()
                .orElseThrow(() -> bad("Not enough points for the loyalty discount"));
        c.setLoyaltyPoints(c.getLoyaltyPoints() - tier.points());
        customers.save(c);
        return tier.rate();
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