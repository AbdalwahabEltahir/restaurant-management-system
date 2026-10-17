package com.example.demo.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.service.StripeCheckoutService;

@RestController
@RequestMapping("/api")
public class PaymentController {

    private final StripeCheckoutService stripeCheckoutService;

    public PaymentController(StripeCheckoutService stripeCheckoutService) {
        this.stripeCheckoutService = stripeCheckoutService;
    }

    /**
     * Guest starts paying their table's bill online. Body is optional:
     * {"phoneNumber": "5555 1234"} to earn loyalty points on the payment.
     * Returns the URL of Stripe's hosted payment page to redirect the guest to.
     */
    @PostMapping("/orders/table/{qrCodeHash}/pay/online")
    public Map<String, String> payOnline(
            @PathVariable String qrCodeHash,
            @RequestBody(required = false) Map<String, String> body) {
        String phone = (body == null) ? null : body.get("phoneNumber");
        String checkoutUrl = stripeCheckoutService.createCheckoutSession(qrCodeHash, phone);
        return Map.of("checkoutUrl", checkoutUrl);
    }

    /**
     * The guest's browser calls this once Stripe sends it back after paying. Body:
     * {"sessionId": "cs_test_..."}. The server asks Stripe directly whether that session
     * really paid before marking anything as paid - the browser's say-so alone isn't trusted.
     */
    @PostMapping("/payments/confirm")
    public void confirm(@RequestBody Map<String, String> body) {
        stripeCheckoutService.confirmSession(body.get("sessionId"));
    }
}
