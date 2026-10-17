package com.example.demo.service;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;

/**
 * Talks to Stripe Checkout (test mode) so a guest can pay their table's bill online.
 * The order/loyalty logic itself stays in OrderService; this class only creates the
 * Checkout Session and reacts to Stripe's webhook once the guest has paid.
 */
@Service
public class StripeCheckoutService {

    private final OrderService orderService;
    private final String webhookSecret;
    private final String baseUrl;

    public StripeCheckoutService(OrderService orderService,
                                 @Value("${stripe.secret-key}") String secretKey,
                                 @Value("${stripe.webhook-secret}") String webhookSecret,
                                 @Value("${app.base-url}") String baseUrl) {
        this.orderService = orderService;
        this.webhookSecret = webhookSecret;
        this.baseUrl = baseUrl;
        Stripe.apiKey = secretKey;
    }

    /**
     * Starts an online payment for a table's bill. Returns the URL of Stripe's own hosted
     * payment page, where the guest enters their card; we never see or store the card itself.
     */
    public String createCheckoutSession(String qrCodeHash, String phoneNumber) {
        OrderService.CheckoutInit init = orderService.beginOnlineCheckout(qrCodeHash, phoneNumber);
        String tableUrl = baseUrl + "/?t=" + qrCodeHash;

        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(tableUrl + "&paid=1")
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
     * Handles Stripe's webhook call. Verifies the payload really came from Stripe using the
     * signing secret, then, once a checkout session has completed, marks that payment's bill
     * as paid. Other event types are acknowledged and ignored.
     */
    public void handleWebhook(String payload, String signatureHeader) {
        Event event;
        try {
            event = Webhook.constructEvent(payload, signatureHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Stripe signature");
        }

        if (!"checkout.session.completed".equals(event.getType())) {
            return;
        }
        Session session = (Session) event.getDataObjectDeserializer().getObject().orElse(null);
        if (session == null) {
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
        } catch (com.stripe.exception.StripeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not start the payment: " + e.getMessage());
        }
    }
}
