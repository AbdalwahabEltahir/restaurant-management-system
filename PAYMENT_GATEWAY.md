# Online payment (Stripe, test mode)

Guests can now pay their table's bill online, through a real Stripe Checkout
Session in test mode. No real card network is ever contacted and no real
money moves, but the integration (tokenized card form, webhook confirmation)
is the same one used in production.

## Setup

1. Create a free Stripe account at https://dashboard.stripe.com/register.
2. In the dashboard, make sure you're in **Test mode** (toggle, top right).
3. Get your test keys from **Developers > API keys** and put the secret key in
   `src/main/resources/application.properties`:
   ```
   stripe.secret-key=sk_test_...
   ```
4. Set up the webhook so Stripe can tell your server a payment went through.
   Locally, the easiest way is the [Stripe CLI](https://stripe.com/docs/stripe-cli):
   ```
   stripe listen --forward-to localhost:8080/api/payments/webhook
   ```
   This prints a webhook signing secret (`whsec_...`) — put that in
   `application.properties` too:
   ```
   stripe.webhook-secret=whsec_...
   ```
   (In a real deployment, you'd instead add a webhook endpoint in the Stripe
   dashboard pointing at your public server, and use the secret it gives you.)
5. `app.base-url` should match the address guests use (e.g. `http://localhost:8080`,
   or your machine's IP if testing from a phone). Stripe redirects the guest
   back here after they pay.
6. Pay with one of Stripe's test cards, e.g. `4242 4242 4242 4242`, any future
   expiry, any CVV. Card number `4000 0000 0000 0002` always declines, for
   testing the failure path.

## The flow

1. Guest opens the table's QR-code page and taps **Pay online** in the "Pay
   your bill" section.
2. The browser calls `POST /api/orders/table/{qrCodeHash}/pay/online`.
   `PaymentController` asks `StripeCheckoutService` to create a Stripe
   Checkout Session for the table's current total, and a `Payment` document
   is saved in MongoDB with `status: PENDING`.
3. The browser is redirected to the URL Stripe returns — a page hosted by
   Stripe, not this app. The guest enters their card there; this server never
   sees it.
4. After paying, Stripe redirects the guest back to the table page
   (`?paid=1`), which shows a "Payment received" banner. The orders list
   still shows the bill as unpaid for a moment, until the next step lands.
5. In parallel, Stripe calls `POST /api/payments/webhook` on this server with
   a signed event. `StripeCheckoutService.handleWebhook` verifies the
   signature, and for a `checkout.session.completed` event, looks up the
   `Payment` by the id it stashed in the session's metadata and calls
   `OrderService.finalizeOnlinePayment`.
6. `finalizeOnlinePayment` marks every order in that bill `PAID`, frees the
   table, adds loyalty points if a phone number was linked, and marks the
   `Payment` document `PAID` with Stripe's payment reference. The guest's
   page picks this up on its next 5-second poll.

Staff's counter-payment flow (`POST /api/staff/orders/table/{qrCodeHash}/pay`)
is unchanged in behavior, just always `COUNTER` now — staff no longer choose
a payment method, since online payment is the guest's own action.

## What changed in the code

- **`model/Payment.java`** — now has a `status` (`PENDING`/`PAID`) and a
  `stripeSessionId`, so a payment can exist before Stripe confirms it.
  `Method` is now `COUNTER` or `STRIPE` (the old simulated `ONLINE_TEST` is
  gone).
- **`service/OrderService.java`** — the old `completePayment(qrCodeHash,
  phoneNumber, method)` is now `completePayment(qrCodeHash, phoneNumber)`,
  counter-only. The shared bill validation (table exists, orders served) and
  the "mark paid, add loyalty points, free the table" logic were pulled out
  into private `loadBill`/`settle` helpers, reused by three public entry
  points: `completePayment` (staff, counter), `beginOnlineCheckout` (starts a
  pending Stripe payment), and `finalizeOnlinePayment` (called by the
  webhook once Stripe confirms).
- **`service/StripeCheckoutService.java`** (new) — the only class that talks
  to the Stripe SDK: creates Checkout Sessions and verifies/handles the
  webhook.
- **`controller/PaymentController.java`** (new) — `POST
  /api/orders/table/{qrCodeHash}/pay/online` (starts checkout, returns the
  redirect URL) and `POST /api/payments/webhook` (Stripe's callback).
- **`controller/OrderController.java`** — the old guest "instant pay" endpoint
  is gone (moved to `PaymentController`); the staff pay endpoint dropped the
  now-unused `method` field.
- **`pom.xml`** — added `com.stripe:stripe-java`.
- **`application.properties`** — added `stripe.secret-key`,
  `stripe.webhook-secret`, `app.base-url`.
- **`static/index.html`** — "Pay your bill" no longer has a fake card form;
  it redirects to Stripe's hosted page and shows a confirmation banner on
  return.
- **`static/staff.html`** — the "Payment method" dropdown is gone; staff only
  confirm counter payments now.
