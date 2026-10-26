package com.example.demo.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.model.Order;
import com.example.demo.service.OrderService;
import com.example.demo.service.UserService;

@RestController
@RequestMapping("/api")
public class OrderController {

    private final OrderService orderService;
    private final UserService userService;

    public OrderController(OrderService orderService, UserService userService) {
        this.orderService = orderService;
        this.userService = userService;
    }

    // ---------- Guest (no login; the table code from the QR link identifies the table) ----------

    /** Open orders of this table. An unknown table code gives 404, so it also checks the QR code. */
    @GetMapping("/orders/table/{qrCodeHash}")
    public List<Order> forTable(@PathVariable String qrCodeHash) {
        return orderService.openOrdersForTable(qrCodeHash);
    }

    /**
     * A guest places their own order from the table's QR-code page. Same validation and
     * business rules as the staff endpoint below (same service method) — only who is
     * allowed to call it differs. Order status can still only be changed by staff, via
     * the PATCH endpoint further down, which stays behind userService.authorize.
     */
    @PostMapping("/orders/table/{qrCodeHash}")
    @ResponseStatus(HttpStatus.CREATED)
    public Order placeAsGuest(@PathVariable String qrCodeHash, @RequestBody List<OrderService.Line> lines) {
        return orderService.placeOrder(qrCodeHash, lines);
    }

    // Paying online is handled by PaymentController, which talks to Stripe Checkout.

    // ---------- Staff (login required, section "orders") ----------

    /** Staff order food for a table. Body: [{"menuItemId": "...", "quantity": 2, "notes": "no onion"}] */
    @PostMapping("/staff/orders/table/{qrCodeHash}")
    @ResponseStatus(HttpStatus.CREATED)
    public Order place(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String qrCodeHash,
            @RequestBody List<OrderService.Line> lines) {
        userService.authorize(auth, "orders");
        return orderService.placeOrder(qrCodeHash, lines);
    }

    /** Kitchen screen: all open orders, oldest first. */
    @GetMapping("/staff/orders")
    public List<Order> queue(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        userService.authorize(auth, "orders");
        return orderService.openOrders();
    }

    /** Dashboard count: orders placed today, paid included, cancelled left out. */
    @GetMapping("/staff/orders/today")
    public Map<String, Long> today(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        userService.authorize(auth, "orders");
        return Map.of("totalOrders", orderService.ordersPlacedToday());
    }

    /** Move an order one step. Body: {"status": "PREPARING"} */
    @PatchMapping("/staff/orders/{id}/status")
    public Order updateStatus(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        userService.authorize(auth, "orders");
        Order.Status next;
        try {
            next = Order.Status.valueOf(String.valueOf(body.get("status")).toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Status must be PENDING, PREPARING, READY, SERVED or CANCELLED");
        }
        return orderService.updateStatus(id, next);
    }

    /**
     * Staff confirm that the table paid at the counter and get the receipt.
     * Body is optional: {"phoneNumber": "5555 1234", "discountTier": "2000"}. The phone number
     * adds loyalty points for that customer; discountTier is the points threshold of the tier
     * the customer picked (500, 1000 or 2000 by default) and spends exactly that many points
     * (requires a phone number with enough points for that tier).
     */
    @PostMapping("/staff/orders/table/{qrCodeHash}/pay")
    public OrderService.Receipt pay(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String qrCodeHash,
            @RequestBody(required = false) Map<String, String> body) {
        userService.authorize(auth, "orders");
        String phone = (body == null) ? null : body.get("phoneNumber");
        Integer discountTier = parseDiscountTier(body);
        return orderService.completePayment(qrCodeHash, phone, discountTier);
    }

    private static Integer parseDiscountTier(Map<String, String> body) {
        String raw = (body == null) ? null : body.get("discountTier");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "discountTier must be a number of points");
        }
    }
}