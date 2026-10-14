package com.example.demo.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.model.Customer;
import com.example.demo.model.MenuItem;
import com.example.demo.model.Order;
import com.example.demo.model.RestaurantTable;
import com.example.demo.repository.CustomerRepository;
import com.example.demo.repository.MenuItemRepository;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.RestaurantTableRepository;

@Service
public class OrderService {

    /** Orders that are still open: not paid and not cancelled. */
    private static final List<Order.Status> OPEN = List.of(
            Order.Status.PENDING, Order.Status.PREPARING,
            Order.Status.READY, Order.Status.SERVED);

    /** COUNTER: the guest pays staff at the counter. ONLINE_TEST: simulated online payment. */
    private static final Set<String> PAYMENT_METHODS = Set.of("COUNTER", "ONLINE_TEST");

    private final OrderRepository orders;
    private final RestaurantTableRepository tables;
    private final MenuItemRepository menuItems;
    private final CustomerRepository customers;
    private final CustomerService customerService;
    private final MongoTemplate mongo;

    public OrderService(OrderRepository orders, RestaurantTableRepository tables,
                        MenuItemRepository menuItems, CustomerRepository customers,
                        CustomerService customerService, MongoTemplate mongo) {
        this.orders = orders;
        this.tables = tables;
        this.menuItems = menuItems;
        this.customers = customers;
        this.customerService = customerService;
        this.mongo = mongo;
    }

    /** One line staff want to order: which dish, how many, and a note. */
    public static class Line {
        private String menuItemId;
        private int quantity;
        private String notes;

        public String getMenuItemId() { return menuItemId; }
        public void setMenuItemId(String menuItemId) { this.menuItemId = menuItemId; }

        public int getQuantity() { return quantity; }
        public void setQuantity(int quantity) { this.quantity = quantity; }

        public String getNotes() { return notes; }
        public void setNotes(String notes) { this.notes = notes; }
    }

    /** What the screen shows after a table is paid. */
    public static class Receipt {
        private final int tableNumber;
        private final String method;
        private final String providerRef;      // only for ONLINE_TEST, otherwise null
        private final Instant paidAt;
        private final List<Order.Item> items;
        private final BigDecimal total;
        private final List<String> orderIds;
        private final String customerPhone;    // null if no phone number is linked
        private final long pointsEarned;       // 0 if no phone number is linked
        private final long pointsBalance;
        private final String tier;

        public Receipt(int tableNumber, String method, String providerRef, Instant paidAt,
                       List<Order.Item> items, BigDecimal total, List<String> orderIds,
                       String customerPhone, long pointsEarned, long pointsBalance, String tier) {
            this.tableNumber = tableNumber;
            this.method = method;
            this.providerRef = providerRef;
            this.paidAt = paidAt;
            this.items = items;
            this.total = total;
            this.orderIds = orderIds;
            this.customerPhone = customerPhone;
            this.pointsEarned = pointsEarned;
            this.pointsBalance = pointsBalance;
            this.tier = tier;
        }

        public int getTableNumber() { return tableNumber; }
        public String getMethod() { return method; }
        public String getProviderRef() { return providerRef; }
        public Instant getPaidAt() { return paidAt; }
        public List<Order.Item> getItems() { return items; }
        public BigDecimal getTotal() { return total; }
        public List<String> getOrderIds() { return orderIds; }
        public String getCustomerPhone() { return customerPhone; }
        public long getPointsEarned() { return pointsEarned; }
        public long getPointsBalance() { return pointsBalance; }
        public String getTier() { return tier; }
    }

    /** Staff place an order for a table (FR-1.2). The table code says which table. */
    public Order placeOrder(String qrCodeHash, List<Line> lines) {
        RestaurantTable table = findTable(qrCodeHash);
        if (lines == null || lines.isEmpty()) {
            throw bad("The order is empty");
        }

        List<Order.Item> items = new ArrayList<>();
        for (Line line : lines) {
            if (line.getMenuItemId() == null || line.getQuantity() < 1) {
                throw bad("Each item needs a dish and a quantity of at least 1");
            }
            // Disabled dishes cannot be ordered.
            MenuItem dish = menuItems.findById(line.getMenuItemId())
                    .filter(MenuItem::isAvailable)
                    .orElseThrow(() -> bad("A dish in the order is not available"));
            // Name and price come from the menu, never from the request.
            items.add(new Order.Item(dish.getId(), dish.getName(), dish.getPrice(),
                    line.getQuantity(), line.getNotes()));
        }

        // If a phone number is already linked to this table, the new order shares it.
        List<Order> open = orders.findByTableIdAndStatusIn(table.getId(), OPEN);
        Order order = new Order(table.getId(), items);
        order.setCustomerId(linkedCustomerId(open));
        order = orders.save(order);

        if (table.getStatus() == RestaurantTable.Status.AVAILABLE) {
            table.setStatus(RestaurantTable.Status.OCCUPIED);
            tables.save(table);
        }
        return order;
    }

    /** Open orders of one table. The guest and the chatbot only see their own table (FR-2.2). */
    public List<Order> openOrdersForTable(String qrCodeHash) {
        RestaurantTable table = findTable(qrCodeHash);
        return orders.findByTableIdAndStatusIn(table.getId(), OPEN);
    }

    /** Kitchen screen for staff: all open orders, oldest first (FR-1.3). */
    public List<Order> openOrders() {
        return orders.findByStatusInOrderByCreatedAtAsc(OPEN);
    }

    /**
     * Staff move an order one step (FR-1.3), for example Pending to Preparing.
     * Cancelling is only allowed while Pending. Paying goes through completePayment.
     */
    public Order updateStatus(String orderId, Order.Status next) {
        Order order = getById(orderId);
        if (next == Order.Status.PAID) {
            throw bad("Use payment to mark an order as paid");
        }
        if (!order.getStatus().canMoveTo(next)) {
            throw bad("An order cannot go from " + order.getStatus() + " to " + next);
        }
        order.setStatus(next);
        return orders.save(order);
    }

    /**
     * A guest links a phone number to their table (FR-4.1). The number is attached to the
     * table's open orders, and later orders at the table share it. The loyalty points are
     * added when staff confirm payment. A new number gets a loyalty profile automatically.
     */
    public Customer linkPhone(String qrCodeHash, String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw bad("Enter a phone number");
        }
        RestaurantTable table = findTable(qrCodeHash);
        List<Order> open = orders.findByTableIdAndStatusIn(table.getId(), OPEN);
        if (open.isEmpty()) {
            throw bad("There are no open orders at this table yet");
        }

        Customer customer = customerService.findOrCreateByPhone(phoneNumber);
        for (Order order : open) {
            order.setCustomerId(customer.getId());
            orders.save(order);
        }
        return customer;
    }

    /**
     * Final step of checkout (FR-3.1, FR-3.2). Staff confirm payment for the whole table:
     * method COUNTER (default) or ONLINE_TEST (simulated). Every open order at the table
     * becomes Paid and the table is freed. The loyalty points go to the phone number given
     * here, or else to the number the guest linked to the table. A record is saved in the
     * "payments" collection.
     * Returns the receipt.
     */
    @Transactional
    public Receipt completePayment(String qrCodeHash, String phoneNumber, String method) {
        String paymentMethod = (method == null || method.isBlank())
                ? "COUNTER" : method.trim().toUpperCase();
        if (!PAYMENT_METHODS.contains(paymentMethod)) {
            throw bad("Payment method must be COUNTER or ONLINE_TEST");
        }

        RestaurantTable table = findTable(qrCodeHash);
        List<Order> open = orders.findByTableIdAndStatusIn(table.getId(), OPEN);
        if (open.isEmpty()) {
            throw bad("There is nothing to pay at this table");
        }
        boolean allServed = open.stream().allMatch(o -> o.getStatus() == Order.Status.SERVED);
        if (!allServed) {
            throw bad("Some orders are not served yet");
        }

        // Who gets the points: a number typed now wins, otherwise the number linked to the table.
        Customer customer = null;
        if (phoneNumber != null && !phoneNumber.isBlank()) {
            customer = customerService.findOrCreateByPhone(phoneNumber);
        } else {
            String linkedId = linkedCustomerId(open);
            if (linkedId != null) {
                customer = customers.findById(linkedId).orElse(null);
            }
        }
        long pointsBefore = (customer == null) ? 0 : customer.getLoyaltyPoints();

        BigDecimal total = BigDecimal.ZERO;
        List<Order.Item> items = new ArrayList<>();
        List<String> orderIds = new ArrayList<>();

        for (Order order : open) {
            total = total.add(order.subtotal());
            items.addAll(order.getItems());
            orderIds.add(order.getId());

            order.setStatus(Order.Status.PAID);
            if (customer != null) {
                order.setCustomerId(customer.getId());
                long before = pointsOf(customer.getId());
                customerService.recordPaidOrder(customer.getId(), order.subtotal());
                long earned = pointsOf(customer.getId()) - before;

                // One document in "loyalty_transactions" for each paid order that earned points.
                if (earned > 0) {
                    mongo.insert(new Document()
                            .append("customerId", customer.getId())
                            .append("orderId", order.getId())
                            .append("type", "EARN")
                            .append("points", earned)
                            .append("createdAt", new Date()), "loyalty_transactions");
                }
            }
            orders.save(order);
        }

        table.setStatus(RestaurantTable.Status.AVAILABLE);
        tables.save(table);

        // Save the payment in the "payments" collection.
        String providerRef = "ONLINE_TEST".equals(paymentMethod)
                ? "TEST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase()
                : null;
        Document payment = new Document()
                .append("orderIds", orderIds)
                .append("method", paymentMethod)
                .append("amount", new Decimal128(total))
                .append("providerRef", providerRef)
                .append("status", "PAID")
                .append("createdAt", new Date());
        mongo.insert(payment, "payments");

        // Read the customer again to report the points that were just added.
        long pointsEarned = 0;
        long pointsBalance = 0;
        String tier = null;
        if (customer != null) {
            Customer updated = customers.findById(customer.getId()).orElse(customer);
            pointsBalance = updated.getLoyaltyPoints();
            pointsEarned = pointsBalance - pointsBefore;
            tier = String.valueOf(updated.getTierLevel());
        }

        return new Receipt(table.getTableNumber(), paymentMethod, providerRef, Instant.now(),
                items, total, orderIds, customer == null ? null : customer.getPhoneNumber(),
                pointsEarned, pointsBalance, tier);
    }

    /** The loyalty points a customer has right now. */
    private long pointsOf(String customerId) {
        Customer c = customers.findById(customerId).orElse(null);
        return (c == null) ? 0 : c.getLoyaltyPoints();
    }

    /** The customer linked to a table's open orders, or null if no number is linked yet. */
    private static String linkedCustomerId(List<Order> open) {
        for (Order order : open) {
            if (order.getCustomerId() != null) {
                return order.getCustomerId();
            }
        }
        return null;
    }

    private Order getById(String id) {
        return orders.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Order not found"));
    }

    private RestaurantTable findTable(String qrCodeHash) {
        return tables.findByQrCodeHash(qrCodeHash)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Unknown table code"));
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}