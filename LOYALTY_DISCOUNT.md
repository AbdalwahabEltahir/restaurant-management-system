# Loyalty Points Redeem-for-Discount — Full Implementation Guide

This document covers the "redeem points for a discount" feature end to end:
what it is, which files changed, the exact data flow for both payment
methods, and the one known edge case to be aware of.

---

## 1. What problem this solves

- Before: `loyaltyPoints` could only ever go up (earned on paid orders via
  `recordPaidOrder`). There was an unused `redeemForDiscount(blocks)` method
  on `CustomerService` that spent points in blocks for a flat dollar amount
  off, but nothing in the app ever called it — points were purely cosmetic.
- Now: once a customer has **500 or more loyalty points**, they (or the
  staff member serving them) can tick a box at checkout to get **15% off**
  the bill. Choosing to redeem spends exactly **500 points**, regardless of
  the bill size.
- This is an **explicit, opt-in action at checkout**, not an automatic perk —
  the discount is only applied if the box is ticked, and only if the
  customer is identified by phone number with enough points.

---

## 2. Configuration

`application.properties`:
```
app.loyalty.discount-threshold-points=500
app.loyalty.discount-rate=0.15
```
Both are read via `@Value` in `CustomerService` with the same defaults
(`500` / `0.15`), so the properties file entries are optional but kept
explicit for visibility. Change these two numbers to retune the offer
without touching any Java logic.

This replaces the old, unused:
```
app.loyalty.points-per-discount=100
app.loyalty.discount-per-block=10.00
```

---

## 3. The pieces, and what each one is responsible for

- **`service/CustomerService.java`** — `redeemLoyaltyDiscount(customerId)`
  replaces the old, never-called `redeemForDiscount(customerId, blocks)`.
  It loads the customer, checks `loyaltyPoints >= discountThresholdPoints`
  (throws `400 Bad Request` "Not enough points for the loyalty discount" if
  not), deducts exactly `discountThresholdPoints` (500), saves, and returns
  the `discountRate` (0.15) for the caller to apply.
- **`model/Payment.java`** — new field `discountApplied` (boolean). Records,
  on the saved payment document, whether this particular bill redeemed the
  discount — shown back on the receipt and kept for audit history.
- **`service/OrderService.java`** — this is where the discount is actually
  applied to money:
  - The private `Bill` record gained a fourth field, `discountRate`. Its
    `total()` method now computes `subtotal × (1 − discountRate)`, rounded
    to 2 decimal places, instead of a plain sum.
  - `loadBill(qrCodeHash, phoneNumber, redeemDiscount)` — gained the
    `redeemDiscount` parameter. If true, it requires a resolved customer
    (throws `400` "Enter a phone number to redeem the loyalty discount" if
    none), calls `customerService.redeemLoyaltyDiscount(...)` (which does
    the points check/deduction), and carries the returned rate into the
    `Bill`. If the box isn't ticked, `discountRate` is `BigDecimal.ZERO` and
    `total()` behaves exactly as before.
  - `completePayment(qrCodeHash, phoneNumber, redeemDiscount)` and
    `beginOnlineCheckout(qrCodeHash, phoneNumber, redeemDiscount)` both take
    the new flag, pass it to `loadBill`, and set `payment.setDiscountApplied(redeemDiscount)`
    on the `Payment` they build — so the *already-discounted* `bill.total()`
    becomes `payment.getAmount()`.
  - `Receipt` gained a `discountApplied` field, filled from
    `payment.isDiscountApplied()` in `settle(...)`, so both the counter
    receipt and the online payment's eventual record reflect it.
  - Loyalty **points earned** on the paid order are still based on the
    order's own `subtotal()` (pre-discount) via `recordPaidOrder` — the
    discount changes what the customer *pays*, not what they *earn* on that
    same bill.
- **`controller/OrderController.java`** (`pay`, staff counter payment) and
  **`controller/PaymentController.java`** (`payOnline`, guest online
  checkout) — both now read an optional `"redeemDiscount": "true"` string
  from the request body (alongside the existing optional `"phoneNumber"`)
  and pass a boolean through to `OrderService`.
- **`service/StripeCheckoutService.java`** — `createCheckoutSession` takes
  the extra `redeemDiscount` boolean and forwards it to
  `OrderService.beginOnlineCheckout` *before* building the Stripe session —
  so the Stripe Checkout line item is created for the **discounted** amount,
  not the full bill.
- **`static/index.html`** (guest page) — the "Pay your bill" card gained a
  checkbox: *"Redeem 500 points for 15% off (enter your phone number in
  Loyalty points below first)."* Ticking it live-updates the displayed total
  to the 15%-off estimate (client-side preview only — the server always
  recomputes and enforces the real amount). `payOnline()` now sends
  `redeemDiscount: 'true'` in the request body when checked.
- **`static/staff.html`** (staff dashboard) — each "Ready to pay" card
  gained the same checkbox: *"Redeem 500 points for 15% off."* `pay()` now
  sends `redeemDiscount` accordingly, and the receipt view shows "15%
  loyalty discount applied" when `receipt.discountApplied` is true.
- **`style.css`** — added a `.checkbox-row` class (flex row, brand-colored
  accent, matching the existing input/label look) used by both checkboxes.

---

## 4. The two checkout entry points, in full

- **`POST /api/staff/orders/table/{qrCodeHash}/pay`** (staff, counter)
  - Body (optional): `{ "phoneNumber": "...", "redeemDiscount": "true" }`
  - `redeemDiscount: true` without a resolvable phone number → `400`.
  - `redeemDiscount: true` with fewer than 500 points → `400`.
  - On success: 500 points are spent immediately, the `Payment` is created
    `PAID` for the discounted amount, and the receipt comes back in the same
    response with `discountApplied: true`.
- **`POST /api/orders/table/{qrCodeHash}/pay/online`** (guest, Stripe)
  - Body (optional): `{ "phoneNumber": "...", "redeemDiscount": "true" }`
  - Same validation as above, but it happens *before* the Stripe Checkout
    Session is created — points are spent and the `Payment` (status
    `PENDING`, amount already discounted) is saved at this point, then the
    guest is redirected to Stripe to pay that discounted amount.

---

## 5. Step-by-step: a redeemed discount's journey (online path)

1. Guest has ≥500 points and enters their phone number in the Loyalty
   points card, then ticks "Redeem 500 points for 15% off" in the Pay your
   bill card. The displayed total updates to the 15%-off estimate.
2. Guest taps **Pay online**. The browser sends
   `{ phoneNumber, redeemDiscount: 'true' }` to
   `POST /api/orders/table/{qrCodeHash}/pay/online`.
3. `StripeCheckoutService.createCheckoutSession` calls
   `OrderService.beginOnlineCheckout(qrCodeHash, phoneNumber, true)`.
4. `loadBill` resolves the customer by phone, calls
   `CustomerService.redeemLoyaltyDiscount(customerId)`, which checks the
   500-point threshold, deducts 500 points, and returns `0.15`.
5. `Bill.total()` now returns `subtotal × 0.85`. A `Payment` (`PENDING`,
   `discountApplied=true`, `amount` = that discounted total) is saved.
6. The Stripe Checkout Session is created for that discounted amount — the
   guest only ever sees and pays the discounted price on Stripe's page.
7. Guest pays, is redirected back, `confirmSession` → `finalizeOnlinePayment`
   runs as before: orders marked `PAID`, table freed, loyalty points earned
   on the **original** order subtotal (not the discounted amount), and the
   receipt reports `discountApplied: true`.

The counter-payment path is identical except steps 2–6 happen synchronously
in one staff-triggered request, with no Stripe redirect.

---

## 6. Known edge case

Because points are spent as soon as `redeemDiscount: true` is sent — for the
online path, that's at **Checkout-Session-creation time**, not at
confirmed-payment time — a guest who ticks the box, starts Stripe Checkout,
and then abandons or cancels the payment on Stripe's page has **already lost
the 500 points**, with no refund of points on cancel. The counter-payment
path doesn't have this problem, since staff only call it once the customer
has actually paid in person.

If this turns out to matter in practice, the fix would be to move the
points deduction to `finalizeOnlinePayment` (i.e., only spend points once
Stripe confirms the money actually arrived) instead of at checkout-session
creation — not implemented here, since it wasn't asked for and the counter
path covers the common case safely.
