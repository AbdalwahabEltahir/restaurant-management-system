# Online Payment Gateway (Stripe, test mode) — Full Implementation Guide

This document walks through the whole online-payment feature end to end:
what it is, how it was built, which files do what, and exactly how a payment
travels through the system from the guest's tap to the database update.

No real card network is ever contacted and no real money moves — but the
integration pattern (hosted card form, server-side confirmation via webhook)
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
  starting `sk_test_...`. Put it in
  `src/main/resources/application.properties` as `stripe.secret-key`.
- **Install the Stripe CLI** — used to receive Stripe's webhook calls on
  your local machine (`winget install --id Stripe.StripeCli -e` on Windows).
- **Log in the CLI** — `stripe login`, which opens a browser to authorize it
  against your Stripe account.
- **Forward the webhook** — run and leave running:
  ```
  stripe listen --forward-to localhost:8080/api/payments/webhook
  ```
  This prints a signing secret `whsec_...` — put that in
  `application.properties` as `stripe.webhook-secret`. (This step only
  exists because we're on localhost; a real deployment would register a
  webhook URL in the Stripe dashboard instead of using the CLI.)
- **Set `app.base-url`** — the address guests use to reach the app (e.g.
  `http://localhost:8080`, or your machine's LAN IP for testing from a
  phone). Stripe uses this to build the redirect-back URL.
- **Restart the Spring Boot app** after changing `application.properties` —
  it only reads these values once, at startup.
- **Use a Stripe test card to pay** — `4242 4242 4242 4242`, any future
  expiry, any CVV, any ZIP. (`4000 0000 0000 0002` always declines, for
  testing the failure path.) These are Stripe's own fake numbers; a real
  card number is rejected by a test-mode Checkout page on purpose.

---

## 3. The pieces, and what each one is responsible for

- **`model/Payment.java`** — a MongoDB document describing one payment
  attempt. Fields: which table/customer/orders it covers, the method
  (`COUNTER` or `STRIPE`), the amount, a `status` (`PENDING` until Stripe
  confirms, then `PAID`), the Stripe session id, and Stripe's own reference
  once paid. A payment exists in the database *before* it succeeds — that's
  what makes the "wait for the provider to confirm" model possible.
- **`repository/PaymentRepository.java`** — plain Mongo repository for
  `Payment` (save/find by id).
- **`service/OrderService.java`** — owns all the business rules: what counts
  as "ready to pay" (every open order at the table must be `SERVED`), who
  gets loyalty points, what happens to the table and orders once paid. It
  knows nothing about Stripe specifically — it just exposes three entry
  points (see §5) that any payment method can call into.
- **`service/StripeCheckoutService.java`** — the *only* class that imports
  the Stripe SDK. It creates Stripe Checkout Sessions and validates/handles
  Stripe's webhook callback. It delegates all order/loyalty logic back to
  `OrderService` — this class only knows about Stripe's API shapes.
- **`controller/PaymentController.java`** — the two HTTP endpoints Stripe
  and the guest's browser actually hit (see §4).
- **`controller/OrderController.java`** — unchanged except the old
  guest "instant pay" endpoint was removed from here (it now lives in
  `PaymentController`), and the staff pay endpoint no longer needs a
  `method` field.
- **`static/index.html`** — the guest-facing QR-code page. Its "Pay your
  bill" section calls the checkout endpoint and redirects the browser to
  Stripe; it has no card form of its own.
- **`static/staff.html`** — the staff dashboard. Its "Ready to pay" cards no
  longer have a payment-method dropdown, since staff only ever do `COUNTER`.
- **`pom.xml`** — added the `com.stripe:stripe-java` dependency (the SDK).
- **`application.properties`** — holds the three pieces of Stripe config:
  `stripe.secret-key`, `stripe.webhook-secret`, `app.base-url`.

---

## 4. The two HTTP endpoints involved

- **`POST /api/orders/table/{qrCodeHash}/pay/online`**
  - Called by: the guest's browser, when they tap "Pay online."
  - Body (optional): `{ "phoneNumber": "..." }` to earn loyalty points.
  - Does: validates the bill, creates a `Payment` (`PENDING`), creates a
    Stripe Checkout Session for the total, returns `{ "checkoutUrl": "..." }`.
  - The browser then does `location.href = checkoutUrl` — a real page
    navigation to Stripe's own servers.
- **`POST /api/payments/webhook`**
  - Called by: Stripe's servers, never the browser or staff.
  - Body: a signed JSON event describing what happened to a Checkout
    Session.
  - Does: verifies the signature really came from Stripe (using
    `stripe.webhook-secret`), and if the event is `checkout.session.completed`,
    finds the matching `Payment` and finalizes it.

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
- **`finalizeOnlinePayment(paymentId, providerRef)`** — called only by the
  webhook handler, once Stripe confirms money was received. Loads the
  pending `Payment`, marks its orders `PAID`, frees the table, adds loyalty
  points, and marks the `Payment` itself `PAID`.
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
     success/cancel URLs pointing back at the table's page, and the
     `Payment`'s id stashed in the session's metadata).
   - calls `Session.create(params)` — this is the actual network call to
     Stripe's API.
   - saves the returned session id onto the `Payment` (`attachStripeSession`).
   - returns the session's checkout URL to the browser.
5. Browser does `location.href = checkoutUrl` — guest is now on a page
   hosted entirely by Stripe. They type their (test) card there. This app's
   server and database see nothing of the card itself.
6. Guest finishes paying on Stripe's page. Two things happen, in parallel,
   both initiated by Stripe:
   - **Browser redirect**: Stripe sends the guest's browser back to
     `.../?t={qrCodeHash}&paid=1`. `index.html` sees `paid=1` and shows a
     "Payment received, thank you" banner — but the order list hasn't
     changed yet, because this redirect doesn't carry proof of payment by
     itself.
   - **Server webhook**: Stripe's backend calls this app's backend directly:
     `POST /api/payments/webhook`, with a signed `checkout.session.completed`
     event. This is the actual proof of payment — it comes from Stripe's
     servers, not from anything the guest's browser could fake.
7. `PaymentController.webhook` → `StripeCheckoutService.handleWebhook`:
   - verifies the request's signature against `stripe.webhook-secret`
     (rejects it if it doesn't match — stops anyone from forging a fake
     "payment completed" call).
   - reads the `paymentId` back out of the session's metadata.
   - calls `OrderService.finalizeOnlinePayment(paymentId, providerRef)`.
8. `OrderService.finalizeOnlinePayment`:
   - loads the `Payment` (still `PENDING`), the table, the orders, and the
     customer (if any).
   - marks every order in that bill `Order.Status.PAID`.
   - if a phone number was linked, adds loyalty points to that `Customer`
     document and logs a `loyalty_transactions` entry.
   - frees the table (`RestaurantTable.Status.AVAILABLE`).
   - updates the `Payment` to `status=PAID` with Stripe's reference.
9. Guest's page is polling `GET /api/orders/table/{qrCodeHash}` every 5
   seconds (this was already happening, unrelated to payment). On its next
   poll, the orders come back `PAID`, the bill shows as settled, and the
   "Pay your bill" section hides itself.

---

## 7. Why two separate signals (redirect *and* webhook)?

- The **redirect** tells the browser "the guest is done," so the page can
  show a friendly message — but a browser redirect can be faked, delayed,
  or never arrive (closed tab, lost connection) and proves nothing about
  whether money actually moved.
- The **webhook** is the one source of truth: it's a direct, signed,
  server-to-server call from Stripe, so it's what actually moves orders to
  `PAID` and awards loyalty points. This is exactly why the implementation
  needs both the CLI-forwarded webhook locally and a registered webhook URL
  in production — without it, payments would stay `PENDING` forever no
  matter what the browser shows.

---

## 8. Staff's (unchanged) counter-payment path, for contrast

- Staff dashboard → "Ready to pay" → **Confirm payment** button.
- `POST /api/staff/orders/table/{qrCodeHash}/pay` (requires staff login).
- `OrderController.pay` → `OrderService.completePayment(qrCodeHash, phoneNumber)`.
- Same `loadBill`/`settle` helpers as the online path, just triggered
  synchronously by staff instead of asynchronously by a webhook — so the
  receipt comes back immediately in the same HTTP response.
