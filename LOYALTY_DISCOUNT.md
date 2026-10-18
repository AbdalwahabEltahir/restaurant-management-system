Loyalty Discount: Redeem Points at Checkout
How it works
A customer can tick a checkbox at checkout to redeem points for a discount. The discount is tiered,
not stackable: redeeming once applies the best tier the customer's points reach, spending only that
tier's points (not the whole balance). Default ladder: 500 pts → 15% off, 1000 pts → 25% off,
2000 pts → 40% off (the cap). A customer with 2500 points still only gets 40% off and spends 2000
points in one redemption — tiers are never combined or applied twice in the same checkout.
It is opt-in and needs the customer's phone number.
Points are still earned on the original (pre-discount) subtotal.
Settings
app.loyalty.discount-tiers-points=500,1000,2000
app.loyalty.discount-tiers-rates=0.15,0.25,0.40
(Both lists must be the same length; tier i's points threshold maps to tier i's rate.)
Backend changes
CustomerService: redeemLoyaltyDiscount() finds the highest tier the customer's points reach (else 400),
deducts that tier's points, and returns that tier's rate.
OrderService: when the redeemDiscount flag is true, the bill total becomes subtotal × (1 - rate). No phone number returns 400.
Payment / Receipt: new discountApplied field.
Controllers and StripeCheckoutService: pass the optional "redeemDiscount": "true" from the request body through, so Stripe charges the discounted amount.
Frontend changes
Guest page and staff dashboard: a "Redeem loyalty points for a discount" checkbox, with the discounted total previewed once the guest's points are known.
The server always recalculates the real amount.
Flow
The user ticks the box and pays, sending { phoneNumber, redeemDiscount: "true" }.
The server finds the best tier the customer's points reach, deducts that tier's points, and applies that tier's discount.
The payment is saved for the discounted amount (PAID at the counter, PENDING online until Stripe confirms).
Points are earned on the original subtotal, and the receipt shows the discount.

The same endpoint accepts this body for both counter and online payments.

Known issue

Online, the points are deducted when the Stripe session is created. If the guest cancels, the 500 points are lost.

Fix: deduct the points in finalizeOnlinePayment, after Stripe confirms payment.