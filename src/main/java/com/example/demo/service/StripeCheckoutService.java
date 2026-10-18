package com.example.demo.service;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;

/**
 * Talks to Stripe Checkout (test mode) so a guest can pay their table's bill online.
 * The order/loyalty logic itself stays in OrderService; this class only creates the
 * Checkout Session and checks its status once the guest comes back from paying.
 */
@Service
public class StripeCheckoutService {

    private final OrderService orderService;
    private final String baseUrl;

    public StripeCheckoutService(OrderService orderService,
                                 @Value("${stripe.secret-key}") String secretKey,
                                 @Value("${app.base-url}") String baseUrl) {
        this.orderService = orderService;
        this.baseUrl = baseUrl;
        Stripe.apiKey = secretKey;
    }

    /**
     * Starts an online payment for a table's bill. Returns the URL of Stripe's own hosted
     * payment page, where the guest enters their card; we never see or store the card itself.
     */
    public String createCheckoutSession(String qrCodeHash, String phoneNumber, boolean redeemDiscount) {
        OrderService.CheckoutInit init = orderService.beginOnlineCheckout(qrCodeHash, phoneNumber, redeemDiscount);
        String tableUrl = baseUrl + "/?t=" + qrCodeHash;

        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(tableUrl + "&session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(tableUrl)
                .putMetadata("paymentId", init.payment().getId())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency("usd")
                                .setUnitAmount(toCents(init.payment().getAmount()))
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName("Table " + init.tableNumber() + " bill")
                                        .build())
                                .build())
                        .build())
                .build();

        Session session = create(params);
        orderService.attachStripeSession(init.payment().getId(), session.getId());
        return session.getUrl();
    }

    /**
     * Called by the guest's browser once Stripe redirects it back after paying. Asks Stripe
     * directly whether that session actually paid - never trusts the browser's say-so - and
     * if so, finalizes the matching payment. Safe to call more than once (finalizing an
     * already-paid payment is a no-op).
     */
    public void confirmSession(String sessionId) {
        Session session = retrieve(sessionId);
        if (!"paid".equals(session.getPaymentStatus())) {
            return;
        }
        Map<String, String> metadata = session.getMetadata();
        String paymentId = (metadata == null) ? null : metadata.get("paymentId");
        if (paymentId == null) {
            return;
        }
        orderService.finalizeOnlinePayment(paymentId, session.getPaymentIntent());
    }

    private static long toCents(BigDecimal amount) {
        return amount.movePointRight(2).longValueExact();
    }

    private static Session create(SessionCreateParams params) {
        try {
            return Session.create(params);
        } catch (StripeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not start the payment: " + e.getMessage());
        }
    }

    private static Session retrieve(String sessionId) {
        try {
            return Session.retrieve(sessionId);
        } catch (StripeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not check the payment: " + e.getMessage());
        }
    }
}
