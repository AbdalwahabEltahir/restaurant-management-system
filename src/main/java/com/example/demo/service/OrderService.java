package com.example.demo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.model.Customer;
import com.example.demo.model.MenuItem;
import com.example.demo.model.Order;
import com.example.demo.model.Payment;
import com.example.demo.model.RestaurantTable;
import com.example.demo.repository.CustomerRepository;
import com.example.demo.repository.MenuItemRepository;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.PaymentRepository;
import com.example.demo.repository.RestaurantTableRepository;

@Service
public class OrderService {

    /** Orders that are still open: not paid and not cancelled. */
    private static final List<Order.Status> OPEN = List.of(
            Order.Status.PENDING, Order.Status.PREPARING,
            Order.Status.READY, Order.Status.SERVED);

    private final OrderRepository orders;
    private final RestaurantTableRepository tables;
    private final MenuItemRepository menuItems;
    private final CustomerRepository customers;
    private final CustomerService customerService;
    private final PaymentRepository payments;
    private final MongoTemplate mongo;

    public OrderService(OrderRepository orders, RestaurantTableRepository tables,
                        MenuItemRepository menuItems, CustomerRepository customers,
                        CustomerService customerService, PaymentRepository payments,
                        MongoTemplate mongo) {
        this.orders = orders;
        this.tables = tables;
        this.menuItems = menuItems;
        this.customers = customers;
        this.customerService = customerService;
        this.payments = payments;
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
        private final String providerRef;      // Stripe's payment reference; null for a counter payment
        private final Instant paidAt;
        private final List<Order.Item> items;
        private final BigDecimal total;
        private final List<String> orderIds;
        private final String customerPhone;    // null if no phone number is linked
        private final long pointsEarned;       // 0 if no phone number is linked
        private final long pointsBalance;
        private final String tier;
        private final boolean discountApplied;

        public Receipt(int tableNumber, String method, String providerRef, Instant paidAt,
                       List<Order.Item> items, BigDecimal total, List<String> orderIds,
                       String customerPhone, long pointsEarned, long pointsBalance, String tier,
                       boolean discountApplied) {
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
            this.discountApplied = discountApplied;
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
        public boolean isDiscountApplied() { return discountApplied; }
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

    /** A table's unpaid bill: the table itself, its served orders, who to credit points to, and
     *  the loyalty discount rate redeemed for it (zero if none was redeemed). */
    private record Bill(RestaurantTable table, List<Order> openOrders, Customer customer, BigDecimal discountRate) {
        BigDecimal total() {
            BigDecimal subtotal = openOrders.stream().map(Order::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
            return subtotal.multiply(BigDecimal.ONE.subtract(discountRate)).setScale(2, RoundingMode.HALF_UP);
        }
    }

    /**
     * Loads and validates a table's bill: the table must exist, have open orders, and every
     * open order must be Served. The phone number given wins for loyalty points; otherwise the
     * number already linked to the table's orders is used, if any. If redeemDiscount is true,
     * the customer must be known and have enough points (FR-4.4); their points are spent now.
     */
    private Bill loadBill(String qrCodeHash, String phoneNumber, boolean redeemDiscount) {
        RestaurantTable table = findTable(qrCodeHash);
        List<Order> open = orders.findByTableIdAndStatusIn(table.getId(), OPEN);
        if (open.isEmpty()) {
            throw bad("There is nothing to pay at this table");
        }
        boolean allServed = open.stream().allMatch(o -> o.getStatus() == Order.Status.SERVED);
        if (!allServed) {
            throw bad("Some orders are not served yet");
        }

        Customer customer = null;
        if (phoneNumber != null && !phoneNumber.isBlank()) {
            customer = customerService.findOrCreateByPhone(phoneNumber);
        } else {
            String linkedId = linkedCustomerId(open);
            if (linkedId != null) {
                customer = customers.findById(linkedId).orElse(null);
            }
        }

        BigDecimal discountRate = BigDecimal.ZERO;
        if (redeemDiscount) {
            if (customer == null) {
                throw bad("Enter a phone number to redeem the loyalty discount");
            }
            discountRate = customerService.redeemLoyaltyDiscount(customer.getId());
        }
        return new Bill(table, open, customer, discountRate);
    }

    /**
     * Staff confirm a table paid at the counter (FR-3.1, FR-3.2). Every open order at the
     * table becomes Paid and the table is freed. Online payment is done by the guest, through
     * beginOnlineCheckout and finalizeOnlinePayment instead. Returns the receipt.
     */
    @Transactional
    public Receipt completePayment(String qrCodeHash, String phoneNumber, boolean redeemDiscount) {
        Bill bill = loadBill(qrCodeHash, phoneNumber, redeemDiscount);
        Payment payment = new Payment(bill.table().getId(), idOf(bill.customer()), orderIdsOf(bill.openOrders()),
                Payment.Method.COUNTER, bill.total(), Payment.Status.PAID);
        payment.setDiscountApplied(redeemDiscount);
        return settle(bill, payment);
    }

    /** What StripeCheckoutService needs to build the Checkout Session for a new online payment. */
    public record CheckoutInit(Payment payment, int tableNumber) {
    }

    /**
     * Guest starts paying online (FR-3.1). Validates the bill and records a Payment that is
     * PENDING until Stripe confirms it.
     */
    @Transactional
    public CheckoutInit beginOnlineCheckout(String qrCodeHash, String phoneNumber, boolean redeemDiscount) {
        Bill bill = loadBill(qrCodeHash, phoneNumber, redeemDiscount);
        Payment payment = new Payment(bill.table().getId(), idOf(bill.customer()), orderIdsOf(bill.openOrders()),
                Payment.Method.STRIPE, bill.total(), Payment.Status.PENDING);
        payment.setDiscountApplied(redeemDiscount);
        return new CheckoutInit(payments.save(payment), bill.table().getTableNumber());
    }

    /** Called once the Checkout Session for a pending payment is created, to keep its id. */
    public void attachStripeSession(String paymentId, String stripeSessionId) {
        Payment payment = payments.findById(paymentId)
                .orElseThrow(() -> bad("Unknown payment"));
        payment.setStripeSessionId(stripeSessionId);
        payments.save(payment);
    }

    /**
     * Stripe confirmed the payment (FR-3.2), through the checkout.session.completed webhook.
     * Marks the bill's orders Paid, frees the table, and adds loyalty points, the same as a
     * counter payment. Returns the receipt. Does nothing if the payment was already finalized,
     * since Stripe may send the same webhook more than once.
     */
    @Transactional
    public Receipt finalizeOnlinePayment(String paymentId, String providerRef) {
        Payment payment = payments.findById(paymentId)
                .orElseThrow(() -> bad("Unknown payment"));
        if (payment.getStatus() == Payment.Status.PAID) {
            return null;
        }
        RestaurantTable table = tables.findById(payment.getTableId())
                .orElseThrow(() -> bad("Unknown table"));
        List<Order> open = orders.findAllById(payment.getOrderIds());
        Customer customer = (payment.getCustomerId() == null)
                ? null : customers.findById(payment.getCustomerId()).orElse(null);

        payment.setStatus(Payment.Status.PAID);
        payment.setProviderRef(providerRef);
        return settle(new Bill(table, open, customer, BigDecimal.ZERO), payment);
    }

    /** Marks a bill's orders Paid, frees the table, adds loyalty points, and saves the payment. */
    private Receipt settle(Bill bill, Payment payment) {
        RestaurantTable table = bill.table();
        Customer customer = bill.customer();
        long pointsBefore = (customer == null) ? 0 : customer.getLoyaltyPoints();

        List<Order.Item> items = new ArrayList<>();
        for (Order order : bill.openOrders()) {
            items.addAll(order.getItems());
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
        payments.save(payment);

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

        return new Receipt(table.getTableNumber(), payment.getMethod().name(), payment.getProviderRef(),
                Instant.now(), items, payment.getAmount(), payment.getOrderIds(),
                customer == null ? null : customer.getPhoneNumber(), pointsEarned, pointsBalance, tier,
                payment.isDiscountApplied());
    }

    /** The loyalty points a customer has right now. */
    private long pointsOf(String customerId) {
        Customer c = customers.findById(customerId).orElse(null);
        return (c == null) ? 0 : c.getLoyaltyPoints();
    }

    private static String idOf(Customer customer) {
        return (customer == null) ? null : customer.getId();
    }

    private static List<String> orderIdsOf(List<Order> open) {
        return open.stream().map(Order::getId).toList();
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