# Online Payment Gateway (Stripe, test mode) — Full Implementation Guide

This document walks through the whole online-payment feature end to end:
what it is, how it was built, which files do what, and exactly how a payment
travels through the system from the guest's tap to the database update.

No real card network is ever contacted and no real money moves — but the
integration pattern (hosted card form, server-side confirmation with Stripe)
is the same one a production system would use.

---

## 1. What problem this solves

- Before: the only way to pay was for the guest to flag down staff, who
  confirmed payment at the counter (`COUNTER` method).
- Now: a guest can also pay their own bill from their phone, at the table,
  without staff involved — a real online checkout.
- Staff's counter flow is untouched in behavior; it's just the only payment
  method staff handle now.

---

## 2. One-time setup (do this before testing)

- **Create a Stripe account** — free, at https://dashboard.stripe.com/register.
- **Switch to Test mode** — toggle in the top-right of the Stripe dashboard.
  Test mode is a fully separate sandbox: nothing done here ever touches real
  money or real cards.
- **Get the secret key** — Dashboard → Developers → API keys → copy the key
  starting `sk_test_...`.
- **Put the key in `secrets.properties`**, a file at the project root (next
  to `pom.xml`) that is **git-ignored** — it's never committed, so it's safe
  to paste the real key directly into it:
  ```
  stripe.secret-key=sk_test_...
  ```
  `application.properties` only has a harmless placeholder; it loads
  `secrets.properties` on top of itself via `spring.config.import` if that
  file exists.
- **Set `app.base-url`** (in `application.properties`, not secret) — the
  address guests use to reach the app, e.g. `http://localhost:8080`, or your
  machine's LAN IP if testing from a phone. Stripe uses this to build the
  redirect-back URL.
- **Restart the Spring Boot app** after changing either properties file —
  they're only read once, at startup.
- **Use a Stripe test card to pay** — `4242 4242 4242 4242`, any future
  expiry, any CVV, any ZIP. (`4000 0000 0000 0002` always declines, for
  testing the failure path.) These are Stripe's own fake numbers; a real
  card number is rejected by a test-mode Checkout page on purpose.

No webhook, no Stripe CLI, and no second secret are needed — see §7 for why.

---

## 3. The pieces, and what each one is responsible for

- **`model/Payment.java`** — a MongoDB document describing one payment
  attempt. Fields: which table/customer/orders it covers, the method
  (`COUNTER` or `STRIPE`), the amount, a `status` (`PENDING` until Stripe
  confirms, then `PAID`), the Stripe session id, and Stripe's own reference
  once paid. A payment exists in the database *before* it succeeds — that's
  what makes "wait for the provider to confirm" possible.
- **`repository/PaymentRepository.java`** — plain Mongo repository for
  `Payment` (save/find by id).
- **`service/OrderService.java`** — owns all the business rules: what counts
  as "ready to pay" (every open order at the table must be `SERVED`), who
  gets loyalty points, what happens to the table and orders once paid. It
  knows nothing about Stripe specifically — it just exposes three entry
  points (see §5) that any payment method can call into.
- **`service/StripeCheckoutService.java`** — the *only* class that imports
  the Stripe SDK. It creates Stripe Checkout Sessions, and checks a
  session's real status with Stripe when the guest comes back. It delegates
  all order/loyalty logic back to `OrderService` — this class only knows
  about Stripe's API shapes.
- **`controller/PaymentController.java`** — the two HTTP endpoints involved
  (see §4).
- **`controller/OrderController.java`** — unchanged except the old
  guest "instant pay" endpoint was removed from here (replaced by
  `PaymentController`'s checkout endpoint), and the staff pay endpoint no
  longer needs a `method` field.
- **`static/index.html`** — the guest-facing QR-code page. Its "Pay your
  bill" section calls the checkout endpoint and redirects the browser to
  Stripe; it has no card form of its own, and confirms the payment when
  Stripe sends the guest back.
- **`static/staff.html`** — the staff dashboard. Its "Ready to pay" cards no
  longer have a payment-method dropdown, since staff only ever do `COUNTER`.
- **`pom.xml`** — added the `com.stripe:stripe-java` dependency (the SDK).
- **`application.properties`** — holds `app.base-url` and a placeholder
  `stripe.secret-key`, plus the `spring.config.import` line that loads
  `secrets.properties` if present.
- **`secrets.properties`** (git-ignored, project root) — the one place the
  real `stripe.secret-key` lives.

---

## 4. The two HTTP endpoints involved

- **`POST /api/orders/table/{qrCodeHash}/pay/online`**
  - Called by: the guest's browser, when they tap "Pay online."
  - Body (optional): `{ "phoneNumber": "..." }` to earn loyalty points.
  - Does: validates the bill, creates a `Payment` (`PENDING`), creates a
    Stripe Checkout Session for the total, returns `{ "checkoutUrl": "..." }`.
  - The browser then does `location.href = checkoutUrl` — a real page
    navigation to Stripe's own servers.
- **`POST /api/payments/confirm`**
  - Called by: the guest's browser, right after Stripe redirects it back.
  - Body: `{ "sessionId": "cs_test_..." }` (the Checkout Session id Stripe
    put in the redirect URL).
  - Does: asks Stripe directly (`Session.retrieve`) whether that session's
    `payment_status` is really `"paid"` — the browser's claim alone is never
    trusted — and if so, finalizes the matching `Payment`.

---

## 5. OrderService's three entry points (how business logic is shared)

- **`completePayment(qrCodeHash, phoneNumber)`** — staff confirm a counter
  payment. Validates the bill, marks it paid immediately with
  `Payment.Method.COUNTER` and `Status.PAID`.
- **`beginOnlineCheckout(qrCodeHash, phoneNumber)`** — called when the guest
  starts paying online. Validates the bill (same rules as above) and saves a
  `Payment` with `Method.STRIPE` and `Status.PENDING` — nothing is marked
  paid yet. Returns the payment plus the table number, for building the
  Stripe session.
- **`finalizeOnlinePayment(paymentId, providerRef)`** — called once Stripe
  confirms money was received. Loads the pending `Payment`, marks its orders
  `PAID`, frees the table, adds loyalty points, and marks the `Payment`
  itself `PAID`. Safe to call more than once for the same payment — a
  payment that's already `PAID` is left alone.
- All three ultimately call the same two private helpers, `loadBill` (the
  validation rules) and `settle` (mark orders paid / add points / free
  table / save the payment) — so "what counts as paid" is defined in exactly
  one place, no matter which payment method triggered it.

---

## 6. Step-by-step: a payment's full journey

1. Guest opens the table's QR-code link → `index.html` loads, shows the
   menu and their orders, and (once there's something served and unpaid)
   a "Pay your bill" section with a **Pay online** button.
2. Guest taps **Pay online** (optionally having entered a phone number for
   loyalty points).
3. Browser calls `POST /api/orders/table/{qrCodeHash}/pay/online`.
4. `PaymentController.payOnline` → `StripeCheckoutService.createCheckoutSession`:
   - calls `OrderService.beginOnlineCheckout` → validates the bill, saves a
     `Payment` document with `status=PENDING` in MongoDB.
   - builds a Stripe `SessionCreateParams` (one line item, the bill total,
     a success URL containing the placeholder `{CHECKOUT_SESSION_ID}`, a
     cancel URL, and the `Payment`'s id stashed in the session's metadata).
   - calls `Session.create(params)` — the actual network call to Stripe's API.
   - saves the returned session id onto the `Payment` (`attachStripeSession`).
   - returns the session's checkout URL to the browser.
5. Browser does `location.href = checkoutUrl` — guest is now on a page
   hosted entirely by Stripe. They type their (test) card there. This app's
   server and database see nothing of the card itself.
6. Guest finishes paying. Stripe redirects the browser back to
   `.../?t={qrCodeHash}&session_id={the real session id}`.
7. `index.html` sees `session_id` in the URL and calls
   `POST /api/payments/confirm` with it.
8. `PaymentController.confirm` → `StripeCheckoutService.confirmSession`:
   - calls `Session.retrieve(sessionId)` — asks Stripe's API directly for
     that session's current state (this is the real confirmation; nothing
     the browser says is trusted on its own).
   - if `payment_status` isn't `"paid"`, it stops here and does nothing.
   - otherwise reads the `paymentId` back out of the session's metadata and
     calls `OrderService.finalizeOnlinePayment(paymentId, providerRef)`.
9. `OrderService.finalizeOnlinePayment`:
   - loads the `Payment` (still `PENDING`), the table, the orders, and the
     customer (if any).
   - marks every order in that bill `Order.Status.PAID`.
   - if a phone number was linked, adds loyalty points to that `Customer`
     document and logs a `loyalty_transactions` entry.
   - frees the table (`RestaurantTable.Status.AVAILABLE`).
   - updates the `Payment` to `status=PAID` with Stripe's reference.
10. `index.html` shows the "Payment received" banner, and its existing
    5-second poll of `GET /api/orders/table/{qrCodeHash}` picks up the
    now-`PAID` orders shortly after, hiding the "Pay your bill" section.

---

## 7. Why confirm on return instead of a webhook?

A production payment integration normally uses a **webhook**: Stripe's
servers call your server directly and tell it a payment succeeded,
independent of whether the guest's browser ever makes it back to your page.
That's more robust (it still works if the guest closes the tab after
paying), but it needs a second secret (the webhook signing secret) and,
for local development, a tool like the Stripe CLI running in the background
to forward Stripe's calls to `localhost`.

This project instead confirms the payment **when the browser returns**:
Stripe redirects the guest back with their Checkout Session id, and the
server calls Stripe's API (`Session.retrieve`) to check that session's real
status before trusting it. This needs only the one secret key you already
have, and no extra running process — simpler to set up for a demo, at the
cost of not handling the case where the guest closes the browser tab
between paying and being redirected back (the payment would stay `PENDING`
in that rare case). A production deployment would want to add the webhook
back for that reason.

---

## 8. Staff's (unchanged) counter-payment path, for contrast

- Staff dashboard → "Ready to pay" → **Confirm payment** button.
- `POST /api/staff/orders/table/{qrCodeHash}/pay` (requires staff login).
- `OrderController.pay` → `OrderService.completePayment(qrCodeHash, phoneNumber)`.
- Same `loadBill`/`settle` helpers as the online path, just triggered
  synchronously by staff instead of by the guest's return trip — so the
  receipt comes back immediately in the same HTTP response.
