Online Payment (Stripe, Test Mode)
What it does
Guests can pay their own bill from their phone at the table.
Staff can still confirm counter payments as before.
It uses Stripe test mode, so no real money moves.
Card details are entered on Stripe’s page and never touch this app.
Setup
Create a free Stripe account and switch to Test mode.
Copy the secret key (sk_test_...) into secrets.properties (git-ignored):
   stripe.secret-key=sk_test_...
Set app.base-url in application.properties (e.g. http://localhost:8080).
Restart the app.
Test cards: 4242 4242 4242 4242 succeeds, and 4000 0000 0000 0002 declines. Use any future expiry, CVV and ZIP.
Key pieces
Payment (new): a payment record with status PENDING, then PAID once Stripe confirms.
StripeCheckoutService (new): the only class that talks to Stripe.
PaymentController (new): the two online-payment endpoints.
OrderService: holds the business rules and knows nothing about Stripe.
index.html: the “Pay online” button redirects to Stripe and confirms on return.
staff.html: the payment-method dropdown is removed, since staff only do counter payments.
Endpoints
POST /api/orders/table/{qrCodeHash}/pay/online
Saves a PENDING payment, creates a Stripe session, and returns the checkout URL.
POST /api/payments/confirm
Asks Stripe whether the session is really paid, then finalizes the payment.
Flow
The guest taps Pay online.
The server saves a PENDING payment and creates a Stripe session.
The guest pays on Stripe’s page.
Stripe redirects back to the app.
The server checks with Stripe that the payment succeeded.
If it did, orders become PAID, loyalty points are added, the table is freed, and the payment becomes PAID.

Staff counter payments follow the same rules but complete immediately, with no Stripe step.

Limitation

Confirmation happens when the guest’s browser returns, not through a webhook. If the guest closes the tab after paying but before being redirected, the payment stays PENDING. A production system should add a webhook.