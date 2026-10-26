Loyalty Discount: Redeem Points at Checkout
How it works
A customer picks one of three checkboxes at checkout to redeem points for a discount: 500 pts →
15% off, 1000 pts → 25% off, 2000 pts → 40% off (the default ladder). Each checkbox is only
selectable once the customer's known points reach its threshold, and checking one unchecks the
others — tiers are never combined or applied twice in the same checkout. Only that tier's points
are spent, not the whole balance.
It is opt-in and needs the customer's phone number.
Points are still earned on the original (pre-discount) subtotal.
Settings
app.loyalty.discount-tiers-points=500,1000,2000
app.loyalty.discount-tiers-rates=0.15,0.25,0.40
(Both lists must be the same length; tier i's points threshold maps to tier i's rate.)
Backend changes
CustomerService: redeemLoyaltyDiscount(customerId, tierPoints) looks up the tier matching exactly
that points threshold (400 if unknown), checks the customer has enough points for it (else 400),
deducts that tier's points, and returns that tier's rate.
OrderService: when discountTierPoints is given, the bill total becomes subtotal × (1 - rate). No
phone number returns 400.
Payment / Receipt: new discountApplied field.
Controllers and StripeCheckoutService: pass the optional "discountTier" points value from the
request body through, so Stripe charges the discounted amount.
Frontend changes
Guest page and staff dashboard: three mutually-exclusive "Redeem N points for X% off" checkboxes
(app.js's buildLoyaltyTierCheckboxes), each enabled only once the customer's known points reach
that tier, with the discounted total previewed for whichever tier is picked.
The server always recalculates the real amount.
Flow
The user picks a tier checkbox and pays, sending { phoneNumber, discountTier: "2000" }.
The server checks the customer has enough points for that exact tier, deducts that tier's points,
and applies that tier's discount.
The payment is saved for the discounted amount (PAID at the counter, PENDING online until Stripe confirms).
Points are earned on the original subtotal, and the receipt shows that a discount was applied.

The same endpoint accepts this body for both counter and online payments.

Known issue

Online, the points are deducted when the Stripe session is created. If the guest cancels, the points for the picked tier are lost.

Fix: deduct the points in finalizeOnlinePayment, after Stripe confirms payment.
